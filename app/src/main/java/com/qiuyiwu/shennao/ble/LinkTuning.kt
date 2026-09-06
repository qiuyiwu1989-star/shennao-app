package com.qiuyiwu.shennao.ble

import android.content.Context

/*
 * 传输实验：BLE 上仅有的三个能拧的旋钮。
 *
 * 实测带宽 27 KB/s，一小时录音要传四分半。慢不在 App 逻辑——下载是设备连续推、
 * 不是每块等应答。慢在链路参数，而这三项**从来没在真机上试过**：
 *
 *   · 高优先级连接：连接间隔从默认 30~50ms 压到 7.5ms，理论上最能提速，也最容易让固件掉线
 *   · 大 MTU：185 → 517，每包多带 330 字节，收益约一两成
 *   · 2M PHY：物理层速率翻倍，要固件那颗芯片支持
 *
 * 所以做成开关而不是直接改：真机上逐项打开、看测出来的 KB/s、掉线就关掉。
 * **默认全关**——默认值必须是已知能用的那套，实验永远是选择加入的。
 */
object LinkTuning {
    private const val PREF = "link_tuning"

    data class Knobs(
        /** 高优先级连接间隔（7.5ms） */
        val fastInterval: Boolean = false,
        /** MTU 要 517 而不是 185 */
        val bigMtu: Boolean = false,
        /** 2M PHY */
        val phy2m: Boolean = false,
    ) {
        val mtu: Int get() = if (bigMtu) 517 else 185
        val anyOn: Boolean get() = fastInterval || bigMtu || phy2m
    }

    fun load(ctx: Context): Knobs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).let {
        Knobs(it.getBoolean("fast", false), it.getBoolean("mtu", false), it.getBoolean("phy", false))
    }

    fun save(ctx: Context, k: Knobs) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("fast", k.fastInterval).putBoolean("mtu", k.bigMtu).putBoolean("phy", k.phy2m).apply()
    }

    /** 一次传输的实测速度。纯计算，可测。 */
    fun kbps(bytes: Long, elapsedMs: Long): Double? =
        if (bytes <= 0 || elapsedMs <= 0) null else bytes * 1000.0 / elapsedMs / 1024.0

    /** 说给人听的那一行。没测出来就说没测出来，不编数字。 */
    fun speedLine(kbps: Double?): String =
        if (kbps == null) "还没测到速度——同步一份录音就有了" else "上次实测 %.1f KB/s".format(kbps)

    /** 开关那一行的小字：说清楚它改的是什么、风险在哪。 */
    fun hint(k: Knobs): String = when {
        !k.anyOn -> "三项都关着，用的是一直以来的参数。"
        else -> "开了实验参数。传着传着掉线就把刚开的那项关掉——固件不一定吃得下。"
    }
}
