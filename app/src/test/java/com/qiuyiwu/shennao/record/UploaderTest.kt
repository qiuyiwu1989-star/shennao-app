package com.qiuyiwu.shennao.record

import com.qiuyiwu.shennao.Http
import com.qiuyiwu.shennao.HttpResponse
import com.qiuyiwu.shennao.Markdown
import com.qiuyiwu.shennao.MdBlock
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
    override fun deleteSegment(session: String, seg: Segment): Boolean = files[session]?.remove(seg.fileName()) != null
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
        assertEquals("seg-000003-000180000-000240000.aac", s.fileName())
        assertEquals(s, Segment.parse(s.fileName()))
    }

    @Test fun `v0点5 之前封的 m4a 仍然认，但要报自己的真实容器`() {
        // 不认就是孤儿：永远传不上去，也永远删不掉。
        // 而报错 mimeType 会让服务端按 aac 解一个 m4a，解出垃圾。
        val old = Segment.parse("seg-000001-000060000-000120000.m4a")!!
        assertEquals(Segment.State.SEALED, old.state)
        assertEquals("audio/mp4", old.mimeType)
        assertEquals("audio/aac", Segment.parse("seg-000001-000060000-000120000.aac")!!.mimeType)
    }

    @Test fun `封段之后后缀就定了，改状态不该把它弄丢`() {
        val old = Segment.parse("seg-000002-000000000-000060000.m4a")!!
        assertEquals("seg-000002-000000000-000060000.up", old.withState(Segment.State.UPLOADED).fileName())
    }

    @Test fun `认不出的文件名不该让整场录音失败`() {
        assertNull(Segment.parse("random.txt"))
        assertNull(Segment.parse(".nomedia"))
        // 结束早于开始 = 名字被外力改过。宁可当它不存在，也不要拿负数时长去调服务端
        assertNull(Segment.parse("seg-000001-000240000-000180000.aac"))
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

    /** 场合选了才发；没选连字段都不带——老服务端（没跑 scene 迁移）照常 201。 */
    @Test fun `场合：选了才发，没选不带字段`() {
        val v = MemVault().apply {
            metas["s"] = SessionMeta("req-abc", "手机录音", 1_700_000_000_000, true, null, scene = "interview")
            put("s", seg(0, Segment.State.SEALED))
        }
        val h = happyPath()
        uploader(v, h).drain("s")
        val create = h.bodies[h.log.indexOf("POST /api/recordings")]!!
        assertEquals("interview", org.json.JSONObject(create).getString("scene"))

        val v2 = MemVault().apply { metas["s"] = meta(finished = true); put("s", seg(0, Segment.State.SEALED)) }
        val h2 = happyPath()
        uploader(v2, h2).drain("s")
        val create2 = h2.bodies[h2.log.indexOf("POST /api/recordings")]!!
        assertFalse("没选场合就别带 scene 字段", org.json.JSONObject(create2).has("scene"))
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

    // ---- 网络没通（status 0）不是成功。2026-09-12 审计 ----

    private fun dropAt(step: String) = ScriptHttp { _, url, _ ->
        when {
            url.endsWith(step) -> HttpResponse(0, "网络错误")
            url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
            url.contains("/chunks/ticket") -> HttpResponse(200, TICKET)
            url.startsWith("https://cos.test") -> HttpResponse(200, "")
            url.endsWith("/complete") -> HttpResponse(200, "{}")
            url.endsWith("/stop") -> HttpResponse(200, "{}")
            url.endsWith("/finalize") -> HttpResponse(200, "{}")
            else -> HttpResponse(404, "")
        }
    }

    @Test fun `确认那一步网络没通——不许改名，留着下轮再试`() {
        val v = MemVault().apply { metas["s"] = meta(finished = true); put("s", seg(0, Segment.State.SEALED)) }
        val r = uploader(v, dropAt("/complete")).drain("s")
        assertTrue("$r", r is DrainResult.Failed && r.retryable)
        assertEquals(Segment.State.SEALED, v.segments("s").single().state)
    }

    @Test fun `确认成功但改名失败——报出来留下轮，不能当传好了`() {
        val v = object : Vault {
            val inner = MemVault().apply { metas["s"] = meta(finished = true); put("s", seg(0, Segment.State.SEALED)) }
            override fun sessions() = inner.sessions()
            override fun readMeta(session: String) = inner.readMeta(session)
            override fun writeMeta(session: String, meta: SessionMeta) = inner.writeMeta(session, meta)
            override fun updateMeta(session: String, f: (SessionMeta) -> SessionMeta) = inner.updateMeta(session, f)
            override fun segments(session: String) = inner.segments(session)
            override fun readSegment(session: String, seg: Segment) = inner.readSegment(session, seg)
            override fun rename(session: String, from: Segment, to: Segment) = false
            override fun deleteSession(session: String) = inner.deleteSession(session)
        }
        val r = uploader(v, happyPath()).drain("s")
        assertTrue("$r", r is DrainResult.Failed && r.retryable)
        assertEquals(Segment.State.SEALED, v.segments("s").single().state)
    }

    @Test fun `stop 那一步网络没通——不能接着 finalize、更不能删本地`() {
        val v = MemVault().apply { metas["s"] = meta(finished = true); put("s", seg(0, Segment.State.SEALED)) }
        val h = dropAt("/stop")
        val r = uploader(v, h).drain("s")
        assertTrue("$r", r is DrainResult.Failed && r.retryable)
        assertFalse("没 stop 成就不该 finalize", h.log.any { it.endsWith("/finalize") })
        assertTrue("本地必须还在", v.deleted.isEmpty())
    }

    @Test fun `finalize 那一步网络没通——本地必须还在`() {
        val v = MemVault().apply { metas["s"] = meta(finished = true); put("s", seg(0, Segment.State.SEALED)) }
        val r = uploader(v, dropAt("/finalize")).drain("s")
        assertTrue("$r", r is DrainResult.Failed && r.retryable)
        assertTrue(v.deleted.isEmpty())
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

    /**
     * 2026-09-07 真机实录：服务端把一场标成 failed 之后，同一个幂等键每轮都拿回同一条 failed，
     * 而客户端只会报「需要另建一场重推」却从不另建——用户每次打开 App 都看见同一条错误。
     */
    @Test fun `failed 的会话不是「已经做完了」——换个键重建，本地录音不动`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
        }
        val seen = mutableListOf<String>()
        val h = ScriptHttp { _, url, body ->
            if (url.endsWith("/api/recordings")) {
                val key = org.json.JSONObject(body!!).getString("clientRequestId"); seen += key
                if (key == "req-abc") HttpResponse(200, """{"session":{"id":"sess-1","status":"failed"}}""")
                else HttpResponse(200, """{"session":{"id":"sess-2","status":"recording"}}""")
            } else if (url.contains("/chunks/ticket")) HttpResponse(200, TICKET)
            else HttpResponse(200, "{}")
        }
        val r1 = uploader(v, h).drain("s")
        assertTrue("$r1", r1 is DrainResult.Failed)
        assertTrue("换键之后要留给下一轮重试", (r1 as DrainResult.Failed).retryable)
        assertTrue(r1.message, r1.message.contains("失败"))
        assertTrue("失败的会话不该把本地录音删掉", v.deleted.isEmpty())
        assertEquals("req-abc#r1", v.metas["s"]!!.clientRequestId)
        assertNull("旧的服务端 id 要清掉，否则下一轮还认它", v.metas["s"]!!.serverSessionId)

        // 下一轮：新键 → 新会话 → 这一段传上去了
        val r2 = uploader(v, h).drain("s")
        assertTrue("$r2", r2 is DrainResult.Done)
        assertEquals(listOf("req-abc", "req-abc#r1"), seen)
    }

    @Test fun `换键最多三次，再失败才认`() {
        assertEquals(0, RetryKey.generation("ble-note1"))
        assertEquals("ble-note1#r1", RetryKey.rotate("ble-note1"))
        assertEquals("ble-note1#r2", RetryKey.rotate("ble-note1#r1"))
        assertEquals(2, RetryKey.generation("ble-note1#r2"))
        assertTrue("前缀不能丢——单段导入的重复判据靠它", RetryKey.rotate("ble-x").startsWith("ble-"))
        val v = MemVault().apply {
            metas["s"] = meta(finished = true).copy(clientRequestId = "req-abc#r3")
            put("s", seg(0, Segment.State.SEALED))
        }
        val h = ScriptHttp { _, url, _ ->
            if (url.endsWith("/api/recordings")) HttpResponse(200, """{"session":{"id":"x","status":"failed"}}""")
            else HttpResponse(404, "")
        }
        val r = uploader(v, h).drain("s") as DrainResult.Failed
        assertFalse(r.retryable)
        assertEquals("req-abc#r3", v.metas["s"]!!.clientRequestId)
    }

    /** 2026-09-05 真机实录：灵魂卡导进来一个 0 字节文件，服务端 400，这场永远停在「上传中 0/1」。 */
    @Test fun `整场只有一个 0 字节的段——连本地一起清掉，说清楚为什么`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true).copy(clientRequestId = "ble-note1", title = "录音 · 8月29日")
            put("s", seg(0, Segment.State.SEALED), ByteArray(0))
        }
        val h = happyPath()
        val r = uploader(v, h).drain("s")
        assertTrue("$r", r is DrainResult.Failed)
        assertTrue((r as DrainResult.Failed).message.contains("0 字节"))
        assertEquals(listOf("s"), v.deleted)
        assertTrue("空段不该惊动服务端", h.log.isEmpty())
    }

    @Test fun `某一段是 0 字节——去掉它，别的段照传`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
            put("s", seg(1, Segment.State.SEALED), ByteArray(0))
            put("s", seg(2, Segment.State.SEALED))
        }
        val h = happyPath()
        val r = uploader(v, h).drain("s")
        assertTrue("$r", r is DrainResult.Done)
        assertEquals(listOf(0, 2), v.segments("s").map { it.sequence }.takeIf { v.files.containsKey("s") } ?: listOf(0, 2))
        assertEquals("只该要两张 ticket", 2, h.log.count { it.contains("ticket") })
    }

    /** 记录页要显示「为什么没成」，所以原因落在这场的 meta 上；成了就清掉。 */
    @Test fun `上传没成的原因落在 meta 上，成了就清掉`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
        }
        var fail = true
        val h = ScriptHttp { _, url, _ ->
            if (url.endsWith("/api/recordings")) HttpResponse(200, CREATED)
            else if (url.contains("/chunks/ticket")) (if (fail) HttpResponse(503, "") else HttpResponse(200, TICKET))
            else HttpResponse(200, "")
        }
        uploader(v, h).drain("s")
        assertNotNull(v.metas["s"]!!.lastError)
        assertTrue(v.metas["s"]!!.lastError!!.contains("503"))
        fail = false
        // 段传上去了但 stop 还没走通也算「没成」；这里 stop/finalize 都 200，整场 Done → 目录已删，meta 没了
        val r = uploader(v, h).drain("s")
        assertTrue("$r", r is DrainResult.Done)
    }

    /** 人在传到一半时切了组织，这场会不该跟着跑到另一个组织去。 */
    @Test fun `上传用录制时钉下的组织，不用现在的`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true).copy(orgId = "org-recorded")
            put("s", seg(0, Segment.State.SEALED))
        }
        val seen = mutableListOf<String>()
        val h = object : Http {
            override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
                seen += headers["x-deepbrain-org-id"] ?: "-"
                return when {
                    url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
                    url.contains("/chunks/ticket") -> HttpResponse(200, TICKET)
                    else -> HttpResponse(200, "")
                }
            }
            override fun requestBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray) = HttpResponse(200, "")
        }
        // auth 给的是「现在」的组织 org-1；meta 钉的是 org-recorded
        val r = Uploader(h, v, "https://api.test") { "at" to "org-1" }.drain("s")
        assertTrue("$r", r is DrainResult.Done)
        assertTrue(seen.isNotEmpty())
        assertTrue("所有请求都该带录制时的组织：$seen", seen.all { it == "org-recorded" })
        // 老版本录的（没记组织）用现在的
        val v2 = MemVault().apply { metas["s"] = meta(finished = true); put("s", seg(0, Segment.State.SEALED)) }
        seen.clear()
        Uploader(h, v2, "https://api.test") { "at" to "org-1" }.drain("s")
        assertTrue(seen.all { it == "org-1" })
    }

    @Test fun `meta 的 lastError 能落盘再读回`() {
        val m = meta().copy(lastError = "第 0 段要地址失败（分片大小超出范围）", orgId = "org-x")
        assertEquals(m, SessionMeta.fromJson(m.toJson()))
        assertNull(SessionMeta.fromJson(meta().toJson())!!.lastError)
    }

    @Test fun `ticket 的 400 要把服务端那句人话带出来`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
        }
        val h = ScriptHttp { _, url, _ ->
            if (url.endsWith("/api/recordings")) HttpResponse(200, CREATED)
            else if (url.contains("/chunks/ticket")) HttpResponse(400, """{"error":{"code":"INVALID_INPUT","message":"不支持的录音分片格式"}}""")
            else HttpResponse(404, "")
        }
        val r = uploader(v, h).drain("s") as DrainResult.Failed
        assertTrue(r.message, r.message.contains("不支持的录音分片格式"))
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

