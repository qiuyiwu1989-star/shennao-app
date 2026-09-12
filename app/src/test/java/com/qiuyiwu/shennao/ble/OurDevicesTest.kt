package com.qiuyiwu.shennao.ble

import org.junit.Assert.*
import org.junit.Test

/** 附近设备列表只留我们的（2026-09-12）。 */
class OurDevicesTest {
    private fun ours(name: String, adv: Boolean = false, known: Set<String> = emptySet(), addr: String = "AA") =
        OurDevices.looksLikeOurs(name, adv, known, addr)

    @Test fun `名字像灵魂卡的算我们的，大小写和连字符都认`() {
        assertTrue(ours("CB08-1234")); assertTrue(ours("cb08_x")); assertTrue(ours(" CB-08 note")); assertTrue(ours("CB08"))
    }
    @Test fun `广播里带我们的服务就算，哪怕名字对不上`() { assertTrue(ours("Note-Rec", adv = true)) }
    @Test fun `连过的卡改了名也认得`() { assertTrue(ours("我的卡", known = setOf("AA"), addr = "AA")) }
    @Test fun `别人的耳机、手表、电视不算`() {
        assertFalse(ours("AirPods Pro")); assertFalse(ours("Mi Band 8")); assertFalse(ours("TV-CB0")); assertFalse(ours(""))
    }
}
