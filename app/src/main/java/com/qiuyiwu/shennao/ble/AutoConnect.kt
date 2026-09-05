package com.qiuyiwu.shennao.ble

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/*
 * 靠近即同步。
 *
 * 「会中不看屏幕」在硬件侧的兑现：连过的卡，App 打开时后台悄悄找它，找到就连、连上就同步，
 * 不用进灵魂卡页、不用点扫描。做不到「App 没打开也同步」——安卓不给后台常驻扫描，
 * 而且那需要一个永远在的前台通知；打开 App 这一下就是我们的触发点。
 *
 * 只做「记住的卡」：不认识的设备一律不碰，那是灵魂卡页手动的事。
 * 每 10 分钟最多试一次，找 12 秒找不到就收——扫描要起前台服务，不能让通知常驻。
 */
object AutoConnect {
    const val WINDOW_MS = 12_000L
    const val MIN_INTERVAL_MS = 10 * 60_000L
    private const val PREF = "autoconnect"
    private const val KEY_LAST = "lastTry"

    /** 在找到的设备里挑一张记住的卡：广播了 0xAE20 的优先，其次信号强的。纯逻辑，可测。 */
    fun pick(known: Set<String>, found: List<BleDevice>): String? =
        found.filter { it.id in known }
            .sortedWith(compareByDescending<BleDevice> { it.advertisesOurService }.thenByDescending { it.rssi })
            .firstOrNull()?.id

    /** 该不该试：有记住的卡、有权限、服务没在忙、距上次够久。纯逻辑，可测。 */
    fun shouldTry(knownEmpty: Boolean, granted: Boolean, serviceRunning: Boolean, lastTryMs: Long, now: Long): Boolean =
        !knownEmpty && granted && !serviceRunning && now - lastTryMs >= MIN_INTERVAL_MS

    fun granted(ctx: Context): Boolean =
        BlePermissions.required().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * 试一次。在主线程的协程里调；自己会轮询服务状态，不阻塞界面。
     * 返回连上了哪张卡的地址，没找到返回 null。
     */
    suspend fun tryOnce(ctx: Context, now: () -> Long = System::currentTimeMillis): String? {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val known = CardNames(ctx).known().map { it.first }.toSet()
        if (!shouldTry(known.isEmpty(), granted(ctx), BleImportService.running, prefs.getLong(KEY_LAST, 0L), now())) return null
        prefs.edit().putLong(KEY_LAST, now()).apply()

        BleImportService.scan(ctx)
        val deadline = now() + WINDOW_MS
        while (now() < deadline) {
            delay(500)
            pick(known, BleImportService.devices)?.let { addr ->
                BleImportService.connect(ctx, addr)
                return addr
            }
            // 用户自己进了灵魂卡页在操作，或者服务已经不在扫了：让开
            if (BleImportService.conn != BleState.SCANNING) return null
        }
        // 找不到就收掉，别让「正在从灵魂卡导入」的通知一直挂着
        if (BleImportService.conn == BleState.SCANNING && BleImportService.state is ImportState.Idle) BleImportService.stop(ctx)
        return null
    }
}
