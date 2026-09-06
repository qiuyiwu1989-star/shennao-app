package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/** 012 P0-9：解析失败不许伪装成「没有内容」。 */
class StrictParseTest {
    @Test fun `sessions 根节点不对就抛，正常的照常解`() {
        assertThrows(Exception::class.java) { SessionsParser.parse("""{"nope":1}""") }
        assertThrows(Exception::class.java) { SessionsParser.parse("not json") }
        assertEquals(0, SessionsParser.parse("""{"sessions":[]}""").size)
        assertEquals(1, SessionsParser.parse("""{"sessions":[{"sessionId":"a","title":"x","stage":"analyzed"}]}""").size)
    }
    @Test fun `search 同理`() {
        assertThrows(Exception::class.java) { SearchParser.parse("{}") }
        assertEquals(0, SearchParser.parse("""{"hits":[]}""").size)
    }
}
