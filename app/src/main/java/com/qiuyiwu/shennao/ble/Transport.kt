package com.qiuyiwu.shennao.ble

/*
 * 传输层的边界。
 *
 * 定在这里是为了让「协议/传输状态机」和「Android 的 BluetoothGatt」两边
 * 能各自独立地写和测。上面那一半（要不要重发、断点从哪续、什么算传完）
 * 是纯逻辑，必须能在 JVM 上跑；下面那一半只有真机能验。
 *
 * 今天的教训：能在没有设备时钉死的部分，先钉死。
 */

/** 一台被发现的设备。 */
data class BleDevice(val id: String, val name: String, val rssi: Int)

/** 连接状态。界面照着念，不许自己编。 */
enum class BleState { IDLE, SCANNING, CONNECTING, READY, DISCONNECTED, FAILED }

/**
 * 底层通道。真实实现走 BluetoothGatt，测试里换成假的。
 *
 * `write` 必须**整包一次写完**——CB08 的下载请求是 36B，拆包设备会把
 * 文件名解析错。所以这一层不做分片，分片是调用方的事（而调用方不该分）。
 */
interface BleTransport {
    val state: BleState
    /** 往 AE21 写一帧。返回 false 表示没写出去（未连接、GATT 忙）。 */
    fun write(frame: ByteArray): Boolean
    /** 注册通知回调。AE22（控制/文件/音频）与 AE23（按键）各一路。 */
    fun onNotify(char: String, cb: (ByteArray) -> Unit)
    fun disconnect()
}
