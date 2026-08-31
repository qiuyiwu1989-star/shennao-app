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
    override fun requestBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        calls += Triple(method, url, headers)
        return handler(method, url, headers, null)
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

class UpdateTest {
    private fun http(status: Int, body: String) = object : Http {
        override fun request(method: String, url: String, headers: Map<String, String>, body2: String?) =
            HttpResponse(status, body)
        override fun requestBytes(method: String, url: String, headers: Map<String, String>, body2: ByteArray) =
            HttpResponse(status, body)
    }

    private val ok = """{"versionName":"0.9.0","versionCode":12,"file":"a.apk","size":9886158,
        "sha256":"abc","url":"https://shennao.zaowuyun.com/downloads/a.apk"}"""

    @Test fun `有新版就报出来`() {
        val s = Update.check(http(200, ok), currentCode = 7)
        assertTrue(s is UpdateState.Available)
        assertEquals("0.9.0", (s as UpdateState.Available).release.versionName)
    }

    @Test fun `同版本不提示`() {
        assertTrue(Update.check(http(200, ok), currentCode = 12) is UpdateState.UpToDate)
    }

    @Test fun `服务端回滚过一版时，不该劝用户装个更旧的`() {
        assertTrue(Update.check(http(200, ok), currentCode = 20) is UpdateState.UpToDate)
    }

    @Test fun `比的是 versionCode 不是名字——字符串比较会把 0点10 判成比 0点9 旧`() {
        val newer = Update.parse("""{"versionName":"0.10.0","versionCode":13,"url":"u"}""")!!
        assertTrue(Update.compare(9, newer) is UpdateState.Available)   // versionName 0.10.0 < "0.9.0" 字符串序
    }

    @Test fun `网络不通要说「查不到」，不能说「已是最新」`() {
        val dead = object : Http {
            override fun request(m: String, u: String, h: Map<String, String>, b: String?): HttpResponse = throw RuntimeException("no net")
            override fun requestBytes(m: String, u: String, h: Map<String, String>, b: ByteArray): HttpResponse = throw RuntimeException("no net")
        }
        assertTrue(Update.check(dead, 7) is UpdateState.Unknown)
    }

    @Test fun `半个清单一律当读不出来，绝不返回半个 Release`() {
        assertNull(Update.parse("""{"versionName":"0.9.0"}"""))          // 没有 code / url
        assertNull(Update.parse("""{"versionCode":9,"url":"u"}"""))      // 没有名字
        assertNull(Update.parse("不是 json"))
    }
}

class HotwordTest {
    /*
     * 本场专有名词（0080）。组织底表是建会话时自动合上的，不用管；
     * 这里传的是这场会特有的词。录音中途也能改。
     */
    private fun spy(status: Int, body: String): Pair<Http, MutableList<String?>> {
        val bodies = mutableListOf<String?>()
        val h = object : Http {
            override fun request(m: String, u: String, hd: Map<String, String>, b: String?): HttpResponse {
                if (u.contains("hotwords")) bodies += b
                return if (u.contains("refresh_token")) HttpResponse(200, """{"access_token":"at"}""")
                       else HttpResponse(status, body)
            }
            override fun requestBytes(m: String, u: String, hd: Map<String, String>, b: ByteArray) =
                HttpResponse(status, body)
        }
        return h to bodies
    }

    @Test fun `词按 pins 数组发出去`() {
        val (h, bodies) = spy(200, """{"accepted":["造物云","品焕"],"rejected":[]}""")
        val c = client(h, MemStore(Credentials("rt", "org-1", "a@b.c")))
        val r = c.setHotwordPins("sess-1", listOf("造物云", "品焕"))
        assertTrue(r is ApiResult.Ok)
        val sent = org.json.JSONObject(bodies.last()!!).getJSONArray("pins")
        assertEquals(2, sent.length())
        assertEquals("造物云", sent.getString(0))
    }

    @Test fun `回报的是服务端真正接受的词，不是用户输入的`() {
        // 超限或重复会被服务端丢掉。照抄输入会让用户以为被丢的那个在起作用。
        val (h, _) = spy(200, """{"accepted":["造物云"],"rejected":[{"term":"品焕"}]}""")
        val r = client(h, MemStore(Credentials("rt", "org-1", "a@b.c")))
            .setHotwordPins("sess-1", listOf("造物云", "品焕"))
        assertEquals(listOf("造物云"), (r as ApiResult.Ok).value)
    }

