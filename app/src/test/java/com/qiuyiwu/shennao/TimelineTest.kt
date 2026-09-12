package com.qiuyiwu.shennao

import com.qiuyiwu.shennao.record.SessionMeta
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 周带与「最近一次录音」那一句（V5 3.3 / 3.4）。 */
class TimelineTest {
    private val sh = TimeZone.getTimeZone("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        Calendar.getInstance(sh).apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    @Test fun `本周从周一起，七天`() {
        val days = Week.dayStarts(at(2026, 9, 16, 15, 0), sh)   // 周三
        assertEquals(7, days.size)
        assertEquals(at(2026, 9, 14), days.first())
        assertEquals(at(2026, 9, 20), days.last())
    }
    @Test fun `周日也算这一周的最后一天，不是下一周的第一天`() {
        assertEquals(at(2026, 9, 14), Week.dayStarts(at(2026, 9, 20, 23, 0), sh).first())
    }
    @Test fun `分组标题：今天、昨天、再往前是日期加星期`() {
        assertEquals("今天", Week.dayLabel("2026-09-12", "2026-09-12", sh))
        assertEquals("昨天", Week.dayLabel("2026-09-11", "2026-09-12", sh))
        assertEquals("9月5日 周六", Week.dayLabel("2026-09-05", "2026-09-12", sh))
    }

    @Test fun `服务端 UTC 时刻落到本地那一天——北京凌晨一点是前一天的 UTC`() {
        assertEquals("2026-09-05", Week.keyOfIso("2026-09-04T17:30:00Z", sh))
        assertNull(Week.keyOfIso("garbage", sh)); assertNull(Week.keyOfIso(null, sh))
    }

    private fun card(stage: Stage, at: String = "2026-09-05T06:00:00Z", title: String = "Q3 复盘会", ms: Long? = 3_120_000) =
        SessionCard("s", title, at, ms, stage, null, "t1")
    private fun local(done: Int, total: Int, recording: Int = 0) =
        LocalSession("d", SessionMeta("k", "手机录音", 1_757_000_000_000L), total, done, recording)

    @Test fun `还在手机上传的排最前——它是唯一可能丢的`() {
        val line = LatestLine.of(listOf(local(1, 3)), listOf(card(Stage.ANALYZED)))!!
        assertTrue(line, line.contains("还在手机上") && line.contains("33%"))
    }
    @Test fun `正在录就说正在录`() {
        assertTrue(LatestLine.of(listOf(local(0, 1, recording = 1)), emptyList())!!.endsWith("正在录"))
    }
    @Test fun `没有本地的就看深脑那边最新一场，说它到哪一站`() {
        val line = LatestLine.of(emptyList(), listOf(card(Stage.DELIVERED, at = "2026-09-01T01:00:00Z"), card(Stage.ANALYZED)))!!
        assertTrue(line, line.startsWith("Q3 复盘会") && line.endsWith("分析完了"))
    }
    @Test fun `太短没分析的说清楚，不说「分析中」`() {
        assertTrue(LatestLine.of(emptyList(), listOf(card(Stage.TRANSCRIBED, ms = 45_000)))!!.endsWith("没分析（太短）"))
    }
    @Test fun `什么都没有就不画`() { assertNull(LatestLine.of(emptyList(), emptyList())) }
}
