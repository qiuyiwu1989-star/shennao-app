package com.qiuyiwu.shennao.ble

import android.Manifest
import android.os.Build

/**
 * 扫描蓝牙要哪几个权限。
 *
 * **安卓 12（API 31）把蓝牙权限拆了**，前后两套完全不同：
 *   31 及以上：BLUETOOTH_SCAN + BLUETOOTH_CONNECT
 *   31 以下  ：还要 ACCESS_FINE_LOCATION —— 系统认为蓝牙扫描能推断位置
 *
 * 少了定位那条的后果特别坏：**扫描不报错，只是永远返回空列表**，
 * 看起来就像「附近没有录音笔」。用户会以为是设备的问题，反复开关蓝牙。
 *
 * 抽成纯函数是为了能测——这段判断一旦写错，只有拿一台旧手机才能发现。
 */
object BlePermissions {
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** 被拒之后该说什么。说「请开启蓝牙权限」在旧系统上是错的——那里要的是定位。 */
    fun deniedHint(sdk: Int = Build.VERSION.SDK_INT): String =
        if (sdk >= 31) "需要「附近的设备」权限才能找到录音笔。到系统设置里给深脑开一下。"
        else "安卓 11 及以下，系统要求有定位权限才允许扫描蓝牙——" +
             "深脑不会用它定位，但没有它就扫不到设备。"
}
