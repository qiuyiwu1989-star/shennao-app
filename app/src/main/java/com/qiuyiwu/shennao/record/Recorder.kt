package com.qiuyiwu.shennao.record

import android.media.AudioRecord
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/*
 * 录音编排：开始 → 每 60 秒封一段 → 停止。
 *
 * 「封段」是这一版唯一的关键动作，它必须能被打断后重做：
 *   1. 关掉 PCM 文件并同步到磁盘
 *   2. 按真实字节数算出真正的结束时刻，改名（此前名字里写的是「计划长度」）
 *   3. 转成 m4a
 *   4. 删掉 PCM
 * 任何一步之间挂掉，下次启动扫到那个 .pcm 都能从头再走一遍——
 * 每一步都是幂等的，重做不会多出也不会少掉一段。
 */
class Recorder(private val vault: FileVault, private val onSegmentSealed: () -> Unit) {

    /**
     * 实时字幕的出口。可以为 null——字幕是附赠，没有它录音照常。
     * 喂数据保证不阻塞（见 Realtime.feed），所以采集循环里直接调是安全的。
     */
    @Volatile var realtime: Realtime? = null

    @Volatile private var session: String? = null
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** 界面要显示的：已经录了多久、这场的本地 id */
    @Volatile var elapsedMs: Long = 0; private set
    /** 此刻的真实状态。界面照着念——不许自己维护一份「我以为在录」 */
    @Volatile var state: RecordState = RecordState.IDLE; private set
    /**
     * 当前音量，0..1。
     *
     * 计时器只能证明「时间在走」，证明不了「录到了声音」——
     * 一个被静音的麦克风，计时器照样走得好好的。声波是唯一能一眼看出
     * 「它真的在听」的东西，而这正是用户最想确认的那件事。
     */
    @Volatile var level: Float = 0f; private set
    @Volatile private var diskFailed = false
    val currentSession: String? get() = session
    val isRecording: Boolean get() = running.get()

    /** 返回本地会话 id；返回 null 表示麦克风打不开（权限被拒、或被别的应用占着）。 */
    fun start(title: String, now: Long, scene: String? = null): String? {
        if (running.get()) return session
        val rec = Capture.open() ?: return null
        state = RecordState.RECORDING
        diskFailed = false
        val meta = SessionMeta(UUID.randomUUID().toString(), title, now, scene = scene)
        val s = vault.newSession(meta)
        session = s
        elapsedMs = 0
        running.set(true)
        thread = Thread({ loop(rec, s) }, "shennao-rec").apply { priority = Thread.MAX_PRIORITY; start() }
        return s
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(5_000)
        thread = null
        val s = session ?: return
        // 标记「用户已停止」必须在最后一段封完之后。反过来的话，上传器可能
        // 在最后一段还没封时就去冻结清单，那一段就永久进不去了。
        vault.updateMeta(s) { it.copy(finished = true) }
        state = RecordState.IDLE
        session = null
        onSegmentSealed()
    }

    private fun loop(first: AudioRecord, s: String) {
        val buf = ByteArray(Capture.BYTES_PER_MS * 200)      // 200 ms 一读
        var seq = 0
        var startMs = 0L
        var rec: AudioRecord? = first
        var attempts = 0

        while (running.get()) {
            val device = rec ?: run {
                // 麦克风还没抢回来。先说真话，再按退避节奏试。
                state = RecordState.INTERRUPTED
                if (Interruption.shouldGiveUp(attempts)) {
                    // 试了十分钟没回来。继续亮着「正在恢复」比明说「停了」更糟——
                    // 用户会以为还在录，而其实早就不是了。
                    state = RecordState.GAVE_UP
                    running.set(false)
                    return
                }
                attempts++
                // 分片睡，每 200 ms 看一眼还要不要录。一觉睡 5 秒的话，
                // 用户按下停止之后要等满这 5 秒，而 stop() 只等 5 秒就不等了。
                var slept = 0L
                val want = Interruption.delayMsFor(attempts)
                while (slept < want && running.get()) { Thread.sleep(200); slept += 200 }
                if (!running.get()) null
                else Capture.open()?.also { state = RecordState.RECORDING; attempts = 0 }
            }
            if (device == null) continue
            // 抢回麦克风的同时用户按了停止：这台设备再也不会被用到，
            // 不还回去就是一直占着别人的麦克风。
            if (!running.get()) {
                runCatching { device.release() }
                break
            }
            rec = device

            // 一段录音，读到断为止。返回下一段该从哪个序号、哪个毫秒开始。
            val (nextSeq, nextStart) = readUntilBroken(device, s, seq, startMs, buf)
            seq = nextSeq
            startMs = nextStart
            runCatching { device.stop() }; runCatching { device.release() }
            rec = null
            if (diskFailed) { state = RecordState.DISK_FULL; running.set(false); return }
        }
        state = when (state) { RecordState.GAVE_UP, RecordState.DISK_FULL -> state; else -> RecordState.IDLE }
    }

