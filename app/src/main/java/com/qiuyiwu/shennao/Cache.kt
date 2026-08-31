package com.qiuyiwu.shennao

import java.io.File

/*
 * 离线可读。
 *
 * 地铁里、电梯里、信号差的会议室——打开 App 看到一个转圈或者「取数失败」，
 * 等于这个东西在最需要它的场合不能用。而「今天有几条到期承诺」这种信息
 * 隔几分钟不刷新完全无所谓，没有理由因为没网就一个字都不显示。
 *
 * 所以：每次取数成功就把原始应答存下来；取数失败就拿上次的显示，
 * 并且**明确标出这是什么时候的**。不标时间的缓存比没有缓存更糟——
 * 用户会拿三天前的数据当今天的。
 */

data class Cached(val body: String, val savedAt: Long)

class Cache(private val dir: File) {

    fun save(key: String, body: String) {
        runCatching {
            dir.mkdirs()
            // 先写临时文件再改名：直接覆写时掉电会留下半截 json，
            // 而半截 json 解出来是空列表——看起来就像「今天没有事」。
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(body)
            tmp.renameTo(File(dir, "$key.json"))
        }
    }

    fun load(key: String): Cached? = runCatching {
        val f = File(dir, "$key.json")
        if (!f.isFile || f.length() == 0L) return null
        Cached(f.readText(), f.lastModified())
    }.getOrNull()

    companion object {
        const val TODAY = "today"
        const val SESSIONS = "sessions"

        /**
         * 「这是什么时候的」怎么说。
         *
         * 一分钟内不说——刚取的就是现在的，标一个时间只会让人以为它旧了。
         */
        fun staleLabel(savedAt: Long, now: Long): String? {
            val min = (now - savedAt) / 60_000
            return when {
                min < 1 -> null
                min < 60 -> "离线 · $min 分钟前的"
                min < 60 * 24 -> "离线 · ${min / 60} 小时前的"
                else -> "离线 · ${min / 1440} 天前的"
            }
        }
    }
}