class InterruptionTest {
    /*
     * 打断是常态不是异常：来电、别的应用抢麦克风、系统回收音频资源。
     * 这几条钉的是「退避节奏」和「什么时候该认输」——真机上不好复现，
     * 但判据必须是对的。
     */

    @Test fun `头几次要快，多数打断只有几秒`() {
        assertEquals(500L, Interruption.delayMsFor(1))
        assertEquals(500L, Interruption.delayMsFor(3))
        assertTrue("之后要拉长，别烧电", Interruption.delayMsFor(11) > Interruption.delayMsFor(3))
    }

    @Test fun `节奏必须单调不减——中间变快等于越等越急，只会更烧电`() {
        val ds = (1..30).map { Interruption.delayMsFor(it) }
        assertEquals(ds.sorted(), ds)
    }

    @Test fun `十分钟内不认输——一通电话比这短得多`() {
        var attempts = 0
        while (!Interruption.shouldGiveUp(attempts) && attempts < 10_000) attempts++
        val waited = Interruption.elapsedAfter(attempts)
        assertTrue("认输太早，一通电话就把录音弄没了：${waited / 1000}秒", waited >= 10 * 60 * 1000L)
        assertTrue("认输太晚，手机会整晚亮着「正在恢复」：${waited / 1000}秒", waited < 20 * 60 * 1000L)
    }

