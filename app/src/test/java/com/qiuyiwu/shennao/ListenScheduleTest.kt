package com.qiuyiwu.shennao

import com.qiuyiwu.shennao.record.ListenSchedule
import com.qiuyiwu.shennao.record.ListenSchedule.Config
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 定时聆听：下一个事件算对了没。差一天或差一个时区，人就会在半夜被叫。 */
class ListenScheduleTest {
    private val sh = TimeZone.getTimeZone("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance(sh).apply {
        clear(); set(y, mo - 1, d, h, mi, 0)
    }.timeInMillis

    @Test fun `工作日早上八点，下一个是今天九点开始`() {
        val cfg = Config(enabled = true)
        val (t, start) = ListenSchedule.nextEvent(at(2026, 9, 14, 8, 0), sh, cfg)!!   // 周一
        assertTrue(start); assertEquals(at(2026, 9, 14, 9, 0), t)
    }

    @Test fun `工作日中午，下一个是今天十八点结束`() {
        val (t, start) = ListenSchedule.nextEvent(at(2026, 9, 14, 12, 0), sh, Config(enabled = true))!!
        assertFalse(start); assertEquals(at(2026, 9, 14, 18, 0), t)
    }

    @Test fun `周五晚上，下一个是下周一早上开始，周末跳过`() {
        val (t, start) = ListenSchedule.nextEvent(at(2026, 9, 18, 20, 0), sh, Config(enabled = true))!!   // 周五
        assertTrue(start); assertEquals(at(2026, 9, 21, 9, 0), t)
    }

    @Test fun `没开、没选天、结束早于开始都没有事件`() {
        assertNull(ListenSchedule.nextEvent(at(2026, 9, 14, 8, 0), sh, Config(enabled = false)))
        assertNull(ListenSchedule.nextEvent(at(2026, 9, 14, 8, 0), sh, Config(enabled = true, days = emptySet())))
        assertNull(ListenSchedule.nextEvent(at(2026, 9, 14, 8, 0), sh, Config(enabled = true, startMin = 600, endMin = 540)))
    }

    @Test fun `时段那一行`() {
        assertEquals("工作日 09:00–18:00", ListenSchedule.summary(Config()))
        assertEquals("每天 09:00–18:00", ListenSchedule.summary(Config(days = (1..7).toSet())))
        assertEquals("周一三五 10:30–12:00", ListenSchedule.summary(Config(days = setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY), startMin = 630, endMin = 720)))
    }

    @Test fun `顶栏那两句`() {
        assertEquals("正在录" to "已录 12 分钟", LiveBarText.of(true, false, 12 * 60_000L))
        assertEquals("在听" to "有人说话时才录", LiveBarText.of(false, true, 0))
        assertEquals("点一下开始录" to "", LiveBarText.of(false, false, 0))
    }
}
