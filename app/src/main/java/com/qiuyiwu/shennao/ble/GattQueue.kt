package com.qiuyiwu.shennao.ble

/**
 * GATT 操作串行队列。
 *
 * ## 为什么必须有
 *
 * Android 的 BluetoothGatt **一次只受理一个操作**。第二个 `writeCharacteristic`
 * 在前一个的回调到达之前发出去，会直接返回 false 或者**被静默丢弃**——
 * 而后者最要命：代码看起来一切正常，设备那边什么都没收到。
 *
 * 连接建立阶段尤其密集：开两路通知要写两个 CCCD 描述符，再加一次 MTU 协商，
 * 三个操作挤在一起发，多半只有第一个真的生效。
 *
 * ## 为什么单独抽出来
 *
 * 这是纯逻辑，能在 JVM 上验；而它管的是「有没有真的发出去」这件事，
 * 错了就是「设备连上了但不响应」——最难查的那一类。
 */
class GattQueue(private val execute: (Op) -> Boolean) {

    /** 一个待办操作。`label` 只用于诊断——出问题时要能说清卡在哪一步。 */
    data class Op(val label: String, val run: () -> Boolean)

    private val pending = ArrayDeque<Op>()
    private var inFlight: Op? = null

    /** 正在跑的那个是什么。卡住时它就是答案。 */
    val current: String? get() = inFlight?.label
    val waiting: Int get() = pending.size

    fun enqueue(label: String, run: () -> Boolean) {
        pending.addLast(Op(label, run))
        pump()
    }

    /**
     * 上一个操作的回调到了。**无论成功失败都要调**——
     * 只在成功时调的话，一次失败就会让队列永远卡住，
     * 而表现是「用了一会儿之后设备就不响应了」。
     */
    fun onComplete() {
        inFlight = null
        pump()
    }

    fun reset() {
        pending.clear()
        inFlight = null
    }

    private fun pump() {
        if (inFlight != null) return
        val next = pending.removeFirstOrNull() ?: return
        inFlight = next
        if (!execute(next)) {
            // 发都没发出去：不能停在这儿等一个永远不会来的回调
            inFlight = null
            pump()
        }
    }
}
