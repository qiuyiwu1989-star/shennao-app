package com.qiuyiwu.shennao

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * 深脑客户端。
 *
 * 网络这一层抽成接口，是为了让「拿到什么就该怎么办」能脱离网络单测：
 * 401 要不要引导重登、服务端返回半截 JSON 会不会整屏白、
 * token 过期后能不能自动续 —— 这些判断全在下面这个类里，
 * 而它一行真实 socket 都不碰。
 *
 * Mac 端今天的教训是反过来的：把判断写在 Activity 里，
 * 就只能靠装到设备上才知道对不对。
 */

/** 一次 HTTP 往返的结果。只保留客户端真正会分支的东西。 */
data class HttpResponse(val status: Int, val body: String)

/** 网络出口。真实实现在 UrlHttp，测试里替换成假的。 */
interface Http {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
    ): HttpResponse

    /**
     * 传二进制。直传 COS 用。
     *
     * 不能复用上面那个：String 版发的时候按 UTF-8 编码，而音频是任意字节——
     * 任何一个不是合法 UTF-8 的字节序列都会在编码时被替换成 U+FFFD，
     * 传上去的音频将静默损坏，而 HTTP 会返回 200。
     */
    fun requestBytes(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): HttpResponse
}

/** 登录凭证。refreshToken 长期存，accessToken 用完即弃。 */
data class Credentials(val refreshToken: String, val orgId: String, val email: String)

/** 凭证怎么存。安卓上用 EncryptedSharedPreferences，测试里用内存。 */
interface CredentialStore {
    fun load(): Credentials?
    fun save(c: Credentials)
    fun clear()
}

