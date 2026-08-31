package com.qiuyiwu.shennao

import org.json.JSONObject

/*
 * 版本更新。
 *
 * APK 走应用商店之外分发，没有自动更新——用户装完就永远停在那个版本，
 * 除非应用自己告诉他有新版。深脑这半个月发了七版，每一版都在修
 * 「录了但传不上去」这类问题，而一个停在 v0.3 的用户永远不会知道。
 *
 * 判据用 versionCode 不用 versionName：名字是给人看的，会有 0.6.1 这种，
 * 字符串比较会把 0.10.0 判成比 0.9.0 旧。
 */

data class Release(
    val versionName: String,
    val versionCode: Int,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
)

sealed class UpdateState {
    /** 已经是最新的 */
    object UpToDate : UpdateState()
    data class Available(val release: Release) : UpdateState()
    /** 查不到。要和「已是最新」分开说——网络不通不等于没有新版 */
    data class Unknown(val reason: String) : UpdateState()
}

object Update {
    const val MANIFEST = "https://shennao.zaowuyun.com/downloads/latest.json"

    /** 解析清单。任何一处不对都返回 null，绝不返回半个 Release。 */
    fun parse(body: String): Release? = runCatching {
        val o = JSONObject(body)
        val code = o.optInt("versionCode", 0)
        val url = o.optString("url")
        val name = o.optString("versionName")
        if (code <= 0 || url.isBlank() || name.isBlank()) return null
        Release(name, code, o.optLong("size", 0), o.optString("sha256"), url)
    }.getOrNull()

    /**
     * 比大小。相等或更旧都算「已是最新」——
     * 服务端回滚过一版时，不该反过来劝用户装个更旧的。
     */
    fun compare(current: Int, remote: Release?): UpdateState = when {
        remote == null -> UpdateState.Unknown("清单读不出来")
        remote.versionCode > current -> UpdateState.Available(remote)
        else -> UpdateState.UpToDate
    }

    fun check(http: Http, currentCode: Int): UpdateState {
        val r = runCatching { http.request("GET", MANIFEST, emptyMap()) }.getOrNull()
            ?: return UpdateState.Unknown("网络不通")
        if (r.status >= 400) return UpdateState.Unknown("清单取不到（${r.status}）")
        return compare(currentCode, parse(r.body))
    }
}
