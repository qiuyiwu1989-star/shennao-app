package com.qiuyiwu.shennao.record

/*
 * 数 ADTS 帧，算真实时长。
 *
 * 为什么需要它（2026-08-31 实测踩出来的）：
 * 一段声称 60 秒的分片，实际编出来只有 13.31 秒——文件结构完美（208 帧、
 * 0 坏字节、头全对），就是短。服务端拿 60 秒去拼时间轴，等于每段凭空多出
 * 47 秒的空白，整场录音的时间全错位，而**没有任何一层会报错**。
 *
 * 所以这里换掉判据：**时长以产物为准，不以计划为准**。
 * 不去猜编码器为什么少读——那是另一个要查的问题，
 * 但无论它为什么少读，都不该由服务端替我们承担这个谎。
 *
 * AAC-LC 每帧固定 1024 个样本，所以帧数 × 1024 ÷ 采样率就是时长。
 */
object Adts {
    /** 一帧的样本数。AAC-LC 固定 1024，不随码率变。 */
    const val SAMPLES_PER_FRAME = 1024

    data class Scan(val frames: Int, val bytesConsumed: Int, val skipped: Int) {
        fun durationMs(sampleRate: Int): Long =
            frames.toLong() * SAMPLES_PER_FRAME * 1000L / sampleRate
    }

    /**
     * 扫一遍字节流。同步字对不上就往前挪一个字节继续找——
     * 这正是 ADTS 能被随意拼接的原因，也意味着扫描器必须容忍垃圾字节。
     */
    fun scan(d: ByteArray): Scan {
        var i = 0; var frames = 0; var skipped = 0
        while (i + 7 <= d.size) {
            val b0 = d[i].toInt() and 0xFF
            val b1 = d[i + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) { skipped++; i++; continue }
            val len = ((d[i + 3].toInt() and 0x03) shl 11) or
                      ((d[i + 4].toInt() and 0xFF) shl 3) or
                      ((d[i + 5].toInt() and 0xFF) shr 5)
            // 长度非法或越界就停：继续往下扫只会把音频数据当成帧头，
            // 数出一个更离谱的时长。
            if (len < 7 || i + len > d.size) break
            frames++; i += len
        }
        return Scan(frames, i, skipped)
    }
}
