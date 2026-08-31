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

    fun kick(ctx: Context) = synchronized(lock) {
        val vault = FileVault(File(ctx.filesDir, "recordings"))
        if (vault.sessions().isEmpty()) return@synchronized
        // 正在录音时不碰孤儿回收：它会去动当前这场还开着的文件。
        // 但推送照常——录音期间也要边录边传。
        if (!RecordingService.recording) Recorder(vault) {}.recoverOrphans()
        Uploader(UrlHttp(), vault, com.qiuyiwu.shennao.BuildConfig.API_BASE) { force ->
            Session.authFor(ctx, force)
        }.drainAll()
    }

    /** 还有多少段没送到深脑。给界面显示、也给 WorkManager 判断要不要再来一次。 */
    fun pending(ctx: Context): Int {
        val vault = FileVault(File(ctx.filesDir, "recordings"))
        return vault.sessions().sumOf { s ->
            vault.segments(s).count { it.state != Segment.State.UPLOADED }
        }
    }
}
