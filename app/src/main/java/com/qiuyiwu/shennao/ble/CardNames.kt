package com.qiuyiwu.shennao.ble

import android.content.Context

/**
 * 给每张灵魂卡起的名字（「随身那张」「办公室那张」）。
 *
 * 按蓝牙地址记，本机保存。一人可多张，列表里只有 SN 认不出哪张是哪张——
 * 名字是用户自己起的，比我们编的「灵魂卡 #2」有意义。
 */
class CardNames(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("card_names", Context.MODE_PRIVATE)
    private companion object { const val KEY_SEEN = "_seen" }

    fun nameOf(address: String): String? = prefs.getString(address, null)?.takeIf { it.isNotBlank() }

    fun rename(address: String, name: String) {
        val n = name.trim().take(20)
        if (n.isEmpty()) prefs.edit().remove(address).apply()
        else prefs.edit().putString(address, n).apply()
    }

    /**
     * 连过的卡都记下来。**卡不在附近是常态**——列表里要能看到「办公室那张」，
     * 哪怕它此刻扫不到；否则一人多张这件事在界面上不成立。
     */
    fun markSeen(address: String, advertised: String) {
        val known = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()
        known += address
        prefs.edit().putStringSet(KEY_SEEN, known).putString("adv:$address", advertised).apply()
    }

    /** 见过的卡：地址 + 显示名，起过名的排前面。 */
    fun known(): List<Pair<String, String>> =
        prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
            .map { addr -> addr to displayName(addr, prefs.getString("adv:$addr", null) ?: "灵魂卡") }
            .sortedBy { (addr, _) -> if (nameOf(addr) != null) 0 else 1 }

    fun forget(address: String) {
        val known = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()
        known -= address
        prefs.edit().putStringSet(KEY_SEEN, known).remove(address).remove("adv:$address").apply()
    }

    /** 显示名：起过名就用名字，没有就用设备广播名 + 地址尾号。 */
    fun displayName(address: String, advertised: String): String =
        nameOf(address) ?: "$advertised · ${address.takeLast(5).replace(":", "")}"
}
