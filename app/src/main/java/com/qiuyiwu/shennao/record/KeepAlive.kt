package com.qiuyiwu.shennao.record

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/*
 * 手机录音在后台活不活得下来（spec 011）。
 *
 * 「会中不看屏幕」是这个 App 的第一原则，而国内 ROM 的默认策略是锁屏几分钟后把前台服务也杀掉。
 * 前台服务 + 常驻通知只是底线；真正决定生死的是两件事：
 *   1. 系统电池优化是否豁免了我们（标准 API，能查能申请）
 *   2. 厂商自己的「自启动 / 后台高耗电」开关（没有 API，只能告诉人去哪点）
 * 这里两件都做，但只说事实：查得到的说查到的，查不到的说「可能」。
 */
object KeepAlive {

    /** 系统电池优化是否已豁免。查不到（老系统、测试）当没豁免——宁可多提醒一次。 */
    fun isExempt(ctx: Context): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    /** 弹系统的「允许后台一直运行？」对话框。 */
    fun requestIntent(ctx: Context): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:" + ctx.packageName))

    /** 厂商的自启动 / 后台开关在哪。纯逻辑，JVM 可测。认不出来的厂商返回 null——不编路径。 */
    fun romHint(manufacturer: String? = Build.MANUFACTURER): String? {
        val m = manufacturer?.lowercase()?.trim() ?: return null
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                "小米：设置 → 应用设置 → 应用管理 → 深脑 → 打开「自启动」，省电策略选「无限制」。"
            m.contains("huawei") || m.contains("honor") ->
                "华为 / 荣耀：设置 → 应用 → 应用启动管理 → 深脑 → 关掉「自动管理」，三项手动都打开。"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                "OPPO / 一加 / realme：设置 → 电池 → 更多设置 → 深脑 → 允许后台运行。"
            m.contains("vivo") || m.contains("iqoo") ->
                "vivo / iQOO：i 管家 → 应用管理 → 权限管理 → 自启动，把深脑打开；后台高耗电里也允许。"
            m.contains("samsung") ->
                "三星：设置 → 电池 → 后台使用限制 → 「从不休眠的应用」里加上深脑。"
            m.contains("meizu") ->
                "魅族：手机管家 → 权限管理 → 后台管理 → 深脑 → 允许后台运行。"
            else -> null
        }
    }

    /** 「我的」页那张卡的两句话。 */
    fun summary(exempt: Boolean, hint: String?): Pair<String, String> = when {
        exempt && hint == null -> "系统已允许后台一直录" to "这台手机没有额外的厂商开关要设。"
        exempt -> "系统已允许后台一直录" to "厂商还有一道开关，没设过的话建议设一下：$hint"
        hint == null -> "还没允许后台一直录" to "锁屏几分钟后录音可能被系统停掉。点一下，系统会问你要不要允许。"
        else -> "还没允许后台一直录" to "锁屏几分钟后录音可能被系统停掉。先点这里允许，再看厂商开关：$hint"
    }
}
