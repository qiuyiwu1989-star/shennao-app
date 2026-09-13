package com.qiuyiwu.shennao.record

import android.annotation.SuppressLint
import android.media.AudioRecord
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全时聆听：麦克风一直开着，但**只有有人说话时才真的录**。
 *
 * ── 为什么不是「一直录，静音不上传」 ──────────────────────────
 *
 * 因为分片时间轴有一条不能破的不变量：每一片的 started_at_ms 必须等于上一片的
 * ended_at_ms，差一毫秒服务端就判清单不合法（Recorder.seal 的注释里记着那次
 * 「从那天起一条都传不上去」）。静音跳过去，时间轴上就是个空洞。
 *
 * 所以换个切法：**一段连续说话 = 一次完整的录音会话**。静音的时候根本没有会话，
 * 也就没有空洞可捅。代价是一天会多出若干场而不是一场，这是对的——
 * 上午的会和晚饭的闲聊本来就不是同一场。
 *
 * ── 一次完整的历程 ────────────────────────────────────────
 *
 *   听着（麦克风在本对象手里，读到的音频只用来算音量，不落盘）
 *     ↓ 有人开口，且熬过了最短语音段
 *   交接（把**正在录的那台设备**连同前置缓冲一起交给 Recorder）
 *     ↓ 录着（麦克风在 Recorder 手里，本对象靠 onFrame 回调继续听）
 *   静了足够久 → 停掉这场，它自己会传上去；麦克风还回来，回到「听着」
 *
 * 交接不先关再开：关一次再开一次要一两百毫秒，而那正好落在一句话的开头。
 *
 * ── 三个容易写错的地方 ────────────────────────────────────
 *
 * 一、**开口就起录是不对的**。OPEN 只说明「像是有人开口」，关门声和咳嗽都能触发。
 *     要等它熬过 minSpeechMs 才起录，在那之前音频只压在内存里。
 *
 * 二、**不能等 CLOSE 再起录**。CLOSE 要到几十秒静音之后才来，那时话早说完了。
 *
 * 三、**挂起时延要按场景放大**。会议用 900 毫秒（一句话内部的停顿），
 *     全时聆听用几十秒——按 900 毫秒切，一场闲聊会被切成几百场。
 */
