package com.qiuyiwu.shennao.record

/*
 * 录音被打断之后怎么办。
 *
 * 打断是常态不是异常：来电、别的应用抢麦克风、系统临时回收音频资源。
 * v0.3 在这里直接退出录音线程，而服务的 recording 标记还是 true——
 * 界面继续显示「正在录音」、通知栏计时器停在原地，其实一个字都没在录。
 * 开完会才发现，那时已经没法补救。
 *
 * v0.4 的规矩是两条，顺序不能反：
 *   1. **先说真话**：一旦读不到音频，状态立刻变成「已中断」，不许继续假装。
 *   2. **再自己救**：后台按退避节奏重开麦克风；开回来就接着录进同一场。
 *
 * 续录的时间轴是**接着走的**，不留空洞：中断那三分钟本来就不在音频里，
 * 给它留一段空白只会让后面所有分片的时间都往后错。
 */

/** 重开麦克风的退避节奏。纯逻辑，可单测——真机上不好复现，但判据必须是对的。 */
object Interruption {

    /** 前几次快速试（多数打断只有几秒），之后拉长到 5 秒一次。 */
    fun delayMsFor(attempt: Int): Long = when {
        attempt <= 0 -> 0L
        attempt <= 3 -> 500L
        attempt <= 10 -> 2_000L
        else -> 5_000L
    }

    /**
     * 试到什么时候放弃。
     *
     * 10 分钟。比一通电话长得多，短到不会让一台已经录不了的手机
     * 整晚亮着「正在恢复」——那种假状态比明说「停了」更糟。
     */
    const val GIVE_UP_MS = 10 * 60 * 1000L

    /** 累计已经等了多久。给「要不要放弃」用。 */
    fun elapsedAfter(attempts: Int): Long =
        (1..attempts).sumOf { delayMsFor(it) }

    fun shouldGiveUp(attempts: Int): Boolean = elapsedAfter(attempts) >= GIVE_UP_MS
}

/** 录音此刻的真实状态。界面照着念，不许自己编。 */
enum class RecordState {
    /** 没在录 */
    IDLE,
    /** 正在录 */
    RECORDING,
    /** 麦克风被抢走了，正在试着抢回来。已经录到的都安全落盘了 */
    INTERRUPTED,
    /** 试了 10 分钟没抢回来，已经停了。录到的部分照常上传 */
    GAVE_UP,
    /** 写盘失败（多半是存储满了）。不重试：再开麦克风也写不进去。012 P0-7 */
    DISK_FULL,
}
