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

    @Volatile private var session: String? = null
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** 界面要显示的：已经录了多久、这场的本地 id */
    @Volatile var elapsedMs: Long = 0; private set
    /** 此刻的真实状态。界面照着念——不许自己维护一份「我以为在录」 */
    @Volatile var state: RecordState = RecordState.IDLE; private set
    val currentSession: String? get() = session
    val isRecording: Boolean get() = running.get()

    /** 返回本地会话 id；返回 null 表示麦克风打不开（权限被拒、或被别的应用占着）。 */
    fun start(title: String, now: Long): String? {
        if (running.get()) return session
        val rec = Capture.open() ?: return null
        state = RecordState.RECORDING
        val meta = SessionMeta(UUID.randomUUID().toString(), title, now)
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
        }
        state = if (state == RecordState.GAVE_UP) RecordState.GAVE_UP else RecordState.IDLE
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
                elapsedMs = startMs + open.elapsedMs
                sinceSync += n
                // 每 2 秒落一次盘。不是每次都 sync——那会让磁盘一直忙；
                // 也不能不 sync——掉电时没 flush 的部分就是没录到。
                if (sinceSync >= Capture.BYTES_PER_MS * 2000) { open.sync(); sinceSync = 0 }

                if (open.elapsedMs >= Capture.SEGMENT_MS) {
                    val end = open.trueEndMs
                    seal(s, open, seq, startMs, end)
                    seq++; startMs = end
                    open = openSegment(s, seq, startMs)
                }
            }
        } catch (e: Exception) {
            // 录音线程出事不能把已经录到的东西一起带走
        } finally {
            val end = open.trueEndMs
            seal(s, open, seq, startMs, end)
            seq++; startMs = end
        }
        return seq to startMs
    }

    private fun openSegment(s: String, seq: Int, startMs: Long): OpenSegment {
        // 名字里先写「计划结束时刻」。真正的结束在封段时按字节数算出来再改名——
        // 但这一刻就得是个合法名字，否则录到一半被杀，下次启动认不出这个文件。
        val planned = Segment(seq, startMs, startMs + Capture.SEGMENT_MS, Segment.State.RECORDING)
        return OpenSegment(vault.segmentFile(s, planned), seq, startMs)
    }

    private fun seal(s: String, open: OpenSegment, seq: Int, startMs: Long, endMs: Long) {
        open.close()
        val planned = Segment(seq, startMs, startMs + Capture.SEGMENT_MS, Segment.State.RECORDING)
        val pcm = vault.segmentFile(s, planned)
        if (!pcm.isFile || pcm.length() < Capture.BYTES_PER_MS * 10) { pcm.delete(); return }
        val truth = Segment(seq, startMs, endMs, Segment.State.RECORDING)
        val truthFile = vault.segmentFile(s, truth)
        if (pcm.absolutePath != truthFile.absolutePath) pcm.renameTo(truthFile)
        if (sealPcm(s, truth)) onSegmentSealed()
    }

    /**
     * 把一个已经定好真实时长的 PCM 转成 m4a。
     * 独立出来是因为启动时的孤儿回收也走它——两条路必须是同一段代码，
     * 不然「录音时封的段」和「崩溃后补封的段」会有细微差别而没人发现。
     */
    private fun sealPcm(s: String, truth: Segment): Boolean {
        val pcm = vault.segmentFile(s, truth)
        val aac = vault.segmentFile(s, truth.withState(Segment.State.SEALED))
        if (!Encoder.pcmToAac(pcm, aac)) return false
        pcm.delete()          // 转码确认成功之后才删。反过来一次失败就丢一分钟录音
        return true
    }

    /**
     * 启动时回收孤儿：上次被杀时留下的、还没封的 PCM。
     *
     * 它们的文件名里写的是「计划结束时刻」，而实际只录到一部分。所以要先按
     * 字节数把名字改成真实时长，再封段——否则这一段会声称自己有 60 秒，
     * 而深脑那边的时间轴会从这里开始整场错位。
     */
    fun recoverOrphans() {
        for (s in vault.sessions()) {
            if (s == session) continue                       // 正在录的这场不动
            for (seg in vault.segments(s)) {
                if (seg.state != Segment.State.RECORDING) continue
                val f = vault.segmentFile(s, seg)
                val real = Capture.durationMsOf(f.length())
                val truth = seg.copy(endMs = seg.startMs + real)
                if (real < 10) { f.delete(); continue }
                if (truth != seg) f.renameTo(vault.segmentFile(s, truth))
                sealPcm(s, truth)
            }
        }
    }
}