    @Test fun `没登录不发请求`() {
        val (h, bodies) = spy(200, "{}")
        assertTrue(client(h, MemStore()).setHotwordPins("s", listOf("x")) is ApiResult.Unauthorized)
        assertEquals(0, bodies.size)
    }

    @Test fun `存不上要说出来，不能假装成功`() {
        val (h, _) = spy(409, """{"error":"会话已冻结"}""")
        assertTrue(client(h, MemStore(Credentials("rt", "org-1", "a@b.c")))
            .setHotwordPins("s", listOf("x")) is ApiResult.Failed)
    }
}

class CacheTest {
    /*
     * 缓存的价值全在「标出这是什么时候的」。不标时间的缓存比没有缓存更糟——
     * 用户会拿三天前的数据当今天的，而这一层的内容恰恰是有时效的（到期承诺）。
     */
    @Test fun `刚取的不标时间——标了只会让人以为它旧了`() {
        assertNull(Cache.staleLabel(1_000_000L, 1_000_000L + 30_000))
    }

    @Test fun `分钟、小时、天都要说人话`() {
        val t = 1_000_000_000L
        assertEquals("离线 · 5 分钟前的", Cache.staleLabel(t, t + 5 * 60_000))
        assertEquals("离线 · 3 小时前的", Cache.staleLabel(t, t + 3 * 3600_000))
        assertEquals("离线 · 2 天前的", Cache.staleLabel(t, t + 2 * 86_400_000L))
    }

    @Test fun `存了能读回来，读不到不抛`() {
        val dir = kotlin.io.path.createTempDirectory("cache").toFile()
        val c = Cache(dir)
        assertNull(c.load(Cache.TODAY))
        c.save(Cache.TODAY, """{"a":1}""")
        assertEquals("""{"a":1}""", c.load(Cache.TODAY)!!.body)
    }

    @Test fun `空文件当成没有缓存——半截 json 解出来是空列表，看着就像「今天没事」`() {
        val dir = kotlin.io.path.createTempDirectory("cache2").toFile()
        java.io.File(dir, "today.json").writeText("")
        assertNull(Cache(dir).load(Cache.TODAY))
    }
}

class RemindTest {
    /*
     * 提醒是手机端相对网页的唯一优势。而它最容易出的两种错，
     * 都只会发生在别人的手机上：算错时刻半夜把人叫醒、同一句话推两遍。
     */

    private fun cal(y: Int, mo: Int, d: Int, h: Int, mi: Int, tz: String = "Asia/Shanghai"): Long {
        val z = java.util.TimeZone.getTimeZone(tz)
        val c = java.util.Calendar.getInstance(z)
        c.set(y, mo - 1, d, h, mi, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test fun `早上八点 → 一小时后就是今天九点`() {
        val tz = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val d = Remind.initialDelayMs(cal(2026, 8, 31, 8, 0), tz)
        assertEquals(60 * 60 * 1000L, d)
    }

    @Test fun `已经过了九点 → 排到明天，不是立刻推`() {
        val tz = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val d = Remind.initialDelayMs(cal(2026, 8, 31, 14, 0), tz)
        // 到明天九点还有 19 小时
        assertEquals(19 * 60 * 60 * 1000L, d)
    }

    @Test fun `正好九点整 → 也排到明天，不该在这一刻立刻炸一条`() {
        val tz = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val d = Remind.initialDelayMs(cal(2026, 8, 31, 9, 0), tz)
        assertEquals(24 * 60 * 60 * 1000L, d)
    }

    @Test fun `延迟永远为正——负数会让 WorkManager 立刻跑，半夜把人叫醒`() {
        val tz = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        for (h in 0..23) {
            assertTrue("$h 点算出了非正延迟", Remind.initialDelayMs(cal(2026, 8, 31, h, 30), tz) > 0)
        }
    }

    @Test fun `同一天同样的内容不重复推`() {
        val k = Remind.keyOf("2026-08-31", "3 条承诺过期")
        assertTrue(Remind.shouldNotify(null, k))
        assertFalse(Remind.shouldNotify(k, k))
    }

    @Test fun `内容变了要推——从 3 条变 5 条是新信息`() {
        val a = Remind.keyOf("2026-08-31", "3 条承诺过期")
        val b = Remind.keyOf("2026-08-31", "5 条承诺过期")
        assertTrue(Remind.shouldNotify(a, b))
    }

    @Test fun `第二天同样的内容要推——它今天仍然到期`() {
        val a = Remind.keyOf("2026-08-31", "3 条承诺过期")
        val b = Remind.keyOf("2026-09-01", "3 条承诺过期")
        assertTrue(Remind.shouldNotify(a, b))
    }
}
