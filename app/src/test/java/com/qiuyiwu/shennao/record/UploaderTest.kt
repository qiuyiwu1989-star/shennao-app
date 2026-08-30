package com.qiuyiwu.shennao.record

import com.qiuyiwu.shennao.Http
import com.qiuyiwu.shennao.HttpResponse
import org.junit.Assert.*
import org.junit.Test

/*
 * 边录边传的测试。
 *
 * 重点全在「进程在第几步被杀」。安卓随时会杀掉后台进程，而这条链路有六步——
 * 每一步之间死掉都是一个必须活下来的场景。这些场景没法靠拔电池来验，
 * 所以整个上传器被写成不碰真实网络、不碰真实磁盘，好让它们能在这里一条条摆出来。
 */

/** 内存版存储。目录=map，文件名=key，跟真机上的语义一致：名字即状态。 */
private class MemVault : Vault {
    val files = linkedMapOf<String, MutableMap<String, ByteArray>>()
    val metas = mutableMapOf<String, SessionMeta>()
    var deleted = mutableListOf<String>()

    fun put(session: String, seg: Segment, bytes: ByteArray = ByteArray(1000)) {
        files.getOrPut(session) { linkedMapOf() }[seg.fileName()] = bytes
    }

    override fun sessions() = files.keys.toList()
    override fun readMeta(session: String) = metas[session]
    override fun writeMeta(session: String, meta: SessionMeta) { metas[session] = meta }
    @Synchronized
    override fun updateMeta(session: String, f: (SessionMeta) -> SessionMeta): SessionMeta? {
        val cur = metas[session] ?: return null
        return f(cur).also { metas[session] = it }
    }
    override fun segments(session: String) =
        (files[session]?.keys ?: emptySet()).mapNotNull { Segment.parse(it) }.sortedBy { it.sequence }
    override fun readSegment(session: String, seg: Segment) = files[session]?.get(seg.fileName())
    override fun rename(session: String, from: Segment, to: Segment): Boolean {
        val d = files[session] ?: return false
        val b = d.remove(from.fileName()) ?: return false
        d[to.fileName()] = b
        return true
    }
    override fun deleteSession(session: String) { files.remove(session); deleted += session }
}

private class ScriptHttp(val handle: (String, String, String?) -> HttpResponse) : Http {
    val log = mutableListOf<String>()          // "METHOD path"，用来断言顺序
    val bodies = mutableListOf<String?>()
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        log += "$method ${path(url)}"; bodies += body
        return handle(method, url, body)
    }
    override fun requestBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        log += "$method ${path(url)}"; bodies += null
        return handle(method, url, null)
    }
    private fun path(u: String) = u.removePrefix("https://api.test").substringBefore("?")
}

private fun seg(n: Int, state: Segment.State, from: Long = n * 60_000L) =
    Segment(n, from, from + 60_000, state)

private const val CREATED = """{"session":{"id":"sess-1","status":"recording"}}"""
private const val TICKET = """{"uploadUrl":"https://cos.test/put/1"}"""

private fun uploader(v: Vault, h: Http) =
    Uploader(h, v, "https://api.test") { "at" to "org-1" }

private fun meta(finished: Boolean = false, sid: String? = null) =
    SessionMeta("req-abc", "手机录音", 1_700_000_000_000, finished, sid)

class UploaderTest {

    private fun happyPath(): ScriptHttp = ScriptHttp { _, url, _ ->
        when {
            url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
            url.contains("/chunks/ticket") -> HttpResponse(200, TICKET)
            url.startsWith("https://cos.test") -> HttpResponse(200, "")
            url.endsWith("/complete") -> HttpResponse(200, "{}")
            url.endsWith("/stop") -> HttpResponse(200, "{}")
            url.endsWith("/finalize") -> HttpResponse(200, "{}")
            else -> HttpResponse(404, "")
        }
    }

    // ---- 名字即状态 ----