class DeepBrainClient(
    private val http: Http,
    private val store: CredentialStore,
    private val apiBase: String,
    private val supabaseUrl: String,
    private val supabaseAnonKey: String,
) {
    /** 当前这次会话的 access token。进程内缓存，不落盘——它几十分钟就过期。 */
    private var accessToken: String? = null

    /**
     * 用邮箱密码换 refresh token。
     *
     * 只在第一次登录时走一次。之后一律用 refresh token 续 —— 密码不留在设备上。
     */
    fun signIn(email: String, password: String): ApiResult<Unit> {
        val r = http.request(
            "POST", "$supabaseUrl/auth/v1/token?grant_type=password",
            mapOf("apikey" to supabaseAnonKey, "Content-Type" to "application/json"),
            JSONObject(mapOf("email" to email, "password" to password)).toString(),
        )
        if (r.status == 400 || r.status == 401) return ApiResult.Failed("邮箱或密码不对")
        if (r.status >= 400) return ApiResult.Failed("登录失败（${r.status}）")
        val o = runCatching { JSONObject(r.body) }.getOrNull()
            ?: return ApiResult.Failed("登录应答看不懂")
        val refresh = o.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: return ApiResult.Failed("登录应答里没有 refresh_token")
        accessToken = o.optString("access_token").takeIf { it.isNotBlank() }

        // 组织 id 是原生端调接口的必备头。拿不到就不算登录成功——
        // 存一份「没有 org 的凭证」下去，后面每个请求都会 403，而且看不出原因。
        val org = fetchOrgId() ?: return ApiResult.Failed("这个账号还没有组织")
        store.save(Credentials(refresh, org, email))
        return ApiResult.Ok(Unit)
    }

    /**
     * 拿这个账号该用哪个组织。
     *
     * **2026-09-02 事故根因**：查询原来是 `select=org_id&limit=1`，
     * 没有排序。一个账号如果有两条 membership（这次是真实撞上的——
     * 一条真在用、一条是空的测试组织），PostgREST 在没有 order by 时
     * 不保证返回哪一行在前——多半时候физически 顺序稳定，但不是承诺，
     * 一次重装/重新登录就可能翻到另一条。翻过去之后**不会报任何错**：
     * 空组织一样能登录成功，一样能拿到 200，只是「今天」「会议」
     * 永远是空的——因为查询本身是对的，查的组织是错的。
     *
     * 加 `order=created_at.asc`：**最早那条 membership 几乎总是账号
     * 真正在用的那个**——后来的组织多半是测试、误建。这不是「多组织
     * 支持」的完整方案，只是把「随机选」换成「选一个大概率对的」，
     * 配合确定性，至少同一个账号每次登录选到的都是同一个组织。
     */
    private fun fetchOrgId(): String? {
        val t = accessToken ?: return null
        val r = http.request(
            "GET", "$supabaseUrl/rest/v1/memberships?select=org_id&order=created_at.asc&limit=1",
            mapOf("apikey" to supabaseAnonKey, "Authorization" to "Bearer $t"),
        )
        if (r.status >= 400) return null
        val arr = runCatching { org.json.JSONArray(r.body) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(0)?.optString("org_id")?.takeIf { it.isNotBlank() }
    }

    /** 用 refresh token 换一个新的 access token。 */
    private fun refresh(): Boolean {
        val c = store.load() ?: return false
        val r = http.request(
            "POST", "$supabaseUrl/auth/v1/token?grant_type=refresh_token",
            mapOf("apikey" to supabaseAnonKey, "Content-Type" to "application/json"),
            JSONObject(mapOf("refresh_token" to c.refreshToken)).toString(),
        )
        if (r.status >= 400) return false
        val o = runCatching { JSONObject(r.body) }.getOrNull() ?: return false
        accessToken = o.optString("access_token").takeIf { it.isNotBlank() }
        // refresh token 会轮换。服务端换了新的还用旧的，下次就登不上了。
        o.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
            store.save(c.copy(refreshToken = it))
        }
        return accessToken != null
    }

    /**
     * 取「今天该看什么」。
     *
     * 401 会自动续一次 token 再试。只续一次——续不上就是真的登录失效了，
     * 反复重试只会让用户对着转圈等，而正确的做法是把他送去登录页。
     */
    fun today(): ApiResult<Today> = get("/api/mobile/today") { TodayParser.parse(it) }

    /**
     * 取原始应答，成功时交给调用方存进离线缓存。
     *
     * 缓存存的是**原始 json 而不是解析后的对象**：解析规则会随版本变，
     * 存对象等于把当前这版的理解冻在磁盘上，升级之后旧缓存要么读不出来、
     * 要么读成错的。存原文则永远能用当前这版的解析器重读一遍。
     */
    fun rawTodayOrNull(): String? = raw("/api/mobile/today")
    fun rawSessionsOrNull(): String? = raw("/api/mobile/sessions")

    private fun raw(path: String): String? {
        val c = store.load() ?: return null
        if (accessToken == null && !refresh()) return null
        fun once() = http.request("GET", "$apiBase$path",
            mapOf("Authorization" to "Bearer ${accessToken ?: ""}", "x-deepbrain-org-id" to c.orgId))
        var r = runCatching { once() }.getOrNull() ?: return null
        if (r.status == 401) {
            if (!refresh()) return null
            r = runCatching { once() }.getOrNull() ?: return null
        }
        return if (r.status >= 400) null else r.body
    }

    /**
     * 设置本场专有名词（0080）。
     *
     * 组织底表是建会话时自动合上的，不用管；这里传的是**这场会特有**的词——
     * 底表里没有的人名、项目名、生造词。
     *
     * 录音中途也能改，但只影响**之后**的识别：已经出的字不会回头修正。
     * 界面必须把这句说出来，否则用户会以为加了词就能把前面的错字改过来。
     */
    fun setHotwordPins(sessionId: String, pins: List<String>): ApiResult<List<String>> {
        val c = store.load() ?: return ApiResult.Unauthorized
        if (accessToken == null && !refresh()) return ApiResult.Unauthorized
        fun once() = http.request(
            "PUT", "$apiBase/api/recordings/$sessionId/hotwords",
            mapOf(
                "Authorization" to "Bearer ${accessToken ?: ""}",
                "x-deepbrain-org-id" to c.orgId,
                "Content-Type" to "application/json",
            ),
            JSONObject().put("pins", org.json.JSONArray(pins)).toString(),
        )
        var r = once()
        if (r.status == 401) {
            if (!refresh()) return ApiResult.Unauthorized
            r = once()
        }
        if (r.status >= 400) return ApiResult.Failed("热词没存上（${r.status}）")
        // 服务端会把超限/重复的词丢掉并回报 accepted。显示真正生效的那些，
        // 不是用户输入的那些——否则用户会以为某个被丢掉的词在起作用。
        return runCatching {
            val a = JSONObject(r.body).optJSONArray("accepted")
            ApiResult.Ok((0 until (a?.length() ?: 0)).map { a!!.getString(it) })
        }.getOrElse { ApiResult.Ok(pins) }
    }

    /** 我录过的会走到哪了。401 同样自动续一次。 */
    fun sessions(): ApiResult<List<SessionCard>> =
        get("/api/mobile/sessions") { SessionsParser.parse(it) }

    /**
     * 生成一条分享链接。
     *
     * 和网页那条走同一张表、同一种 token——收到的人看到的页面完全一样。
     * 手机端默认**不附原文**：分享往往是随手发出去的，而原始转写里
     * 有别人说的每一句话。要附原文请到网页里显式开。
     */
    fun share(transcriptId: String): ApiResult<String> {
        val c = store.load() ?: return ApiResult.Unauthorized
        if (accessToken == null && !refresh()) return ApiResult.Unauthorized
        fun once() = http.request(
            "POST", "$apiBase/api/mobile/transcript/$transcriptId/share",
            mapOf(
                "Authorization" to "Bearer ${accessToken ?: ""}",
                "x-deepbrain-org-id" to c.orgId,
                "Content-Type" to "application/json",
            ), "{}",
        )
        var r = once()
        if (r.status == 401) { if (!refresh()) return ApiResult.Unauthorized; r = once() }
        // 409 = 还没分析完。说清楚，用户会去等，而不是反复点。
        if (r.status == 409) return ApiResult.Failed("这场会还没分析完，分析完才能分享")
        if (r.status >= 400) return ApiResult.Failed("生成链接失败（${r.status}）")
        return runCatching {
            val u = JSONObject(r.body).optString("url")
            if (u.isBlank()) ApiResult.Failed("没拿到链接") else ApiResult.Ok(u)
        }.getOrElse { ApiResult.Failed("应答看不懂") }
    }

    /**
     * 问深脑。**流式**——一个字一个字回调给界面。
     *
     * 不走 Http 接口（那个是「发一次、拿全文」），这里必须自己开连接：
     * agent 模式会翻好几篇文档，二十秒里界面得一直有东西在动。
     *
     * onEvent 在 IO 线程上被调用，调用方负责切回主线程。
     */
    fun ask(question: String, onEvent: (Ask.Event) -> Unit) {
        val c = store.load() ?: return onEvent(Ask.Event.Failed("请先登录"))
        if (accessToken == null && !refresh()) return onEvent(Ask.Event.Failed("登录失效了"))
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = (java.net.URL("$apiBase/api/mobile/ask").openConnection()
                as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                // **读超时要给足。** 智能体一轮可能十几秒不出字，
                // 30 秒的读超时会在它正常工作时把连接掐掉，
                // 而表现出来是「问一句就报网络错误」。
                readTimeout = 180_000
                setRequestProperty("Authorization", "Bearer ${accessToken ?: ""}")
                setRequestProperty("x-deepbrain-org-id", c.orgId)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                doOutput = true
            }
            conn.outputStream.use {
                it.write(JSONObject(mapOf("question" to question)).toString().toByteArray())
            }
            val code = conn.responseCode
            if (code >= 400) {
                val why = conn.errorStream?.bufferedReader()?.use { it.readText() }
                val msg = runCatching { JSONObject(why ?: "").optString("error") }.getOrNull()
                return onEvent(Ask.Event.Failed(msg?.takeIf { it.isNotBlank() } ?: "问不出来（$code）"))
            }
            conn.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val e = Ask.parseLine(line) ?: continue
                    onEvent(e)
                    if (e is Ask.Event.Done || e is Ask.Event.Failed) return
                }
            }
            // 流断在中途而没有 done：说出来。静默收尾会让半截答案
            // 看起来像是完整答案。
            onEvent(Ask.Event.Failed("答到一半断了，请重问一次"))
        } catch (e: Exception) {
            onEvent(Ask.Event.Failed(e.message ?: "网络错误"))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 换一张进网页的入场券。
     *
     * 一分钟有效、只能用一次。**token 不进 URL**——URL 会进日志、进历史、
     * 进用户随手的分享，而这里面装的是一整个会话。
     *
     * 券要连 refreshToken 一起交：网页那边写进 cookie 的是完整会话，
     * 只给 accessToken 的话用户在网页里待过一小时就被踢出来。
     */
    fun webTicket(): ApiResult<String> {
        val c = store.load() ?: return ApiResult.Unauthorized
        if (accessToken == null && !refresh()) return ApiResult.Unauthorized
        fun once() = http.request(
            "POST", "$apiBase/api/mobile/web-ticket",
            mapOf(
                "Authorization" to "Bearer ${accessToken ?: ""}",
                "x-deepbrain-org-id" to c.orgId,
                "Content-Type" to "application/json",
            ),
            JSONObject(mapOf("refreshToken" to c.refreshToken)).toString(),
        )
        var r = once()
        if (r.status == 401) { if (!refresh()) return ApiResult.Unauthorized; r = once() }
        if (r.status >= 400) return ApiResult.Failed("拿不到入场券（${r.status}）")
        return runCatching {
            val t = JSONObject(r.body).optString("ticket")
            if (t.isBlank()) ApiResult.Failed("没拿到入场券") else ApiResult.Ok(t)
        }.getOrElse { ApiResult.Failed("应答看不懂") }
    }

    /**
     * 手动跑一次分析。
     *
     * finalizer 会按「不到 5 分钟不自动分析」跳过短录音。那条门槛的意思是
     * **默认不花这个钱**，不是不许分析——所以手机上必须有这个出口，
     * 只是要用户自己按一下。
     *
     * 服务端用 409 表示「现在还不能排」（积分不够 / 已经在跑 / 是实时草稿），
     * 那不是「请求写错了」，理由直接端给用户看，别翻成一句「出错了」。
     */
    /*
     * ── 2026-09-05 接的四个新端点（服务端分支 agent/mobile-bff-phase2）──
     * 服务端没部署时是 404：界面按「没有这个功能」处理，不报错，不重试。
     */

    /** 用量：余额。服务端没这个端点时返回 Failed，「我的」页就不显示用量那一行。 */
    fun credits(): ApiResult<Credits> = get("/api/mobile/credits") { CreditsParser.parse(it) }

    /**
     * 给预测一个说法。verdict = borne_out / refuted / partial / too_early。
     * 返回 true 表示这次是顺延（too_early），预测没死、到期还回来——
     * 界面要把它和「已裁定」分开说，否则用户以为记完了，那条预测就再也不知道在不在了。
     */
    fun settlePrediction(id: String, verdict: String): ApiResult<Boolean> {
        val r = postJson("/api/mobile/predictions/$id", JSONObject().put("verdict", verdict).toString())
        if (r !is ApiResult.Ok) return r as ApiResult<Boolean>
        return ApiResult.Ok(runCatching { JSONObject(r.value).optBoolean("deferred", false) }.getOrDefault(false))
    }

    fun speakers(transcriptId: String): ApiResult<SpeakersPage> =
        get("/api/mobile/transcript/$transcriptId/speakers") { SpeakersParser.parse(it) }

    /** 认领。name 为 null 表示「听不出来，跳过」——记为未确认、不命名，不是不发。 */
    fun claimSpeaker(transcriptId: String, speakerId: String, name: String?): ApiResult<Unit> {
        val one = JSONObject().put("id", speakerId)
        if (name == null) one.put("skip", true) else one.put("name", name)
        val body = JSONObject().put("speakers", org.json.JSONArray().put(one)).toString()
        val r = postJson("/api/mobile/transcript/$transcriptId/speakers", body)
        return if (r is ApiResult.Ok) ApiResult.Ok(Unit) else r as ApiResult<Unit>
    }

    /** 对一条判断说「对」「不对」「别再给我看」。每人每条只留最新一份，重复点就是覆盖。 */
    fun feedback(atomId: String, verdict: String): ApiResult<Unit> {
        val r = postJson("/api/mobile/insights/$atomId/feedback", JSONObject().put("verdict", verdict).toString())
        return if (r is ApiResult.Ok) ApiResult.Ok(Unit) else r as ApiResult<Unit>
    }

    /** 我名下的灵魂卡。老服务端 404 → Failed，界面按「没这功能」处理。 */
    fun myCards(): ApiResult<CardsPage> = get("/api/mobile/card") { CardsParser.parse(it) }

    /** 把连上的这张卡绑到我名下。幂等：自己重复绑不重复发权益；别人的卡 409。 */
    fun bindCard(address: String): ApiResult<BoundCard> {
        val r = postJson("/api/mobile/card", JSONObject().put("address", address).toString())
        return when (r) {
            is ApiResult.Ok -> runCatching { ApiResult.Ok(CardsParser.card(JSONObject(r.value).getJSONObject("card"))) }
                .getOrElse { ApiResult.Failed("回包读不懂") }
            else -> r as ApiResult<BoundCard>
        }
    }

    /** POST JSON，401 刷新重试一次，>=400 把服务端 error.message 或 error 原样带回。 */
    private fun postJson(path: String, body: String): ApiResult<String> {
        val c = store.load() ?: return ApiResult.Unauthorized
        if (accessToken == null && !refresh()) return ApiResult.Unauthorized
        fun once() = http.request(
            "POST", "$apiBase$path",
            mapOf("Authorization" to "Bearer ${accessToken ?: ""}", "x-deepbrain-org-id" to c.orgId,
                  "Content-Type" to "application/json"), body,
        )
        var r = once()
        if (r.status == 401) { if (!refresh()) return ApiResult.Unauthorized; r = once() }
        if (r.status >= 400) {
            val why = runCatching {
                val e = JSONObject(r.body).opt("error")
                if (e is JSONObject) e.optString("message") else e?.toString()
            }.getOrNull()
            return ApiResult.Failed(why?.takeIf { it.isNotBlank() && it != "null" } ?: "没记上（${r.status}）")
        }
        return ApiResult.Ok(r.body)
    }

    fun analyze(transcriptId: String): ApiResult<Unit> {
        val c = store.load() ?: return ApiResult.Unauthorized
        if (accessToken == null && !refresh()) return ApiResult.Unauthorized
        fun once() = http.request(
            "POST", "$apiBase/api/mobile/transcript/$transcriptId/analyze",
            mapOf(
                "Authorization" to "Bearer ${accessToken ?: ""}",
                "x-deepbrain-org-id" to c.orgId,
                "Content-Type" to "application/json",
            ), "{}",
        )
        var r = once()
        if (r.status == 401) { if (!refresh()) return ApiResult.Unauthorized; r = once() }
        if (r.status >= 400) {
            val why = runCatching { JSONObject(r.body).optString("error") }.getOrNull()
            return ApiResult.Failed(why?.takeIf { it.isNotBlank() } ?: "排不上（${r.status}）")
        }
        return ApiResult.Ok(Unit)
    }

    /**
     * 删掉一条录音会话。
     *
     * 给「救不回来」的那些用：一条永远失败的录音挂在列表上，
     * 用户每次打开都要重新判断一次「这个要不要管」。
     */
    fun deleteRecording(sessionId: String): ApiResult<Unit> {
        val c = store.load() ?: return ApiResult.Unauthorized
        if (accessToken == null && !refresh()) return ApiResult.Unauthorized
        fun once() = http.request(
            "DELETE", "$apiBase/api/recordings/$sessionId",
            mapOf("Authorization" to "Bearer ${accessToken ?: ""}", "x-deepbrain-org-id" to c.orgId),
        )
        var r = once()
        if (r.status == 401) { if (!refresh()) return ApiResult.Unauthorized; r = once() }
        // 404 = 已经不在了，也算达成目的
        return if (r.status < 400 || r.status == 404) ApiResult.Ok(Unit)
               else ApiResult.Failed("删不掉（${r.status}）")
    }

    /** 搜索。查询串要转义，否则用户搜的东西里带 & 会把参数截断。 */
    fun search(q: String): ApiResult<List<Hit>> =
        get("/api/mobile/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")) { SearchParser.parse(it) }

    /** 这个人说过什么、兑现了多少。 */
    fun person(id: String): ApiResult<Person> =
        get("/api/mobile/people/$id") { PersonParser.parse(it) ?: throw IllegalStateException("看不懂") }

    /**
     * 给一条承诺落账。
     *
     * **系统不裁定任何人**——兑现和取消都需要账本以外的信息，只由人落。
     * 409 不是错误，是「已经落过账了」：报成失败会让用户以为网络有问题然后反复点。
     */
    fun settleCommitment(id: String, action: String): ApiResult<String> {
        val c = store.load() ?: return ApiResult.Unauthorized
        if (accessToken == null && !refresh()) return ApiResult.Unauthorized
        fun once() = http.request(
            "POST", "$apiBase/api/mobile/commitments/$id",
            mapOf(
                "Authorization" to "Bearer ${accessToken ?: ""}",
                "x-deepbrain-org-id" to c.orgId,
                "Content-Type" to "application/json",
            ),
            JSONObject().put("action", action).toString(),
        )
        var r = once()
        if (r.status == 401) {
            if (!refresh()) return ApiResult.Unauthorized
            r = once()
        }
        if (r.status == 409) return ApiResult.Failed("这条已经落过账了")
        if (r.status >= 400) return ApiResult.Failed("落账失败（${r.status}）")
        return runCatching { ApiResult.Ok(JSONObject(r.body).optString("status")) }
            .getOrElse { ApiResult.Ok(action) }
    }

    /** 这场会讲了什么。 */
    fun meeting(transcriptId: String): ApiResult<Meeting> =
        get("/api/mobile/transcript/$transcriptId") {
            SessionsParser.parseMeeting(it) ?: throw IllegalStateException("应答看不懂")
        }

    /**
     * 取数的公共壳：续 token、401 重试一次、应答解析失败不崩。
     *
     * 抽出来是因为 today() 那套逻辑要被逐字重复三遍，而「只续一次」
     * 这种判据一旦分叉，某个面就会在 token 过期时开始无限转圈。
     */
    private fun <T> get(path: String, parse: (String) -> T): ApiResult<T> {
        val c = store.load() ?: return ApiResult.Unauthorized
        if (accessToken == null && !refresh()) return ApiResult.Unauthorized
        fun once() = http.request(
            "GET", "$apiBase$path",
            mapOf("Authorization" to "Bearer ${accessToken ?: ""}", "x-deepbrain-org-id" to c.orgId),
        )
        var r = once()
        if (r.status == 401) {
            if (!refresh()) return ApiResult.Unauthorized
            r = once()
        }
        return when {
            r.status == 401 -> ApiResult.Unauthorized
            r.status >= 400 -> ApiResult.Failed("取数失败（${r.status}）")
            else -> runCatching { ApiResult.Ok(parse(r.body)) }
                .getOrElse { ApiResult.Failed("应答看不懂") }
        }
    }

    /**
     * 拿一个当前可用的 access token，必要时续一次。
     *
     * 给录音上传器用。它跑在服务里、跟界面不共享调用栈，但必须共享同一份
     * token 缓存——各续各的会让 refresh token 轮换互相作废。
     */
    @Synchronized
    fun validAccessToken(force: Boolean = false): String? {
        // force=true 是「刚才那个被服务端拒了」。必须先丢掉旧的再续——
        // 不丢的话下面那个判空会直接把已经作废的 token 又还回去。
        if (force) accessToken = null
        if (accessToken == null) refresh()
        return accessToken
    }

    fun orgId(): String? = store.load()?.orgId

    fun signedInEmail(): String? = store.load()?.email
    fun signOut() { accessToken = null; store.clear() }
}

/** 真实网络出口。用 HttpURLConnection，不引第三方库——这一层没有值得依赖的复杂度。 */
class UrlHttp(private val timeoutMs: Int = 30_000) : Http {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse = send(method, url, headers, body?.toByteArray())

    override fun requestBytes(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): HttpResponse = send(method, url, headers, body)

    private fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true }
        }
        return try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            // 4xx/5xx 的正文在 errorStream 里。只读 inputStream 的话，
            // 服务端辛辛苦苦返回的错误说明会被整个丢掉，界面只剩一个数字。
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            HttpResponse(code, text)
        } catch (e: Exception) {
            HttpResponse(0, e.message ?: "网络错误")
        } finally {
            conn.disconnect()
        }
    }
}