class AlwaysOn(
    private val recorder: Recorder,
    /** 起一场录音。返回本地会话 id，null = 起不来（麦克风、磁盘） */
    private val begin: (adopt: AudioRecord, preroll: ByteArray) -> String?,
    /** 收一场录音。由调用方去走停止、上传那一路 */
    private val end: () -> Unit,
    /** 静多久算这一场结束。邱 2026-09-12：「切得太碎」，40 秒 → 10 分钟；09-13 再定 20 分钟 */
    hangoverMs: Int = HANGOVER_MS,
    /** 连续说满多久才起一场。咳嗽、关门、单句应答不再开场 */
    private val minSpeechMs: Int = MIN_SPEECH_MS,
    /** 一场最长多久，到点自动切下一场（接着录，不丢话头）。现在不封顶，这条路留着 */
    private val maxSessionMs: Long = MAX_SESSION_MS,
) {
    companion object {
        /** 邱 2026-09-13 定：安静 20 分钟算一场结束 */
        const val HANGOVER_MS = 20 * 60_000
        const val MIN_SPEECH_MS = 20_000
        /**
         * 邱 2026-09-13 定：一场不封顶。代价是很长的一场要等它整个结束才开始转写分析；
         * 服务端转写按分件跑，几个小时的一场能处理，但出稿会晚。
         */
        const val MAX_SESSION_MS = Long.MAX_VALUE
    }
    /** 听着的时候多久读一次。100 毫秒：前置缓冲的颗粒度，也是判开口的颗粒度 */
    private val frameMs = 100

    private val gate = VoiceGate(frameMs = frameMs, hangoverMs = hangoverMs, minSpeechMs = minSpeechMs)
    /** 上一场是到 60 分钟切掉的，不是说完了：下一场一开口就起，不再等 20 秒 */
    @Volatile private var rollover = false
    /** 正录着的这一场是接着上一场切过来的：门没熬满 20 秒时的 DISCARD 不算数，只认 CLOSE */
    @Volatile private var continuedFromRollover = false
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** 界面要显示的：此刻是在听、还是在录 */
    enum class Phase { OFF, LISTENING, RECORDING }
    @Volatile var phase: Phase = Phase.OFF; private set
    /** 今天真正录进去多少毫秒。**这就是计量的那个数**，不是开着的时长 */
    val speechMs: Long get() = gate.speechMs
    /** 听着的时候的实时音量，给声波用 */
    @Volatile var level: Float = 0f; private set
    /** 起不来的原因。null = 没问题 */
    @Volatile var problem: String? = null; private set

    /** 录音那一路报回来的：该收尾了。由采集线程置位，聆听线程读 */
    @Volatile private var shouldEnd = false

    fun start(): Boolean {
        if (running.get()) return true
        running.set(true)
        problem = null
        thread = Thread({ loop() }, "shennao-listen").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(3_000)
        thread = null
        recorder.onFrame = null
        // 关掉聆听时手上还有一场正录着：**必须收掉**。
        // 留着它就是一场永远不会结束、也永远不会上传的录音，
        // 而界面上「全时聆听」已经是关的了——用户不会再去找它。
        if (recorder.isRecording) runCatching { end() }
        phase = Phase.OFF
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        while (running.get()) {
            if (recorder.isRecording) { waitWhileRecording(); continue }

            val mic = Capture.open()
            if (mic == null) {
                // 麦克风被别的应用占着。**不要停**——占着它的多半是一通电话，
                // 挂了就还回来了。但要说真话：这段时间是听不见的。
                problem = "麦克风被别的应用占着，这段时间听不见"
                phase = Phase.OFF
                sleepInterruptibly(3_000)
                continue
            }
            problem = null
            listenUntilSpeech(mic)
        }
        phase = Phase.OFF
    }

    /**
     * 拿着麦克风听，直到判定该起录为止。
     *
     * 前置缓冲用环形队列压着最近 [VoiceGate.prerollMs] + 最短语音段那么久的音频——
     * 起录的那一刻要把它整个写进去，否则这句话的开头就没了。
     */
    private fun listenUntilSpeech(mic: AudioRecord) {
        var handedOver = false
        val bytesPerFrame = Capture.BYTES_PER_MS * frameMs
        val buf = ByteArray(bytesPerFrame)
        // 压住「判定开口之前的 preroll」+「熬最短语音段期间的全部」。
        // 多压一点无所谓（一秒的 16k PCM 只有 32 KB），少压一点就是把话头切掉。
        // 要压住「判定开口之前的 preroll」+「熬最短语音段期间的全部」：20 秒的 16k PCM 是 640 KB，可以接受
        val ring = PcmRing(bytesPerFrame * ((gate.prerollMs + minSpeechMs + 500) / frameMs))
        phase = Phase.LISTENING
        try {
            mic.startRecording()
            while (running.get()) {
                val n = mic.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) return
                    continue
                }
                level = Level.of(buf, n)
                ring.push(buf, n)
                val e = gate.feed(level)
                if (e == VoiceGate.Event.DISCARD) ring.clear()   // 一声咳嗽，忘掉它
                // 起录的条件不是「开口了」，是「开口了并且说够了」。
                if (gate.state == VoiceGate.State.SPEAKING && (gate.voicedMsSoFar >= minSpeechMs || rollover)) {
                    // 到点切下一场起的那一场，门还没熬过 20 秒：它随后的 DISCARD 不能把这场收掉——
                    // 2026-09-13 实录：60 分钟切场后紧跟一场 7 秒的，就是被 DISCARD 收的。
                    continuedFromRollover = rollover
                    rollover = false
                    handedOver = handOver(mic, ring)
                    return
                }
            }
        } catch (e: Exception) {
            problem = "听不下去了：${e.message ?: "麦克风出错"}"
        } finally {
            // 交接成功时麦克风已经归 Recorder 了，这里一个字节都不能动它。
            // 判据用「交没交出去」这个事实，不用 recorder.isRecording——
            // 后者在交接失败的那条路上已经被 handOver 处理过一次，再判一次就是重复释放。
            if (!handedOver) { runCatching { mic.stop() }; runCatching { mic.release() } }
        }
    }

    /** 把正在录的这台设备连同攒下的音频交给 Recorder。返回「交出去了没有」。 */
    private fun handOver(mic: AudioRecord, ring: PcmRing): Boolean {
        val preroll = ring.drain()
        shouldEnd = false
        // 先挂回调再起录：反过来的话，起录到挂上回调之间的那几帧没人听，
        // 万一那几帧正好是最后几帧，这一场就永远等不到收尾。
        recorder.onFrame = { lvl, ms -> onRecordingFrame(lvl, ms) }
        val id = begin(mic, preroll)
        if (id == null) {
            recorder.onFrame = null
            problem = "起不来这一场——存储或权限的问题"
            runCatching { mic.stop() }; runCatching { mic.release() }
            phase = Phase.LISTENING
            return false
        }
        phase = Phase.RECORDING
        return true
    }

    /**
     * 录音线程每读一块就来一次。**只做判断，不做收尾**——收尾要 join 录音线程，
     * 在录音线程上 join 它自己就是死锁。置个位，让聆听线程去做。
     */
    private fun onRecordingFrame(lvl: Float, ms: Int) {
        level = lvl
        // 采集是 200 毫秒一块，而门是按 100 毫秒一帧标定的。按帧数喂，
        // 时间才对得上——直接喂一次，静音时长会算成实际的一半，
        // 一段话没说完就被收尾了。
        var left = ms
        while (left > 0 && !shouldEnd) {
            val e = gate.feed(lvl)
            if (e == VoiceGate.Event.CLOSE || (e == VoiceGate.Event.DISCARD && !continuedFromRollover)) shouldEnd = true
            left -= frameMs
        }
    }

    /** 录着的时候聆听线程在这里等：等它说完，或者等用户关掉全时聆听。 */
    private fun waitWhileRecording() {
        phase = Phase.RECORDING
        while (running.get() && recorder.isRecording && !shouldEnd) {
            // 到 60 分钟切一场：太长的一场分析起来也慢；切完接着录，下一场不再等 20 秒
            if (recorder.elapsedMs >= maxSessionMs) { rollover = true; break }
            sleepInterruptibly(200)
        }
        recorder.onFrame = null
        if (recorder.isRecording) end()      // 静够了（或到点了）：收这一场，它自己会传上去
        shouldEnd = false
        continuedFromRollover = false
        phase = Phase.LISTENING
    }

    private fun sleepInterruptibly(ms: Long) {
        var slept = 0L
        while (slept < ms && running.get()) { Thread.sleep(minOf(100L, ms - slept)); slept += 100 }
    }
}