    @Test fun `文件名能原样解析回来——序号和起止时刻都不放在内存里`() {
        val s = Segment(3, 180_000, 240_000, Segment.State.SEALED)
        assertEquals("seg-000003-000180000-000240000.m4a", s.fileName())
        assertEquals(s, Segment.parse(s.fileName()))
    }

    @Test fun `认不出的文件名不该让整场录音失败`() {
        assertNull(Segment.parse("random.txt"))
        assertNull(Segment.parse(".nomedia"))
        // 结束早于开始 = 名字被外力改过。宁可当它不存在，也不要拿负数时长去调服务端
        assertNull(Segment.parse("seg-000001-000240000-000180000.m4a"))
    }

    // ---- 什么时候不该动服务端 ----

    @Test fun `还在录、没有已封段——一个请求都不该发`() {
        val v = MemVault().apply { metas["s"] = meta(); put("s", seg(0, Segment.State.RECORDING)) }
        val h = happyPath()
        assertTrue(uploader(v, h).drain("s") is DrainResult.Idle)
        assertEquals(emptyList<String>(), h.log)
    }

    @Test fun `一段都没录到就停了——不在服务端建空会话`() {
        val v = MemVault().apply { metas["s"] = meta(finished = true); files["s"] = linkedMapOf() }
        val h = happyPath()
        assertTrue(uploader(v, h).drain("s") is DrainResult.Idle)
        assertEquals("不该建会话", emptyList<String>(), h.log)
        assertEquals(listOf("s"), v.deleted)
    }

