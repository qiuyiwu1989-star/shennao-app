package com.qiuyiwu.shennao.ble

import android.content.Context

/**
 * 「这台录音笔的这个文件，已经导进过手机了」——本地记的一份账。
 *
 * **为什么需要它。** 服务端的幂等键（`ble-<文件名>`，见 Ingest.kt）挡得住
 * 「重复导入同一个文件」造成的重复会话，但挡不住浪费：BLE 只有 27 KB/s，
 * 一份已经导过的文件重新走一遍下载，纯粹是白等几分钟，换来一个服务端
 * 直接扔掉的重复请求。自动同步要「跳过已经导过的」，必须靠这份本地账——
 * 服务端幂等键只在**上传完之后**才起作用，下载之前它什么都不知道。
 *
 * **按设备 + 文件名记，不是只按文件名。** 两支不同的录音笔完全可能各自
 * 生成一个 `note20260901-1400.opus`——这在协议层就允许，见 Ingest.kt
 * 的幂等键注释。只按文件名记的话，导过 A 笔的这个文件，就会让 B 笔的
 * 同名文件被误判成「已经导过」，白白漏掉一份真实录音。
 */
class ImportedRegistry(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ble_imported", Context.MODE_PRIVATE)

    private fun key(deviceAddress: String, fileBase: String) = "$deviceAddress|$fileBase"

    fun isImported(deviceAddress: String, fileBase: String): Boolean =
        prefs.getBoolean(key(deviceAddress, fileBase), false)

    fun markImported(deviceAddress: String, fileBase: String) {
        prefs.edit().putBoolean(key(deviceAddress, fileBase), true).apply()
    }
}