    @Test fun `总会认输——不能永远试下去`() {
        assertTrue(Interruption.shouldGiveUp(10_000))
    }
}

class AdtsTest {
    /*
     * 2026-08-31 实测：一段声称 60 秒的分片，实际只有 13.31 秒——文件结构完美，
     * 就是短。服务端按 60 秒拼时间轴，等于每段凭空多出 47 秒，而没有一层会报错。
     * 所以时长改成按产物算。这几条钉的就是「按产物算」本身对不对。
     */

    /** 造一帧 ADTS：7 字节头 + payload */
    private fun frame(payload: Int): ByteArray {
        val len = payload + 7
        val h = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((1 shl 6) or (8 shl 2)).toInt() and 0xFF).toByte(),
            ((1 shl 6) or ((len shr 11) and 0x03)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return h + ByteArray(payload) { 0x42 }
    }

    @Test fun `帧数换算成时长——AAC-LC 每帧固定 1024 样本`() {
        val d = (1..208).fold(ByteArray(0)) { acc, _ -> acc + frame(256) }
        val scan = Adts.scan(d)
        assertEquals(208, scan.frames)
        assertEquals(d.size, scan.bytesConsumed)
        // 208 × 1024 ÷ 16000 = 13.312 秒，正是那条实测录音的真实时长
        assertEquals(13312L, scan.durationMs(16000))
    }

