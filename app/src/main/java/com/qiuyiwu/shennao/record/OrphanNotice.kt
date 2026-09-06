package com.qiuyiwu.shennao.record

import android.content.Context

/**
 * 录音被系统杀掉之后，下次打开要说一句（012 P1-7）。
 * recoverOrphans 把半截段补封了是对的，但以前一声不吭——用户以为那场录完了，其实录到一半就停了。
 * 只记最近一次的墙上时刻；「记录」页显示一次，点「知道了」就清。
 */
object OrphanNotice {
    private const val PREF = "orphan_notice"
    fun record(ctx: Context, endedAtEpochMs: Long) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong("ended", endedAtEpochMs).apply()
    }
    fun peek(ctx: Context): Long? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("ended", 0L).takeIf { it > 0 }
    fun clear(ctx: Context) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply() }
    fun line(endedAtEpochMs: Long): String {
        val t = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(endedAtEpochMs))
        return "上次录音在 $t 被系统停掉了。已录到的都保住了，会接着传。想让它以后不被停，到「我的」里设一下后台。"
    }
}