/**
 * 定长环形缓冲，压着最近的 PCM。
 *
 * 用环而不是 ArrayDeque<ByteArray>：听着的时候这个循环每秒跑十次、可能跑一整天，
 * 每帧都分配一个数组就是每秒十次垃圾。环从头到尾只有一块内存。
 *
 * 纯逻辑，不碰安卓 API，可以在 JVM 上直接测。
 */
class PcmRing(private val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var head = 0        // 下一个写入位置
    private var filled = 0      // 已经装了多少字节（到 capacity 为止）

    fun push(src: ByteArray, n: Int) {
        if (capacity == 0) return
        // 一次就塞满还有余：只留最后 capacity 个字节，前面的本来也会被覆盖掉
        val from = if (n > capacity) n - capacity else 0
        val len = n - from
        val first = minOf(len, capacity - head)
        System.arraycopy(src, from, buf, head, first)
        if (len > first) System.arraycopy(src, from + first, buf, 0, len - first)
        head = (head + len) % capacity
        filled = minOf(capacity, filled + len)
    }

    /** 按时间顺序取出来，并清空。 */
    fun drain(): ByteArray {
        val out = snapshot()
        clear()
        return out
    }

    fun snapshot(): ByteArray {
        if (filled == 0) return ByteArray(0)
        val out = ByteArray(filled)
        val start = (head - filled + capacity) % capacity
        val first = minOf(filled, capacity - start)
        System.arraycopy(buf, start, out, 0, first)
        if (filled > first) System.arraycopy(buf, 0, out, first, filled - first)
        return out
    }

    fun clear() { head = 0; filled = 0 }

    val size: Int get() = filled
}