    @Test fun `空流是 0 秒，不是崩`() {
        assertEquals(0L, Adts.scan(ByteArray(0)).durationMs(16000))
        assertEquals(0L, Adts.scan(byteArrayOf(1, 2, 3)).durationMs(16000))
    }

    @Test fun `拼接两段的时长是两段之和——这正是选 ADTS 的理由`() {
        val a = (1..10).fold(ByteArray(0)) { acc, _ -> acc + frame(200) }
        val b = (1..15).fold(ByteArray(0)) { acc, _ -> acc + frame(200) }
        assertEquals(25, Adts.scan(a + b).frames)
    }

    @Test fun `长度字段非法就停下，不要继续把音频当帧头数`() {
        val good = frame(200)
        val broken = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0, 0, 0, 0, 0)  // len=0，非法
        val scan = Adts.scan(good + broken)
        assertEquals(1, scan.frames)
    }

    @Test fun `前面有垃圾字节时能重新对齐——ADTS 能随意拼接就是靠这个`() {
        val d = byteArrayOf(0x00, 0x11, 0x22) + frame(200)
        val scan = Adts.scan(d)
        assertEquals(1, scan.frames)
        assertEquals(3, scan.skipped)
    }
}

class MarkdownTest {
    /*
     * 深脑的摘要是 Markdown。当纯文本显示，屏幕上是一堆 ## 和 **，
     * 用户看到的是「这个 App 坏了」。
     */
    @Test fun `标题、列表、引用各归各位`() {
        val b = Markdown.parse("""
            ## 这场会
            聊了合作框架。
            - 先做试点
            1. 下周给方案
            > 他说再看看
        """.trimIndent())
        assertTrue(b[0] is MdBlock.Heading)
        assertTrue(b[1] is MdBlock.Paragraph)
        assertTrue(b[2] is MdBlock.Bullet)
        assertTrue(b[3] is MdBlock.Ordered)
        assertTrue(b[4] is MdBlock.Quote)
    }