    @Test fun `已停止但还有没封的段——绝不能去 stop，那会把最后一段永久关在门外`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.UPLOADED))
            put("s", seg(1, Segment.State.RECORDING))     // 录音线程还没封完
        }
        val h = happyPath()
        val r = uploader(v, h).drain("s")
        assertTrue(r is DrainResult.Progress)
        assertFalse("清单绝不能在这时冻结", h.log.any { it.endsWith("/stop") })
    }

    // ---- 正常链路 ----

    @Test fun `六步的顺序不能乱——stop 必须在 finalize 之前`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
        }
        val h = happyPath()
        val r = uploader(v, h).drain("s")
        assertTrue("$r", r is DrainResult.Done)
        assertEquals(
            listOf(
                "POST /api/recordings",
                "POST /api/recordings/sess-1/chunks/ticket",
                "PUT /put/1",
                "POST /api/recordings/sess-1/chunks/0/complete",
                "POST /api/recordings/sess-1/stop",
                "POST /api/recordings/sess-1/finalize",
            ),
            h.log.map { it.replace("https://cos.test", "") },
        )
    }

    @Test fun `冻结时报的片数和时长要跟实际落盘的对得上`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            (0..2).forEach { put("s", seg(it, Segment.State.SEALED)) }
        }
        val h = happyPath()
        uploader(v, h).drain("s")
        val stopBody = h.log.indexOfFirst { it.endsWith("/stop") }.let { h.bodies[it] }!!
        val o = org.json.JSONObject(stopBody)
        assertEquals(3, o.getInt("expectedChunkCount"))
        assertEquals(180_000, o.getLong("durationMs"))
    }

    @Test fun `边录边传：没停止就只传不收尾`() {
        val v = MemVault().apply {
            metas["s"] = meta()                            // 还在录
            put("s", seg(0, Segment.State.SEALED))
        }
        val h = happyPath()
        val r = uploader(v, h).drain("s")
        assertTrue(r is DrainResult.Progress)
        assertFalse(h.log.any { it.endsWith("/stop") || it.endsWith("/finalize") })
        assertEquals(Segment.State.UPLOADED, v.segments("s").single().state)
    }

    // ---- 进程在第 N 步被杀 ----

    @Test fun `死在 PUT 之后、确认之前——重跑时服务端说这片已经有了，跳过而不是失败`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))          // 名字还停在「待上传」
        }
        val h = ScriptHttp { _, url, _ ->
            when {
                url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
                url.contains("/chunks/ticket") ->
                    HttpResponse(409, """{"code":"CHUNK_ALREADY_VERIFIED"}""")
                url.endsWith("/stop") || url.endsWith("/finalize") -> HttpResponse(200, "{}")
                else -> HttpResponse(404, "")
            }
        }
        val r = uploader(v, h).drain("s")
        assertTrue("$r", r is DrainResult.Done)
        assertFalse("已在服务端的片不该再传一遍", h.log.any { it.startsWith("PUT") })
    }

    @Test fun `确认失败时不许改名——改了这一片就被当成传好了而永远丢掉`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
        }
        val h = ScriptHttp { _, url, _ ->
            when {
                url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
                url.contains("/chunks/ticket") -> HttpResponse(200, TICKET)
                url.startsWith("https://cos.test") -> HttpResponse(200, "")
                url.endsWith("/complete") -> HttpResponse(500, "boom")
                else -> HttpResponse(404, "")
            }
        }
        val r = uploader(v, h).drain("s")
        assertTrue(r is DrainResult.Failed)
        assertTrue("500 该留着下轮再试", (r as DrainResult.Failed).retryable)
        assertEquals("必须还停在待上传", Segment.State.SEALED, v.segments("s").single().state)
    }

    @Test fun `死在 finalize 之后、删本地之前——重跑认出已收尾，清干净而不是报错`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true, sid = "sess-1")
            put("s", seg(0, Segment.State.UPLOADED))        // 都传完了
        }
        val h = ScriptHttp { _, url, _ ->
            if (url.endsWith("/api/recordings"))
                HttpResponse(200, """{"session":{"id":"sess-1","status":"completed"}}""")
            else HttpResponse(404, "")
        }
        val r = uploader(v, h).drain("s")
        assertTrue("$r", r is DrainResult.Done)
        assertEquals(listOf("s"), v.deleted)
    }

    @Test fun `服务端已冻结、本地还有没进去的段——报出来，绝不删本地录音`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true, sid = "sess-1")
            put("s", seg(0, Segment.State.UPLOADED))
            put("s", seg(1, Segment.State.SEALED))          // 这一段再也进不去了
        }
        val h = ScriptHttp { _, url, _ ->
            if (url.endsWith("/api/recordings"))
                HttpResponse(200, """{"session":{"id":"sess-1","status":"completed"}}""")
            else HttpResponse(404, "")
        }
        val r = uploader(v, h).drain("s")
        assertTrue(r is DrainResult.Failed)
        assertFalse("不可重试的错也不该把录音删了", (r as DrainResult.Failed).retryable)
        assertTrue(v.deleted.isEmpty())
        assertEquals(2, v.segments("s").size)
    }

    @Test fun `failed 的会话不是「已经做完了」——它挡着重传，必须报出来`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
        }
        val h = ScriptHttp { _, url, _ ->
            if (url.endsWith("/api/recordings"))
                HttpResponse(200, """{"session":{"id":"sess-1","status":"failed"}}""")
            else HttpResponse(404, "")
        }
        val r = uploader(v, h).drain("s")
        assertTrue("$r", r is DrainResult.Failed)
        assertFalse((r as DrainResult.Failed).retryable)
        assertTrue(r.message.contains("failed"))
        assertTrue("失败的会话不该把本地录音删掉", v.deleted.isEmpty())
    }

    // ---- 幂等键 ----

    @Test fun `幂等键由序号决定，不由重试次数决定——重跑必须拿同一个键`() {
        fun keyOf(): String {
            val v = MemVault().apply {
                metas["s"] = meta(); put("s", seg(2, Segment.State.SEALED))
            }
            val h = happyPath()
            uploader(v, h).drain("s")
            val i = h.log.indexOfFirst { it.contains("ticket") }
            return org.json.JSONObject(h.bodies[i]!!).getString("idempotencyKey")
        }
        assertEquals("req-abc-2", keyOf())
        assertEquals("两次跑必须一样", keyOf(), keyOf())
    }

    @Test fun `建会话要带上真实开始时刻，不是上传时刻`() {
        val v = MemVault().apply { metas["s"] = meta(); put("s", seg(0, Segment.State.SEALED)) }
        val h = happyPath()
        uploader(v, h).drain("s")
        val body = org.json.JSONObject(h.bodies[0]!!)
        assertEquals("2023-11-14T22:13:20Z", body.getString("startedAt"))
        assertEquals("android", body.getString("captureClient"))
    }

    @Test fun `没登录就整轮不动，且留着下次再试`() {
        val v = MemVault().apply { metas["s"] = meta(); put("s", seg(0, Segment.State.SEALED)) }
        val h = happyPath()
        val r = Uploader(h, v, "https://api.test") { null }.drain("s")
        assertTrue(r is DrainResult.Failed && r.retryable)
        assertEquals(emptyList<String>(), h.log)
    }
}

