package com.qiuyiwu.shennao

import com.qiuyiwu.shennao.ble.AutoConnect
import com.qiuyiwu.shennao.ble.BleDevice
import org.junit.Assert.*
import org.junit.Test

/** 靠近即同步的两条判据：挑哪张卡、该不该试。 */
class AutoConnectTest {
    private fun d(id: String, rssi: Int, adv: Boolean = false) = BleDevice(id, "灵魂卡", rssi, adv)

    @Test fun `只挑记住的卡；广播了服务的优先，其次信号强`() {
        val known = setOf("A", "B")
        assertNull("不认识的一律不碰", AutoConnect.pick(known, listOf(d("Z", -30, true))))
        assertEquals("B", AutoConnect.pick(known, listOf(d("A", -40), d("B", -80, adv = true))))
        assertEquals("A", AutoConnect.pick(known, listOf(d("A", -40), d("B", -80))))
        assertNull(AutoConnect.pick(emptySet(), listOf(d("A", -40))))
    }

    @Test fun `该不该试：有卡、有权限、服务闲着、隔够 10 分钟`() {
        val t = 1_000_000L
        assertTrue(AutoConnect.shouldTry(knownEmpty = false, granted = true, serviceRunning = false, lastTryMs = 0, now = t))
        assertFalse("没记住过卡就不扫——扫描要起前台通知", AutoConnect.shouldTry(true, true, false, 0, t))
        assertFalse("没权限不试，更不弹权限框", AutoConnect.shouldTry(false, false, false, 0, t))
        assertFalse("服务在忙（可能正在导入）就让开", AutoConnect.shouldTry(false, true, true, 0, t))
        assertFalse("刚试过", AutoConnect.shouldTry(false, true, false, t - 60_000, t))
        assertTrue(AutoConnect.shouldTry(false, true, false, t - AutoConnect.MIN_INTERVAL_MS, t))
    }
}
