package com.qiuyiwu.shennao.record

import android.content.Context
import com.qiuyiwu.shennao.Session
import com.qiuyiwu.shennao.UrlHttp
import java.io.File

/**
 * 把没传完的录音接着传。开 App 时走一遍，WorkManager 也走这一遍。
 *
 * 顺带把上次被杀时留下的半截 PCM 补封了——它们的文件名写的是「计划长度」，
 * 不补封就永远不是可上传的状态。
 */
object Resume {

    /**
     * 全进程一把锁。
     *
     * 有三个地方会催上传：录音服务的 15 秒轮询、封段时的即时催、
     * 以及 WorkManager 的后台任务。两个同时跑会去传同一段——
     * 第二个虽然会被服务端的幂等挡住（409 CHUNK_ALREADY_VERIFIED），
     * 但那是白跑一次网络往返，而且两边都在改同一批文件名，
     * 没必要让「能不能同时改」这件事去赌文件系统的语义。
     *
     * 必须是**同一个对象**。第一版这里是私有的，而录音服务那边写的是
     * `synchronized(Resume)` —— 那是两个不同的监视器，锁了个寂寞。
     */
    val lock = Any()

    fun kick(ctx: Context): Map<String, DrainResult> = synchronized(lock) {
        val vault = FileVault(File(ctx.filesDir, "recordings"))
        if (vault.sessions().isEmpty()) return@synchronized emptyMap()
        // 正在录音时不碰孤儿回收：它会去动当前这场还开着的文件。
        // 但推送照常——录音期间也要边录边传。
        if (!RecordingService.recording) Recorder(vault) {}.recoverOrphans()?.let { OrphanNotice.record(ctx, it) }
        Uploader(UrlHttp(), vault, com.qiuyiwu.shennao.BuildConfig.API_BASE) { force ->
            Session.authFor(ctx, force)
        }.drainAll()
    }

    /**
     * WorkManager 要不要再来一次。
     *
     * 只数没传的段是不够的：段全传完、stop / finalize 撞上 503 时，段数是 0，
     * Worker 就 success 了，收尾要等用户下次打开 App（2026-09-12 审计 A2）。
     * 所以还要看这一轮的结果：还在推进（Progress）或可重试的失败，都得再来。纯逻辑，JVM 可测。
     */
    fun shouldRetry(results: Map<String, DrainResult>, pendingSegments: Int): Boolean =
        pendingSegments > 0 || results.values.any {
            it is DrainResult.Progress || (it is DrainResult.Failed && it.retryable)
        }

    /** 还有多少段没送到深脑。给界面显示、也给 WorkManager 判断要不要再来一次。 */
    fun pending(ctx: Context): Int {
        val vault = FileVault(File(ctx.filesDir, "recordings"))
        return vault.sessions().sumOf { s ->
            // 只算已封段：正在录的 pcm 不是「待传」，算进去会让 UploadWorker 在整场录音期间一直 retry/退避（012 P2-3）
            vault.segments(s).count { it.state == Segment.State.SEALED }
        }
    }
}
