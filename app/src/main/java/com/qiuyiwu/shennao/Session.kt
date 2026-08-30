package com.qiuyiwu.shennao

import android.content.Context

/*
 * 全应用共用一个登录态。
 *
 * 为什么要有这一层：录音服务和界面是两个组件，各建一个 client 的话，
 * 它们会各自缓存一份 access token、各自去续期。续期时 refresh token 会轮换，
 * 两边就会互相把对方的凭证作废——表现是「用着用着突然要重新登录」，
 * 而且只在开着录音时出现，极难复现。
 */

/** 凭证存在 SharedPreferences。第一版先这样，上真机验通之后换 EncryptedSharedPreferences。 */
class PrefsStore(ctx: Context) : CredentialStore {
    private val p = ctx.applicationContext.getSharedPreferences("shennao", Context.MODE_PRIVATE)
    override fun load(): Credentials? {
        val rt = p.getString("refresh", null) ?: return null
        val org = p.getString("org", null) ?: return null
        return Credentials(rt, org, p.getString("email", "") ?: "")
    }
    override fun save(c: Credentials) {
        p.edit().putString("refresh", c.refreshToken)
            .putString("org", c.orgId).putString("email", c.email).apply()
    }
    override fun clear() { p.edit().clear().apply() }
}

object Session {
    @Volatile private var client: DeepBrainClient? = null

    fun client(ctx: Context): DeepBrainClient = client ?: synchronized(this) {
        client ?: DeepBrainClient(
            http = UrlHttp(),
            store = PrefsStore(ctx),
            apiBase = BuildConfig.API_BASE,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        ).also { client = it }
    }

    /** 上传器要用的：一对（access token, org id）。没登录返回 null。 */
    fun authFor(ctx: Context, force: Boolean = false): Pair<String, String>? {
        val c = client(ctx)
        val token = c.validAccessToken(force) ?: return null
        val org = c.orgId() ?: return null
        return token to org
    }
}