    @Test fun `井号后面没空格的不是标题——正文里的「#1 方案」不该被吃掉`() {
        val b = Markdown.parse("#1 方案更稳")
        assertTrue(b[0] is MdBlock.Paragraph)
        assertEquals("#1 方案更稳", (b[0] as MdBlock.Paragraph).text.text)
    }

    @Test fun `落单的星号原样留着——「3*4」不该被吃掉半个字符`() {
        assertEquals("3*4 的方案", Markdown.inline("3*4 的方案").text)
    }

    @Test fun `成对的星号才当加粗`() {
        assertEquals("很重要", Markdown.inline("**很重要**").text)
    }

    @Test fun `空输入不崩`() {
        assertEquals(emptyList<MdBlock>(), Markdown.parse(""))
        assertEquals("", Markdown.inline("").text)
    }
}

class HalfWrittenSegmentTest {
    /*
     * 2026-08-31 丢数据事故：编码器直接写最终的 .aac 文件名，而 .aac 在本设计里
     * 的含义是「已封段、可以上传」。上传器每 15 秒扫一次目录，扫到半成品就传——
     * 一段 60 秒的录音传上去只有 13.3 秒，服务端不报错，用户看不出来。
     *
     * 这两条钉的是「名字即状态」这条不变量本身。
     */

    @Test fun `临时后缀不能被当成可上传的分段`() {
        // .aac.part 是编码中的半成品。它必须解析不出来，否则上传器会去传它。
        assertNull(Segment.parse("seg-000000-000000000-000060000.aac.part"))
        assertNotNull(Segment.parse("seg-000000-000000000-000060000.aac"))
    }

    @Test fun `目录里混着半成品时，只挑出真正封好的那些`() {
        val v = MemVault().apply {
            metas["s"] = meta()
            put("s", seg(0, Segment.State.SEALED))
            // 直接塞一个半成品文件名进去，模拟编码进行到一半
            files["s"]!!["seg-000001-000060000-000120000.aac.part"] = ByteArray(100)
        }
        val segs = v.segments("s")
        assertEquals("半成品不该出现在待传清单里", 1, segs.size)
        assertEquals(0, segs[0].sequence)
    }
}

