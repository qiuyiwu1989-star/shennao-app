package com.qiuyiwu.shennao.record

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 从一段 16 位 PCM 算出 0..1 的音量。
 *
 * 用均方根而不是峰值：峰值会被一次咳嗽、一声桌子响顶到满格，
 * 然后声波看起来一直在满幅跳动，等于什么都没说。均方根反映的是
 * 这段时间里的实际能量，说话和安静能分得开。
 *
 * 再套一个平方根做感知压缩：人耳对响度是对数感知的，
 * 线性映射会让正常说话只占到条形高度的十分之一，看起来像没在录。
 */
object Level {
    /** 16 位有符号的满幅 */
    private const val FULL = 32768.0

    fun of(buf: ByteArray, n: Int): Float {
        if (n < 2) return 0f
        var sum = 0.0
        var i = 0
        // 每 8 个采样取一个就够画声波了——逐样本算会让录音线程多做十倍无用功，
        // 而那条线程一慢，丢的是音频本身。
        var count = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += v.toDouble() * v
            count++
            i += 16
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count) / FULL
        return min(1.0, sqrt(rms) * 1.4).toFloat()
    }
}
