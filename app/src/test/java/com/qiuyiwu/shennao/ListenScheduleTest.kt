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

    // ── 边界（V5 2.5）──
    @Test fun `错过了提醒——已经过了结束时间，下一个是明天开始，不是补发今天的`() {
        val (t, start) = ListenSchedule.nextEvent(at(2026, 9, 14, 19, 0), sh, Config(enabled = true))!!
        assertTrue(start); assertEquals(at(2026, 9, 15, 9, 0), t)
    }
    @Test fun `正好在开始那一刻重排（重启或通知刚发）——下一个是结束，不会再催一次开始`() {
        val (t, start) = ListenSchedule.nextEvent(at(2026, 9, 14, 9, 0), sh, Config(enabled = true))!!
        assertFalse(start); assertEquals(at(2026, 9, 14, 18, 0), t)
    }
    @Test fun `换了时区——按新时区的墙上时间算，不是按旧的绝对时刻`() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val now = at(2026, 9, 14, 8, 0)   // 上海周一早八点 = 纽约周日晚八点
        val (t, start) = ListenSchedule.nextEvent(now, ny, Config(enabled = true))!!
        assertTrue(start)
        val c = Calendar.getInstance(ny).apply { timeInMillis = t }
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK)); assertEquals(9, c.get(Calendar.HOUR_OF_DAY))
        assertTrue("纽约的周一九点比上海的周一九点晚", t > at(2026, 9, 14, 9, 0))
    }
    @Test fun `只选周末——工作日整周都没有事件，直到周六`() {
        val cfg = Config(enabled = true, days = setOf(Calendar.SATURDAY, Calendar.SUNDAY))
        val (t, start) = ListenSchedule.nextEvent(at(2026, 9, 14, 12, 0), sh, cfg)!!
        assertTrue(start); assertEquals(at(2026, 9, 19, 9, 0), t)
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