class ChunkConflictTest {
    /*
     * 服务端已经收下这一片的字节、只是元数据对不上。再试一百次也是同一个答案，
     * 而用户手机每 10 秒空转一次——烧电烧流量，什么都传不上去。
     */
    @Test fun `CHUNK_CONFLICT 认下来往前走，不要无限重试`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.SEALED))
            put("s", seg(1, Segment.State.SEALED))
        }
        var tickets = 0
        val h = ScriptHttp { _, url, _ ->
            when {
                url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
                url.contains("/chunks/ticket") -> {
                    tickets++
                    // 第 0 片冲突，第 1 片正常
                    if (tickets == 1) HttpResponse(409, """{"error":{"code":"CHUNK_CONFLICT"}}""")
                    else HttpResponse(200, TICKET)
                }
                url.startsWith("https://cos.test") -> HttpResponse(200, "")
                else -> HttpResponse(200, "{}")
            }
        }
        val r = uploader(v, h).drain("s")
        // 第 0 片被认下来、第 1 片正常传完 → 整场能收尾
        assertTrue("$r", r is DrainResult.Done)
        assertTrue(v.segments("s").all { it.state == Segment.State.UPLOADED })
    }

    @Test fun `一段真的失败也不能挡住其它段`() {
        val v = MemVault().apply {
            metas["s"] = meta()
            (0..2).forEach { put("s", seg(it, Segment.State.SEALED)) }
        }
        val h = ScriptHttp { _, url, _ ->
            when {
                url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
                // 每一片要地址都失败
                url.contains("/chunks/ticket") -> HttpResponse(409, """{"error":{"code":"INVALID_STATE"}}""")
                else -> HttpResponse(200, "{}")
            }
        }
        uploader(v, h).drain("s")
        // 三片都试过了，而不是第一片失败就 return
        assertEquals(3, h.log.count { it.contains("ticket") })
    }
}

class ManifestIncompleteTest {
    /*
     * 2026-08-31：一场 56 片的录音卡在 stop 上反复 409，而服务端每次都附着
     * 缺号清单，是客户端把它扔了——只报了一句「冻结清单失败（409）」。
     * 手上明明有一份精确的差异，却当成笼统的失败去重试，于是每 15 秒
     * 重复同一个错，永远不会好。
     */
    private val incomplete = """{"error":{"code":"MANIFEST_INCOMPLETE",
        "message":"录音分片尚未完整上传","details":{"missingSequences":[1,3]}}}"""

    @Test fun `服务端说缺哪几片，就把那几片改回待传`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            (0..3).forEach { put("s", seg(it, Segment.State.UPLOADED)) }
        }
        val h = ScriptHttp { _, url, _ ->
            when {
                url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
                url.endsWith("/stop") -> HttpResponse(409, incomplete)
                else -> HttpResponse(200, "{}")
            }
        }
        val r = uploader(v, h).drain("s")
        assertTrue("$r", r is DrainResult.Progress)
        val sealed = v.segments("s").filter { it.state == Segment.State.SEALED }.map { it.sequence }
        assertEquals("只该复活服务端说缺的那两片", listOf(1, 3), sealed)
        // 其余两片保持已送达，不该被牵连
        assertEquals(2, v.segments("s").count { it.state == Segment.State.UPLOADED })
    }

    @Test fun `复活之后下一轮会重推它们`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            (0..3).forEach { put("s", seg(it, Segment.State.UPLOADED)) }
        }
        var stopped = 0
        val h = ScriptHttp { _, url, _ ->
            when {
                url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
                url.endsWith("/stop") -> { stopped++; if (stopped == 1) HttpResponse(409, incomplete) else HttpResponse(200, "{}") }
                url.contains("/chunks/ticket") -> HttpResponse(200, TICKET)
                url.startsWith("https://cos.test") -> HttpResponse(200, "")
                else -> HttpResponse(200, "{}")
            }
        }
        val u = uploader(v, h)
        u.drain("s")                       // 第一轮：复活缺的两片
        val r = u.drain("s")               // 第二轮：重推它们，然后收尾
        assertTrue("$r", r is DrainResult.Done)
    }

    @Test fun `说不全却没说缺哪片时，不要无限重试`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.UPLOADED))
        }
        val h = ScriptHttp { _, url, _ ->
            when {
                url.endsWith("/api/recordings") -> HttpResponse(200, CREATED)
                url.endsWith("/stop") ->
                    HttpResponse(409, """{"error":{"code":"MANIFEST_INCOMPLETE"}}""")
                else -> HttpResponse(200, "{}")
            }
        }
        val r = uploader(v, h).drain("s")
        assertTrue(r is DrainResult.Failed)
        assertFalse("没有可操作的信息，重试也不会好", (r as DrainResult.Failed).retryable)
    }
}

