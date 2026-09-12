package com.qiuyiwu.shennao.ble

/**
 * 附近哪些是我们的设备（邱 2026-09-12：「不要扫描到其他很多不是我们的蓝牙信号，只有我们的设备」）。
 *
 * 过滤放在**列表这一层**，不放在扫描器：按服务 UUID 过滤扫描在 2026-09-01 出过「扫不到」的事故——
 * 广播包只有 31 字节，厂商常常不把服务 UUID 放进去。所以照旧扫所有设备，显示时只留三种：
 *   · 广播里带了我们的服务 0xAE20
 *   · 名字像灵魂卡（CB08…、或厂商偶尔用的 CB-08 / cb08）
 *   · 这台手机连过的卡（地址在本机名册里），改过名之后广播名可能对不上
 * 其余收进「看全部附近设备」里，以防哪天固件把名字改了还有路可走。纯逻辑，JVM 可测。
 */
object OurDevices {
    private val namePattern = Regex("""^\s*cb[-_ ]?08""", RegexOption.IGNORE_CASE)
    fun looksLikeOurs(name: String, advertisesOurService: Boolean, knownAddresses: Set<String>, address: String): Boolean =
        advertisesOurService || namePattern.containsMatchIn(name) || address in knownAddresses

    fun <T> split(all: List<T>, isOurs: (T) -> Boolean): Pair<List<T>, List<T>> = all.partition(isOurs)
}
