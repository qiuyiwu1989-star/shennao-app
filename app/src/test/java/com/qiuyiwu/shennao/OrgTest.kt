package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/** 多组织：列表怎么解、切换改了什么、录音钉在录制时的组织。 */
class OrgTest {
    private class Mem(var c: Credentials?) : CredentialStore {
        override fun load() = c
        override fun save(c: Credentials) { this.c = c }
        override fun clear() { c = null }
    }

    @Test fun `嵌出组织名，个人空间认得出`() {
        val body = """[
          {"org_id":"o1","role":"owner","created_at":"2026-01-01","organizations":{"name":"个人空间","plan":"personal"}},
          {"org_id":"o2","role":"member","created_at":"2026-02-01","organizations":{"name":"造物云","plan":"team"}}
        ]"""
        val orgs = OrgParser.parse(body)!!
        assertEquals(listOf("个人空间", "造物云"), orgs.map { it.name })
        assertTrue(orgs[0].personal); assertFalse(orgs[1].personal)
        assertEquals("member", orgs[1].role)
    }

    @Test fun `嵌不出来就用 id 前八位顶着，切换照样能用`() {
        val orgs = OrgParser.parse("""[{"org_id":"0f20b203-756d-4bb0","role":"owner","created_at":"x"}]""")!!
        assertEquals("0f20b203", orgs.single().name)
        assertNull(OrgParser.parse("not json"))
    }

    @Test fun `切换只改 orgId，刷新钥匙和邮箱不动`() {
        val store = Mem(Credentials("rt", "o1", "a@b.c"))
        val c = DeepBrainClient(object : Http {
            override fun request(method: String, url: String, headers: Map<String, String>, body: String?) = HttpResponse(0, "")
            override fun requestBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray) = HttpResponse(0, "")
        }, store, "https://api.test", "https://sb.test", "k")
        assertTrue(c.switchOrg("o2"))
        assertEquals(Credentials("rt", "o2", "a@b.c"), store.c)
        assertEquals("o2", c.orgId())
    }
}
