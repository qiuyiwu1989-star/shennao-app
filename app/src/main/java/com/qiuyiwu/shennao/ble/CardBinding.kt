package com.qiuyiwu.shennao.ble

import android.content.Context

/*
 * 这张卡上次同步进的是哪个账号（spec 019）。
 *
 * 2026-09-03 的事故：重新登录时输错账号，三份录音全部同步进了一个空的测试账号，人工查了几小时。
 * 这里记 卡地址 → (orgId, email)。连上卡、开始同步之前先对一下：不一致就拦住，把那句重话给人看。
 * 服务端那边有同样的判断（别的账号名下还开着 → 409）；这里是本地那一层——没网、老服务端也能拦。
 */
class CardBinding(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("card_binding", Context.MODE_PRIVATE)

    fun boundOrgId(address: String): String? = prefs.getString("org:$address", null)
    fun lastEmail(address: String): String? = prefs.getString("email:$address", null)

    fun bind(address: String, orgId: String, email: String) {
        prefs.edit().putString("org:$address", orgId).putString("email:$address", email).apply()
    }

    fun forget(address: String) { prefs.edit().remove("org:$address").remove("email:$address").apply() }

    companion object {
        /** 纯判据：本地记过且和当前账号不一致才算不匹配。没记过 = 第一次 = 不拦。 */
        fun mismatch(boundOrgId: String?, currentOrgId: String?): Boolean =
            boundOrgId != null && currentOrgId != null && boundOrgId != currentOrgId
    }
}
