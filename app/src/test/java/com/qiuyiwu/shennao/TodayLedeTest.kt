package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/*
 * 「今天」页上那两条判据。
 *
 * 它们会被反复改（哪件算急、哪一档要折叠），所以拎出 composable 单独测——
 * 埋在界面里就只能装到手机上才知道对不对，而这一屏是兑现面，改错的代价最高。
 */
class TodayLedeTest {

    private fun today(
        overdue: Int = 0,
        awaiting: Int = 0,
        predictions: Int = 0,
        insights: Int = 0,
    ) = Today(
        counts = TodayCounts(overdue = overdue, total = overdue, awaitingSpeaker = awaiting),
        commitments = emptyList(),
        predictions = List(predictions) {
            Prediction("p$it", "会怎样", "看到什么算数", null, null, null)
        },
        insights = List(insights) {
            Insight("i$it", "一条判断", "signal", "", "attested", null, null)
        },
        notReady = false,
        failed = false,
    )

    @Test fun `没有急事就不出横幅`() {
        assertNull("一个永远在的横幅等于没有横幅", urgentLede(today()))
        assertNull("只有新判断不算急——它没有时间压力", urgentLede(today(insights = 5)))
    }

    @Test fun `逾期排第一`() {
        val s = urgentLede(today(overdue = 2, predictions = 3, awaiting = 9))
        assertEquals("有 2 条承诺过期了", s)
    }

    @Test fun `没有逾期时轮到到期的预测`() {
        assertEquals("有 3 条预测到期，等你给个说法", urgentLede(today(predictions = 3, awaiting = 9)))
    }

    @Test fun `最后才是认人`() {
        assertEquals("有 9 句还不知道是谁说的", urgentLede(today(awaiting = 9)))
    }

    /** 两件并列就又变回了要读的一段话。**只说一件。** */
    @Test fun `只说一件事`() {
        val s = urgentLede(today(overdue = 1, predictions = 1, awaiting = 1))!!
        assertFalse("不该把几件事串起来", s.contains("，") && s.contains("预测"))
    }

    @Test fun `只折叠猜想`() {
        assertTrue("猜想默认折叠", foldByDefault("conjecture"))
        assertFalse("亲证有原话托底，展开是帮人核对", foldByDefault("attested"))
        assertFalse("推断也照常展开", foldByDefault("inferred"))
        assertFalse("认不出来的等级不折叠——宁可多显示，也别把东西藏了", foldByDefault(""))
    }
}

/*
 * 「记录」页顶上那一行灵魂卡状态。措辞会被反复改，所以判据单独测。
 */
class CardStatusTest {
    private val idle = com.qiuyiwu.shennao.ble.ImportState.Idle
    private fun of(
        conn: com.qiuyiwu.shennao.ble.BleState,
        done: Int = 0, total: Int = 0, err: String? = null,
        state: com.qiuyiwu.shennao.ble.ImportState = idle,
    ) = CardStatus.of(conn, state, done, total, err)

    @Test fun `没连上不是故障，是常态`() {
        val c = of(com.qiuyiwu.shennao.ble.BleState.IDLE)
        assertEquals("灵魂卡 · 未连接", c.line)
        assertFalse("卡不在身边是常态，标成红的会天天吓人", c.attention)
        assertFalse(c.busy)
    }

    @Test fun `同步中要说可以走开，不给百分比`() {
        val c = of(com.qiuyiwu.shennao.ble.BleState.READY, done = 1, total = 3)
        assertTrue(c.line.contains("2/3"))
        assertTrue("人要的是「我能不能走开」", c.line.contains("可以直接切走"))
        assertTrue(c.busy)
        assertFalse(c.line.contains("%"))
    }

    @Test fun `连上且没事做就说没事`() {
        assertEquals("灵魂卡 · 已连接，没有待同步的", of(com.qiuyiwu.shennao.ble.BleState.READY).line)
    }

    @Test fun `真失败才用警示色，并且带原因`() {
        val c = of(com.qiuyiwu.shennao.ble.BleState.FAILED, err = "蓝牙没开")
        assertTrue(c.attention)
        assertTrue(c.line.contains("蓝牙没开"))
    }
}