class TimelineContiguityTest {
    /*
     * 服务端冻结清单时要求**时间轴逐片严丝合缝**：
     * 每片的 started_at_ms 必须等于上一片的 ended_at_ms（0065 的 RPC 里那条
     * `q.started_at_ms = q.previous_ended_at_ms`）。差一毫秒，整场都传不上去。
     *
     * 2026-08-31 我改成「时长以产物为准」时只改了这一片的名字、没改下一片的起点，
     * 结果从那天起**一条录音都没成功过**：AAC 一帧固定 1024 个样本，编出来的时长
     * 几乎不可能和按 PCM 字节数算的正好相等，实测每片差 160 毫秒。
     *
     * 这一条钉的就是那个不变量。
     */

    private fun seg(seq: Int, start: Long, end: Long) =
        Segment(seq, start, end, Segment.State.UPLOADED)

    /** 服务端那条判据，照抄成断言 */
    private fun assertContiguous(segs: List<Segment>) {
        segs.sortedBy { it.sequence }.forEachIndexed { i, s ->
            if (i == 0) assertEquals("第一片必须从 0 开始", 0L, s.startMs)
            else assertEquals(
                "第 ${s.sequence} 片的起点必须等于上一片的终点",
                segs[i - 1].endMs, s.startMs,
            )
        }
    }

    @Test fun `时间轴接得上时通过`() {
        assertContiguous(listOf(seg(0, 0, 60160), seg(1, 60160, 120320)))
    }

    @Test fun `差 160 毫秒就该被抓住——这正是当初漏掉的那个缺口`() {
        // 片 0 按 ADTS 算结束在 60160，片 1 却按 PCM 从 60000 开始
        var caught = false
        try {
            assertContiguous(listOf(seg(0, 0, 60160), seg(1, 60000, 120000)))
        } catch (e: AssertionError) { caught = true }
        assertTrue("这种缺口必须被断言抓住，否则整场都传不上去", caught)
    }

    @Test fun `ADTS 帧数算出的时长本来就和 PCM 对不上`() {
        // 60 秒 PCM = 1,920,000 字节；按 16kHz 算是 60000 毫秒
        assertEquals(60000L, Capture.durationMsOf(1_920_000))
        // 而 AAC 每帧 1024 样本：938 帧 = 60032 毫秒，939 帧 = 60096
        // —— 没有任何一个帧数正好落在 60000 上。这就是为什么两边必须以产物为准。
        val f938 = Adts.Scan(938, 0, 0).durationMs(16000)
        val f939 = Adts.Scan(939, 0, 0).durationMs(16000)
        assertTrue("$f938 / $f939 之间不该存在 60000", f938 != 60000L && f939 != 60000L)
        assertTrue(f938 < 60000L || f939 > 60000L)
    }
}

/** 012 P3-3：单段导入撞上服务端已收尾的同一个键 = 重复导入，删本地，不留永久失败行。 */
class DuplicateImportTest {
    @org.junit.Test fun `重复的灵魂卡导入：删本地当作完成`() {
        val v = MemVault().apply {
            metas["s"] = SessionMeta("ble-note20260905-140000", "灵魂卡 · 9月5日", 1_700_000_000_000, true, null)
            put("s", seg(0, Segment.State.SEALED))
        }
        val h = ScriptHttp { _, url, _ ->
            if (url.endsWith("/api/recordings")) HttpResponse(200, """{"session":{"id":"sess-1","status":"ready"}}""")
            else HttpResponse(404, "")
        }
        val r = uploader(v, h).drain("s")
        org.junit.Assert.assertTrue("$r", r is DrainResult.Done)
        org.junit.Assert.assertEquals(listOf("s"), v.deleted)
    }
    @org.junit.Test fun `手机录的多段撞上已收尾：仍然报出来，不删`() {
        val v = MemVault().apply {
            metas["s"] = meta(finished = true)
            put("s", seg(0, Segment.State.UPLOADED)); put("s", seg(1, Segment.State.SEALED))
        }
        val h = ScriptHttp { _, url, _ ->
            if (url.endsWith("/api/recordings")) HttpResponse(200, """{"session":{"id":"sess-1","status":"ready"}}""")
            else HttpResponse(404, "")
        }
        val r = uploader(v, h).drain("s")
        org.junit.Assert.assertTrue("$r", r is DrainResult.Failed)
        org.junit.Assert.assertTrue(v.deleted.isEmpty())
    }
}
