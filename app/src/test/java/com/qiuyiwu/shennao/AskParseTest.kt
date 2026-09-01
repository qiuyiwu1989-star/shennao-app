package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/**
 * SSE 解析。这条流的形状由我们自己的服务端定，但**认不出的行必须返回 null**——
 * 猜错会把一条中间状态显示成答案正文。
 */
class AskParseTest {

    @Test fun `正文一个字一个字来`() {
        val e = Ask.parseLine("""data: {"type":"token","delta":"陈总"}""")
        assertEquals("陈总", (e as Ask.Event.Token).delta)
    }

    @Test fun `依据不够是回答，不是失败`() {
        // 「我不知道」是一个正当的回答。当成 Failed 显示的话，
        // 用户会以为系统坏了，而不是「库里真的没有」。
        assertTrue(Ask.parseLine("""data: {"type":"insufficient"}""") is Ask.Event.Insufficient)
    }

    @Test fun `智能体在翻什么，要翻成人话`() {
        val e = Ask.parseLine("""data: {"type":"tool_call","name":"search_atoms"}""")
        // search_atoms 对用户没有意义
        assertEquals("在翻判断", (e as Ask.Event.Step).what)
    }

    @Test fun `认不出的一律返回 null，不猜`() {
        assertNull(Ask.parseLine("""data: {"type":"未来才有的事件"}"""))
        assertNull(Ask.parseLine(": keep-alive"))
        assertNull(Ask.parseLine(""))
        assertNull(Ask.parseLine("data: 这不是 json"))
    }

    @Test fun `错误要带上原因`() {
        val e = Ask.parseLine("""data: {"type":"error","message":"模型超时"}""")
        assertEquals("模型超时", (e as Ask.Event.Failed).message)
        // 服务端没给原因时也不能是空白
        val e2 = Ask.parseLine("""data: {"type":"error"}""")
        assertTrue((e2 as Ask.Event.Failed).message.isNotBlank())
    }
}