class MetaRaceTest {
    /*
     * 录音线程写 finished=true 的同时，上传线程正拿着旧 meta 要写 serverSessionId。
     * 各自「读→copy→写回」的话，后写的会把 finished 抹回 false，
     * 这场录音就再也不会收尾——表现是「偶尔有一场一直显示在传」。
     */
    @Test fun `建会话落盘 serverSessionId 时，不许把 finished 抹回去`() {
        val v = MemVault().apply { metas["s"] = meta(); put("s", seg(0, Segment.State.SEALED)) }
        val h = ScriptHttp { _, url, _ ->
            if (url.endsWith("/api/recordings")) {
                // 模拟：就在建会话往返的这一刻，录音线程停了录音
                v.updateMeta("s") { it.copy(finished = true) }
                HttpResponse(200, CREATED)
            } else when {
                url.contains("/chunks/ticket") -> HttpResponse(200, TICKET)
                url.startsWith("https://cos.test") -> HttpResponse(200, "")
                url.endsWith("/complete") -> HttpResponse(200, "{}")
                else -> HttpResponse(200, "{}")
            }
        }
        uploader(v, h).drain("s")
        assertTrue("finished 被抹掉了，这场录音永远不会收尾", v.metas["s"]!!.finished)
        assertEquals("sess-1", v.metas["s"]!!.serverSessionId)
    }
}

class UploaderAuthTest {
    /*
     * 一场会开两小时，access token 只活一小时。第 60 分钟之后每一段都会 401——
     * 不续期的话录音会静默传不上去，而界面上什么都看不出来。
     */
    @Test fun `传到一半 token 过期——续一次接着传，不是整场失败`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
        }
        var handed = 0
        val h = ScriptHttp { _, url, _ ->
            when {
                // 第一次用旧 token 建会话被拒，续过之后放行
                url.endsWith("/api/recordings") ->
                    if (handed <= 1) HttpResponse(401, "") else HttpResponse(200, CREATED)
                url.contains("/chunks/ticket") -> HttpResponse(200, TICKET)
                url.startsWith("https://cos.test") -> HttpResponse(200, "")
                url.endsWith("/complete") -> HttpResponse(200, "{}")
                url.endsWith("/stop") || url.endsWith("/finalize") -> HttpResponse(200, "{}")
                else -> HttpResponse(404, "")
            }
        }
        val u = Uploader(h, v, "https://api.test") { force ->
            handed++
            if (force) "fresh" to "org-1" else "stale" to "org-1"
        }
        val r = u.drain("s")
        assertTrue("$r", r is DrainResult.Done)
        assertEquals("必须真的换了新 token", 2, handed)
    }

    @Test fun `续了还是 401 就别再续——反复重试只会烧电`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
        }
        var handed = 0
        val h = ScriptHttp { _, _, _ -> HttpResponse(401, "") }
        val r = Uploader(h, v, "https://api.test") { handed++; "t" to "org-1" }.drain("s")
        assertTrue(r is DrainResult.Failed)
        assertEquals("只该续一次", 2, handed)
    }
}
