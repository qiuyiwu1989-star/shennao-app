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
    val currentSession: String? get() = session
    val isRecording: Boolean get() = running.get()

    /** 返回本地会话 id；返回 null 表示麦克风打不开（权限被拒、或被别的应用占着）。 */
    fun start(title: String, now: Long): String? {
        if (running.get()) return session
        val rec = Capture.open() ?: return null
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
        vault.readMeta(s)?.let { vault.writeMeta(s, it.copy(finished = true)) }
        session = null
        onSegmentSealed()
    }

    private fun loop(rec: AudioRecord, s: String) {
        val buf = ByteArray(Capture.BYTES_PER_MS * 200)      // 200 ms 一读
        var seq = 0
        var startMs = 0L
        var open = openSegment(s, seq, startMs)
        try {
            rec.startRecording()
            var sinceSync = 0L
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    // 读不出来通常是被别的应用抢走了麦克风。硬转圈只会烧电，
                    // 停下来让用户看到状态比假装还在录要诚实。
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
            // 录音线程死掉不能把已经录到的东西一起带走
        } finally {
            runCatching { rec.stop() }; runCatching { rec.release() }
            val end = open.trueEndMs
            seal(s, open, seq, startMs, end)
            running.set(false)
        }
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
        val m4a = vault.segmentFile(s, truth.withState(Segment.State.SEALED))
        if (!Encoder.pcmToM4a(pcm, m4a)) return false
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
