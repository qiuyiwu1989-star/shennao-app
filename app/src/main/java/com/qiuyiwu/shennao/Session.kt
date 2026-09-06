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

/**
 * 凭证落盘。
 *
 * refresh token 是长期钥匙——它能一直换出新的 access token，作废之前
 * 谁拿到谁就是这个账号。明文躺在 SharedPreferences 里，任何能读到应用目录的
 * 途径（root、某些厂商的备份、调试桥）都能直接拿走。所以用
 * EncryptedSharedPreferences：密钥在系统 keystore 里，拷走文件也解不开。
 *
 * 旧的明文库要迁移过来并**删掉**，不能只是不再写它——留着等于加密了个寂寞。
 * 迁完的用户不用重新登录。
 *
 * 加密库起不来时退回明文（某些设备的 keystore 会抽风）。宁可能用，
 * 也不要让人连登录都登不上；但退回时必须留下痕迹，不能假装一切正常。
 */
class PrefsStore(ctx: Context) : CredentialStore {
    private val app = ctx.applicationContext
    private var encrypted = true
    /*
     * 懒开：EncryptedSharedPreferences 要碰系统 keystore，冷启动主线程开它要 100–300 ms（012 P2-2）。
     * 第一次 load() 才开，而第一次 load() 在 IO 上（MainActivity.load）。
     */
    private val p: android.content.SharedPreferences by lazy {
        val e = openEncrypted()
        if (e != null) { migrateFromPlain(e); e }
        else {
            encrypted = false
            android.util.Log.w("shennao", "加密存储起不来，凭证退回明文")
            app.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)
        }
    }

    private fun openEncrypted(): android.content.SharedPreferences? = runCatching {
        val key = androidx.security.crypto.MasterKey.Builder(app)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            app, SECURE, key,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    /** 把旧的明文凭证搬进来再删掉。留着旧文件 = 加密了个寂寞。 */
    private fun migrateFromPlain(p: android.content.SharedPreferences) {
        val old = app.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)
        val rt = old.getString("refresh", null) ?: run { old.edit().clear().apply(); return }
        val org = old.getString("org", null)
        if (org != null && p.getString("refresh", null) == null) {
            p.edit().putString("refresh", rt).putString("org", org)
                .putString("email", old.getString("email", "") ?: "").apply()
        }
        // 明文那份必须消失，无论有没有搬成功——搬不成功用户重登一次，
        // 而留着一份明文长期钥匙的代价大得多。
        old.edit().clear().apply()
        app.deleteSharedPreferences(PLAIN)
    }
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

    private companion object {
        const val PLAIN = "shennao"          // 旧的明文库，只用来迁移和删除
        const val SECURE = "shennao-secure"
    }
}

object Session {
    @Volatile private var client: DeepBrainClient? = null

    /** 只给调试包的夹具模式用（Demo.kt）：换成一个不联网的假客户端。 */
    fun installForDemo(c: DeepBrainClient) { client = c }

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
