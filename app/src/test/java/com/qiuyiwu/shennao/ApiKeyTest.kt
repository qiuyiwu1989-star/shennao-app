package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/** 接入 AI 的 key：应答解析与那段能直接粘的配置（V5 用户反馈 7）。 */
class ApiKeyTest {
    @Test fun `列表：撤销过的标出来，没用过的说没用过`() {
        val ks = ApiKeyParser.parseList("""{"keys":[{"id":"k1","name":"Claude","prefix":"lj_live_a1b2","last_used_at":"2026-09-11T02:00:00Z","created_at":"2026-09-01T00:00:00Z","revoked_at":null},{"id":"k2","name":"旧","prefix":"lj_live_zz","last_used_at":null,"created_at":"x","revoked_at":"2026-08-20T00:00:00Z"}]}""")
        assertEquals(2, ks.size)
        assertFalse(ks[0].revoked); assertTrue(ks[1].revoked)
        assertEquals("lj_live_a1b2… · 上次用 2026-09-11", Agents.keyLine(ks[0]))
        assertEquals("lj_live_zz… · 还没用过", Agents.keyLine(ks[1]))
    }
    @Test fun `建好的那把带 secret，只在这一次`() {
        val k = ApiKeyParser.parseCreated("""{"id":"k3","name":"新的","prefix":"lj_live_n3w0","secret":"lj_live_n3w0xxx"}""")!!
        assertEquals("lj_live_n3w0xxx", k.secret)
        assertNull(ApiKeyParser.parseCreated("not json"))
    }
    @Test fun `MCP 配置是合法 JSON，带地址和 Bearer`() {
        val j = org.json.JSONObject(Agents.mcpConfig("https://x.test", "lj_live_abc"))
        val srv = j.getJSONObject("mcpServers").getJSONObject("shennao")
        assertEquals("https://x.test/api/mcp", srv.getString("url"))
        assertEquals("Bearer lj_live_abc", srv.getJSONObject("headers").getString("Authorization"))
    }
}
