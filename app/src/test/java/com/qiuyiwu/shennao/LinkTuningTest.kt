package com.qiuyiwu.shennao

import com.qiuyiwu.shennao.ble.LinkTuning
import org.junit.Assert.*
import org.junit.Test

/**
 * 传输实验的三个旋钮。默认必须全关——默认值要是已知能用的那套，实验永远是选择加入的。
 */
class LinkTuningTest {
    @Test fun `默认全关，MTU 是一直以来的 185`() {
        val k = LinkTuning.Knobs()
        assertFalse(k.fastInterval); assertFalse(k.bigMtu); assertFalse(k.phy2m)
        assertFalse(k.anyOn)
        assertEquals(185, k.mtu)
        assertEquals(517, k.copy(bigMtu = true).mtu)
        assertTrue(k.copy(phy2m = true).anyOn)
    }

    @Test fun `速度：没数据不编，有数据按 KB per s 算`() {
        assertNull(LinkTuning.kbps(0, 1000))
        assertNull("时间为 0 说明没测到，不能除", LinkTuning.kbps(1024, 0))
        assertEquals(1.0, LinkTuning.kbps(1024, 1000)!!, 0.001)
        assertEquals(27.0, LinkTuning.kbps(27 * 1024L * 10, 10_000)!!, 0.01)
    }

    @Test fun `没测到就说没测到`() {
        assertTrue(LinkTuning.speedLine(null).contains("还没测到"))
        assertTrue(LinkTuning.speedLine(27.4).contains("27.4"))
    }

    @Test fun `开了实验参数要提醒掉线怎么办`() {
        assertTrue(LinkTuning.hint(LinkTuning.Knobs()).contains("一直以来"))
        assertTrue(LinkTuning.hint(LinkTuning.Knobs(fastInterval = true)).contains("掉线"))
    }
}
