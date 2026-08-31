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

    private fun fetchOrgId(): String? {
        val t = accessToken ?: return null
        val r = http.request(
            "GET", "$supabaseUrl/rest/v1/memberships?select=org_id&limit=1",
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
    fun today(): ApiResult<Today> {
        val c = store.load() ?: return ApiResult.Unauthorized
        if (accessToken == null && !refresh()) return ApiResult.Unauthorized

        var r = call(c)
        if (r.status == 401) {
            if (!refresh()) return ApiResult.Unauthorized
            r = call(c)
        }
        return when {
            r.status == 401 -> ApiResult.Unauthorized
            r.status >= 400 -> ApiResult.Failed("取数失败（${r.status}）")
            else -> runCatching { ApiResult.Ok(TodayParser.parse(r.body)) }
                .getOrElse { ApiResult.Failed("应答看不懂") }
        }
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

    private fun call(c: Credentials) = http.request(
        "GET", "$apiBase/api/mobile/today",
        mapOf(
            "Authorization" to "Bearer ${accessToken ?: ""}",
            "x-deepbrain-org-id" to c.orgId,
        ),
    )

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
