package com.qiuyiwu.shennao.record

import android.content.Context
import com.qiuyiwu.shennao.Session
import com.qiuyiwu.shennao.UrlHttp
import java.io.File

/**
 * 开 App 时把上次没传完的录音接着传。
 *
 * 为什么必须有这一条：录音服务在传完之前可能被系统杀掉（后台限制、内存紧张、
 * 用户手动划掉）。没有这一步的话，那些段会一直躺在手机上，而界面上写着
 * 「下次打开接着传」——那句话就成了假的。
 *
 * 顺带把上次被杀时留下的半截 PCM 补封了。它们的文件名写的是「计划长度」，
 * 不补封就永远不是可上传的状态。
 */
object Resume {
    fun kick(ctx: Context) {
        val vault = FileVault(File(ctx.filesDir, "recordings"))
        if (vault.sessions().isEmpty()) return
        // 正在录音时不碰：孤儿回收会去动当前这场的文件
        if (RecordingService.recording) return
        Recorder(vault) {}.recoverOrphans()
        Uploader(UrlHttp(), vault, com.qiuyiwu.shennao.BuildConfig.API_BASE) { force ->
            Session.authFor(ctx, force)
        }.drainAll()
    }

    /** 还有多少段没送到深脑。给界面显示用。 */
    fun pending(ctx: Context): Int {
        val vault = FileVault(File(ctx.filesDir, "recordings"))
        return vault.sessions().sumOf { s ->
            vault.segments(s).count { it.state != Segment.State.UPLOADED }
        }
    }
}
