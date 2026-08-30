package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/*
 * 客户端判断的测试。全部脱离网络——重点不是「能发请求」，
 * 是「拿到各种应答时该怎么办」，而那才是会出错的地方。
 */

private class FakeHttp(
    val handler: (method: String, url: String, headers: Map<String, String>, body: String?) -> HttpResponse,
) : Http {
    val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        calls += Triple(method, url, headers)
        return handler(method, url, headers, body)
    }
}

private class MemStore(var c: Credentials? = null) : CredentialStore {
    override fun load() = c
    override fun save(x: Credentials) { c = x }
    override fun clear() { c = null }
}

private fun client(http: Http, store: CredentialStore) =
    DeepBrainClient(http, store, "https://api.test", "https://sb.test", "anon-key")

class ClientTest {

    private val okToday = """{"counts":{"overdue":2,"total":5,"awaitingSpeaker":0},"commitments":[]}"""

    @Test fun `没有凭证直接算未登录，不发请求`() {
        val http = FakeHttp { _, _, _, _ -> error("不该发请求") }
        val r = client(http, MemStore()).today()
        assertTrue(r is ApiResult.Unauthorized)
        assertEquals(0, http.calls.size)
    }

    @Test fun `正常路径：先续 token 再取数`() {
        val http = FakeHttp { _, url, _, _ ->
            when {
                url.contains("grant_type=refresh_token") ->
                    HttpResponse(200, """{"access_token":"at1"}""")
                url.endsWith("/api/mobile/today") -> HttpResponse(200, okToday)
                else -> HttpResponse(404, "")
            }
        }
        val r = client(http, MemStore(Credentials("rt", "org-1", "a@b.c"))).today()
        assertTrue(r is ApiResult.Ok)
        assertEquals(2, (r as ApiResult.Ok).value.counts.overdue)
    }

    @Test fun `原生 Bearer 两个头都要带上——少一个服务端就是 403`() {
        val http = FakeHttp { _, url, _, _ ->
            if (url.contains("refresh_token")) HttpResponse(200, """{"access_token":"at1"}""")
            else HttpResponse(200, okToday)
        }
        client(http, MemStore(Credentials("rt", "org-1", "a@b.c"))).today()
        val call = http.calls.last { it.second.endsWith("/api/mobile/today") }
        assertEquals("Bearer at1", call.third["Authorization"])
        assertEquals("org-1", call.third["x-deepbrain-org-id"])
    }

    @Test fun `401 自动续一次 token 就能救回来`() {
        var served = 0
        val http = FakeHttp { _, url, _, _ ->
            when {
                url.contains("refresh_token") -> HttpResponse(200, """{"access_token":"at${++served}"}""")
                // 第一次取数 401，续了 token 之后成功
                url.endsWith("today") && served <= 1 -> HttpResponse(401, "")
                else -> HttpResponse(200, okToday)
            }
        }
        val r = client(http, MemStore(Credentials("rt", "org-1", "a@b.c"))).today()
        assertTrue(r is ApiResult.Ok)
    }

    @Test fun `续不上就是真失效——不无限重试，把人送去登录`() {
        var refreshes = 0
        val http = FakeHttp { _, url, _, _ ->
            if (url.contains("refresh_token")) { refreshes++; HttpResponse(400, "") }
            else HttpResponse(401, "")
        }
        val r = client(http, MemStore(Credentials("bad", "org-1", "a@b.c"))).today()
        assertTrue(r is ApiResult.Unauthorized)
        assertTrue("续 token 不该反复试，实际 $refreshes 次", refreshes <= 2)
    }

    @Test fun `refresh token 轮换后要存新的——否则下次登不上`() {
        val store = MemStore(Credentials("old-rt", "org-1", "a@b.c"))
        val http = FakeHttp { _, url, _, _ ->
            if (url.contains("refresh_token"))
                HttpResponse(200, """{"access_token":"at","refresh_token":"new-rt"}""")
            else HttpResponse(200, okToday)
        }
        client(http, store).today()
        assertEquals("new-rt", store.load()!!.refreshToken)
    }

    @Test fun `服务端返回半截 JSON 不许崩，要报「看不懂」`() {
        val http = FakeHttp { _, url, _, _ ->
            if (url.contains("refresh_token")) HttpResponse(200, """{"access_token":"at"}""")
            else HttpResponse(200, """{"counts":{"overdue":""")
        }
        val r = client(http, MemStore(Credentials("rt", "org-1", "a@b.c"))).today()
        assertTrue(r is ApiResult.Failed)
    }

    // ---- 登录 ----

    @Test fun `登录成功要连组织一起拿到，缺一不可`() {
        val store = MemStore()
        val http = FakeHttp { _, url, _, _ ->
            when {
                url.contains("grant_type=password") ->
                    HttpResponse(200, """{"access_token":"at","refresh_token":"rt"}""")
                url.contains("memberships") -> HttpResponse(200, """[{"org_id":"org-9"}]""")
                else -> HttpResponse(404, "")
            }
        }
        val r = client(http, store).signIn("a@b.c", "pw")
        assertTrue(r is ApiResult.Ok)
        assertEquals("org-9", store.load()!!.orgId)
    }

    @Test fun `账号没有组织就不算登录成功——存下去每个请求都会 403 且看不出原因`() {
        val store = MemStore()
        val http = FakeHttp { _, url, _, _ ->
            when {
                url.contains("grant_type=password") ->
                    HttpResponse(200, """{"access_token":"at","refresh_token":"rt"}""")
                url.contains("memberships") -> HttpResponse(200, "[]")
                else -> HttpResponse(404, "")
            }
        }
        assertTrue(client(http, store).signIn("a@b.c", "pw") is ApiResult.Failed)
        assertNull("不该留下半截凭证", store.load())
    }

    @Test fun `密码错给人话，不是抛数字`() {
        val http = FakeHttp { _, _, _, _ -> HttpResponse(400, """{"error":"invalid_grant"}""") }
        val r = client(http, MemStore()).signIn("a@b.c", "wrong")
        assertTrue(r is ApiResult.Failed)
        assertTrue((r as ApiResult.Failed).message.contains("密码"))
    }

    @Test fun `退出要把凭证清干净`() {
        val store = MemStore(Credentials("rt", "org", "a@b.c"))
        client(FakeHttp { _, _, _, _ -> HttpResponse(200, "") }, store).signOut()
        assertNull(store.load())
    }
}
