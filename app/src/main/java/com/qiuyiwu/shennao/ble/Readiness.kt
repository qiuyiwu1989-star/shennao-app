package com.qiuyiwu.shennao.ble

/**
 * 开始之前，先把**能自己查的前提**全查一遍。
 *
 * ## 为什么要有这一层
 *
 * 2026-09-01 用户在手机蓝牙关着的情况下扫描，得到一个空列表，
 * 而界面给的提示是「确认录音笔开着」——**把他指向了完全错误的方向**。
 * 他为此关掉了 Mac 的蓝牙、反复唤醒录音笔，折腾了很久。
 *
 * 而 `adapter.isEnabled` 是一个随手就能查的布尔值。
 *
 * 判据：**凡是程序自己知道答案的事，绝不让用户去猜。**
 * 空列表是最含糊的输出——它可能意味着五件不同的事，而其中三件
 * 程序当场就能分辨出来。
 */
enum class Readiness {
    /** 这台设备没有 BLE */
    NO_BLE,
    /** 手机蓝牙没开 */
    BLUETOOTH_OFF,
    /** 权限没给 */
    NO_PERMISSION,
    /** 可以开始 */
    READY;

    /** 说清楚是什么问题、以及**该做什么**。只说问题不给出路等于没说。 */
    val message: String? get() = when (this) {
        NO_BLE -> "这台手机不支持低功耗蓝牙，没法连灵魂卡。"
        BLUETOOTH_OFF -> "手机蓝牙没打开。从屏幕顶上下拉，打开蓝牙再回来。"
        NO_PERMISSION -> null      // 权限有专门的申请流程，不在这里说
        READY -> null
    }

    /** 这一条能不能由用户在 App 里直接解决——决定要不要给按钮。 */
    val fixable: Boolean get() = this == BLUETOOTH_OFF || this == NO_PERMISSION
}
