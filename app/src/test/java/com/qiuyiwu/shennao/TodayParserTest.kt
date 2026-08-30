package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/**
 * 解析层的判据。
 *
 * 重点不在「能解析正常数据」，在**畸形数据不能让整屏白**：
 * 手机上一次解析崩溃就是整页挂掉，而服务端多加/少给一个字段是常态。
 */
class TodayParserTest {

    private val full = """
    {"counts":{"overdue":3,"total":7,"awaitingSpeaker":2},
     "notReady":false,"failed":false,
     "commitments":[
       {"id":"c1","speakerName":"郭校长","statement":"下周给方案",
        "quote":"我下周把方案发你","saidDate":"8月23日","context":"AI合作探讨",
        "dueDate":"8月30日","overdueDays":2,"status":"open",
        "transcriptId":"t1"}]}
    """.trimIndent()

    @Test fun `正常数据全字段解出来`() {
        val t = TodayParser.parse(full)
        assertEquals(3, t.counts.overdue)
        assertEquals(1, t.commitments.size)
        val c = t.commitments[0]
        assertEquals("郭校长", c.speakerName)
        assertEquals(2, c.overdueDays)
        assertEquals("t1", c.transcriptId)   // 往回走的路
    }

    @Test fun `overdueDays 为 null 表示待确认，不能变成 0`() {
        val t = TodayParser.parse(
            """{"commitments":[{"id":"c1","overdueDays":null}]}"""
        )
        assertNull(t.commitments[0].overdueDays)
    }

    @Test fun `空字符串的可选字段归一成 null，界面才好判断`() {
        val t = TodayParser.parse(
            """{"commitments":[{"id":"c1","context":"","dueDate":"","transcriptId":""}]}"""
        )
        val c = t.commitments[0]
        assertNull(c.context); assertNull(c.dueDate); assertNull(c.transcriptId)
    }

    @Test fun `没有 id 的行直接丢掉，不要半条数据`() {
        val t = TodayParser.parse("""{"commitments":[{"speakerName":"某人"},{"id":"c2"}]}""")
        assertEquals(1, t.commitments.size)
        assertEquals("c2", t.commitments[0].id)
    }

    @Test fun `字段全缺也不抛异常——宁可少显示，不能整页挂掉`() {
        val t = TodayParser.parse("{}")
        assertEquals(0, t.counts.total)
        assertTrue(t.commitments.isEmpty())
        assertFalse(t.notReady)
    }

    @Test fun `notReady 要透出来——「没有承诺」和「这块坏了」不是一回事`() {
        val t = TodayParser.parse("""{"notReady":true,"commitments":[]}""")
        assertTrue(t.notReady)
    }

    // ---- 通知判据 ----

    @Test fun `有逾期才推通知`() {
        assertEquals("3 条承诺过期", TodayParser.notification(TodayParser.parse(full)))
    }

    @Test fun `预测到期也要提醒，和承诺一起说`() {
        val t = TodayParser.parse("""
            {"counts":{"overdue":2},"predictions":[{"id":"p1"},{"id":"p2"}]}
        """.trimIndent())
        assertEquals("2 条承诺过期，2 条预测该给说法", TodayParser.notification(t))
    }

    // 什么都往通知里塞，用户很快会把通知整个关掉——那时真正要紧的也送不到了。
    @Test fun `洞察不进通知——它没有时间压力`() {
        val t = TodayParser.parse("""
            {"counts":{"overdue":0},"insights":[{"id":"a1"},{"id":"a2"}]}
        """.trimIndent())
        assertNull(TodayParser.notification(t))
    }

    @Test fun `洞察与预测都能解出来，含往回走的路`() {
        val t = TodayParser.parse("""
          {"insights":[{"id":"a1","statement":"设备只录音不互动","atomType":"decision",
                        "quote":"我们决定设备只录音","epistemic":"attested",
                        "subject":"设备","transcriptId":"t9"}],
           "predictions":[{"id":"p1","statement":"三个月内会有人问",
                           "observableSignal":"客户主动提","dueAt":"9月1日","overdueDays":3}]}
        """.trimIndent())
        assertEquals("attested", t.insights[0].epistemic)
        assertEquals("t9", t.insights[0].transcriptId)      // 路
        assertEquals("客户主动提", t.predictions[0].observableSignal)
        assertEquals(3, t.predictions[0].overdueDays)
    }

    @Test fun `只有在途没有逾期，不推——不该半夜吵醒人`() {
        val t = TodayParser.parse("""{"counts":{"overdue":0,"total":9}}""")
        assertNull(TodayParser.notification(t))
    }

    @Test fun `系统自己有毛病时不推通知`() {
        val broken = TodayParser.parse("""{"counts":{"overdue":5},"failed":true}""")
        assertNull(TodayParser.notification(broken))
        val notReady = TodayParser.parse("""{"counts":{"overdue":5},"notReady":true}""")
        assertNull(TodayParser.notification(notReady))
    }
}