    /**
     * 一直读到读不动为止。中断时把当前这段封好再返回——
     * 已经录到的字节必须先安全落盘，重开麦克风是之后的事。
     *
     * 返回：下一段的序号和起始毫秒。**起始毫秒接着走，不给中断留空洞**：
     * 中断那几分钟本来就不在音频里，留空白只会让后面所有分片的时间往后错。
     */
    private fun readUntilBroken(
        rec: AudioRecord, s: String, startSeq: Int, from: Long, buf: ByteArray,
    ): Pair<Int, Long> {
        var seq = startSeq
        var startMs = from
        var open = openSegment(s, seq, startMs)
        try {
            rec.startRecording()
            var sinceSync = 0L
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    // 读不出来 = 麦克风被抢走了（来电、别的应用、系统回收）。
                    // 不是错误，是常态——封好这一段，交给外层去抢回来。
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break
                    continue
                }
                open.write(buf, n)
                // 顺手分一份给字幕。它内部是有界队列、塞不下就丢——
                // 丢的是字幕，不是录音。这个顺序不能反：先落盘，再分流。
                realtime?.feed(buf, n)
                level = Level.of(buf, n)
                elapsedMs = startMs + open.elapsedMs
                sinceSync += n
                // 每 2 秒落一次盘。不是每次都 sync——那会让磁盘一直忙；
                // 也不能不 sync——掉电时没 flush 的部分就是没录到。
                if (sinceSync >= Capture.BYTES_PER_MS * 2000) { open.sync(); sinceSync = 0 }

                if (open.elapsedMs >= Capture.SEGMENT_MS) {
                    // **下一片的起点必须是上一片的真实结束**，不是按 PCM 算的那个。
                    // 服务端要求时间轴逐片严丝合缝（每片 started_at_ms 必须等于
                    // 上一片的 ended_at_ms），差一毫秒清单就不合法。
                    startMs = seal(s, open, seq, startMs, open.trueEndMs)
                    seq++
                    open = openSegment(s, seq, startMs)
                }
            }
        } catch (e: java.io.IOException) {
            // 写不进磁盘（多半是满了）。以前这里吞掉之后重开麦克风继续写、继续失败，
            // 界面照常走时——录了个寂寞（012 P0-7）。标出来，让上面停下。
            diskFailed = true
        } catch (e: Exception) {
            // 录音线程出事不能把已经录到的东西一起带走
        } finally {
            startMs = seal(s, open, seq, startMs, open.trueEndMs)
            seq++
        }
        return seq to startMs
    }

    private fun openSegment(s: String, seq: Int, startMs: Long): OpenSegment {
        // 名字里先写「计划结束时刻」。真正的结束在封段时按字节数算出来再改名——
        // 但这一刻就得是个合法名字，否则录到一半被杀，下次启动认不出这个文件。
        val planned = Segment(seq, startMs, startMs + Capture.SEGMENT_MS, Segment.State.RECORDING)
        return OpenSegment(vault.segmentFile(s, planned), seq, startMs)
    }

    /**
     * 封一段，返回**下一段该从哪个毫秒开始**。
     *
     * 返回值不是 `endMs` 而是转码之后的真实结束：AAC 一帧固定 1024 个样本，
     * 编出来的时长几乎不可能和按 PCM 字节数算的那个正好相等（实测差 160 毫秒）。
     * 而服务端要求逐片严丝合缝——2026-08-31 我改成「时长以产物为准」时
     * 只改了这一片的名字，没改下一片的起点，结果**从那天起一条都传不上去**：
     * 每片都差那 160 毫秒，清单永远不合法。
     */
    private fun seal(s: String, open: OpenSegment, seq: Int, startMs: Long, endMs: Long): Long {
        open.close()
        val planned = Segment(seq, startMs, startMs + Capture.SEGMENT_MS, Segment.State.RECORDING)
        val pcm = vault.segmentFile(s, planned)
        // 太短、封不成的：下一段仍从这一段声称的结束开始，不留空洞。
        if (!pcm.isFile || pcm.length() < Capture.BYTES_PER_MS * 10) { pcm.delete(); return endMs }
        val truth = Segment(seq, startMs, endMs, Segment.State.RECORDING)
        val truthFile = vault.segmentFile(s, truth)
        if (pcm.absolutePath != truthFile.absolutePath) pcm.renameTo(truthFile)
        val real = sealPcm(s, truth)
        if (real != null) { onSegmentSealed(); return real }
        return endMs
    }

    /**
     * 把一个已经定好真实时长的 PCM 转成 m4a。
     * 独立出来是因为启动时的孤儿回收也走它——两条路必须是同一段代码，
     * 不然「录音时封的段」和「崩溃后补封的段」会有细微差别而没人发现。
     */
    private fun sealPcm(s: String, truth: Segment): Long? {
        val pcm = vault.segmentFile(s, truth)
        val aac = vault.segmentFile(s, truth.withState(Segment.State.SEALED))

        /*
         * **先编到临时名，编完才改成 .aac。**
         *
         * 2026-08-31 的丢数据事故就出在这里：编码器直接写最终文件名，
         * 而 .aac 这个后缀在本设计里的含义是「已封段、可以上传」。
         * 于是编码进行到一半时，磁盘上已经存在一个叫 .aac 的半成品，
         * 上传器每 15 秒扫一次目录，扫到就传——传上去的是 13.3 秒，
         * 而那一段实际有 60 秒。**服务端不会报错，用户也看不出来。**
         *
         * 这违背的正是本文件开头那条「名字即状态」：既然名字就是状态，
         * 那么在东西没到那个状态之前，就一个字节都不能叫那个名字。
         * meta.json 那里写对了（先写临时文件再改名），这里当初漏了。
         */
        val part = java.io.File(aac.parentFile, aac.name + ".part")
        part.delete()
        if (!Encoder.pcmToAac(pcm, part)) { part.delete(); return null }
        // 改名是原子的：要么还没有 .aac，要么就是完整的 .aac，不存在中间态。
        if (!part.renameTo(aac)) { part.delete(); return null }
        pcm.delete()          // 转码确认成功之后才删。反过来一次失败就丢一分钟录音

        // **时长以产物为准，不以计划为准。**
        //
        // 2026-08-31 实测：一段声称 60 秒的分片，实际编出来只有 13.31 秒。
        // 服务端拿 60 秒去拼时间轴，等于每段凭空多出 47 秒空白，整场时间全错位，
        // 而没有任何一层会报错。编码器为什么少读是另一个要查的问题，
        // 但无论为什么，都不该由服务端替我们承担这个谎。
        val real = Adts.scan(runCatching { aac.readBytes() }.getOrElse { return truth.endMs })
            .durationMs(Capture.SAMPLE_RATE)
        if (real <= 0) return truth.endMs
        val trueEnd = truth.startMs + real
        if (trueEnd != truth.endMs) {
            val fixed = truth.copy(endMs = trueEnd).withState(Segment.State.SEALED)
            if (!aac.renameTo(vault.segmentFile(s, fixed))) return truth.endMs
        }
        // 返回真实结束——调用方拿它当下一段的起点，时间轴才接得上。
        return trueEnd
    }

    /**
     * 启动时回收孤儿：上次被杀时留下的、还没封的 PCM。
     *
     * 它们的文件名里写的是「计划结束时刻」，而实际只录到一部分。所以要先按
     * 字节数把名字改成真实时长，再封段——否则这一段会声称自己有 60 秒，
     * 而深脑那边的时间轴会从这里开始整场错位。
     */
    /** 返回最近一个被补封的孤儿段结束的墙上时刻；没有孤儿就 null。调用方据此告诉用户「上次被停掉了」。 */
    fun recoverOrphans(): Long? {
        var latest: Long? = null
        for (s in vault.sessions()) {
            if (s == session) continue                       // 正在录的这场不动
            val startedAt = vault.readMeta(s)?.startedAtEpochMs ?: 0L
            for (seg in vault.segments(s)) {
                if (seg.state != Segment.State.RECORDING) continue
                val f = vault.segmentFile(s, seg)
                val real = Capture.durationMsOf(f.length())
                val truth = seg.copy(endMs = seg.startMs + real)
                if (real < 10) { f.delete(); continue }
                if (truth != seg) f.renameTo(vault.segmentFile(s, truth))
                sealPcm(s, truth)   // 孤儿是整场的最后一段，没有「下一段」要对齐
                val wall = startedAt + truth.endMs
                if (startedAt > 0 && (latest == null || wall > latest!!)) latest = wall
            }
        }
        return latest
    }
}
