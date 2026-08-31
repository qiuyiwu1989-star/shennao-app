package com.qiuyiwu.shennao.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream

/**
 * 把一段裸 PCM 转成**带 ADTS 头的裸 AAC**（.aac）。只在封段时跑一次。
 *
 * ## 为什么不是 m4a（2026-08-31 用真实录音栽出来的）
 *
 * 服务端的合成是把所有分片**字节拼接后喂进 ffmpeg 的管道**（`-i pipe:0`）。
 * m4a 扛不住这个用法，两条都实测过：
 *
 *   · 单个 60 秒 m4a 走管道 → `partial file`，直接失败。
 *     因为 moov 索引在文件末尾，而管道不能回退；小文件碰巧能过是
 *     ffmpeg 的探测缓冲盖得住，375 KB 就盖不住了。
 *   · 两个 m4a 拼接 → **不报错**，只解出第一段。
 *     四分钟的会静默变成一分钟——比直接失败糟得多。
 *
 * ADTS 没有索引：每一帧自带 7 字节头，任意两段接起来仍是合法的流。
 * 顺带还消掉了 MediaMuxer 那个「moov 在 stop() 时才写、进程被杀就全废」的
 * 老问题——现在写下去的每一帧都是完整可解的，和裸 PCM 一个道理。
 */
object Encoder {
    /**
     * 码率。16 kHz 单声道语音。
     *
     * 32k 而不是 64k：体积正好减半（3 小时的会 82 MB → 41 MB），
     * 而 16 kHz 单声道语音在这一档听感几乎没有损失——32k 是语音录音的常用档。
     * 再往下 24k 开始发闷，16k 明显劣化，那时省下的流量换的是转写准确率，
     * 不划算（仓里对 ASR 这条路的既有立场就是「不为省钱冒险」）。
     *
     * 服务端拿到之后自己会转成 48k 的 canonical 和 64k 的 ASR 切片——
     * 那两个数字与这里无关，且从 32k 的源转上去不会凭空变好，
     * 真实质量以这里为准。所以判断标准只有一个：**转写准不准**。
     * 若发现识别变差，把这里改回 64_000 即可，链路其余部分不用动。
     */
    private const val BIT_RATE = 32_000
    private const val TIMEOUT_US = 10_000L

    /** 16 kHz 在 AAC 采样率表里的下标。写进 ADTS 头，错了整段解不开。 */
    private const val SR_INDEX_16K = 8

    /** 成功返回 true。失败时不动 PCM 文件，留着下次再试。 */
    fun pcmToAac(pcm: File, out: File): Boolean {
        if (!pcm.isFile || pcm.length() < Capture.BYTES_PER_MS * 10) return false   // 不足 10 ms 不值得
        var codec: MediaCodec? = null
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Capture.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            val info = MediaCodec.BufferInfo()
            var frames = 0

            FileOutputStream(out).use { sink ->
                pcm.inputStream().buffered().use { input ->
                    val chunk = ByteArray(8192)
                    val adts = ByteArray(7)
                    var presentationUs = 0L
                    var eof = false
                    loop@ while (true) {
                        if (!eof) {
                            val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                            if (inIdx >= 0) {
                                val buf = codec.getInputBuffer(inIdx)!!
                                buf.clear()
                                val n = input.read(chunk, 0, minOf(chunk.size, buf.capacity()))
                                if (n <= 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, presentationUs,
                                                           MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    eof = true
                                } else {
                                    buf.put(chunk, 0, n)
                                    codec.queueInputBuffer(inIdx, 0, n, presentationUs, 0)
                                    // 时间戳按采样数推进，不用挂钟——挂钟会让音轨忽快忽慢
                                    presentationUs += n.toLong() * 1_000_000L / (Capture.SAMPLE_RATE * 2)
                                }
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (outIdx >= 0) {
                            // 编码器配置帧（AudioSpecificConfig）不是音频数据。
                            // ADTS 头里已经带了同样的信息，把它写进流里反而会多出一帧噪音。
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && info.size > 0) {
                                val ob = codec.getOutputBuffer(outIdx)!!
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                writeAdtsHeader(adts, info.size)
                                sink.write(adts)
                                val payload = ByteArray(info.size)
                                ob.get(payload)
                                sink.write(payload)
                                frames++
                            }
                            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outIdx, false)
                            if (end) break@loop
                        }
                    }
                }
                sink.flush(); sink.fd.sync()
            }
            // 一帧都没写出来 = 这段是空的。留个 0 字节文件会被当成合法分片传上去。
            if (frames == 0) { out.delete(); return false }
            out.isFile && out.length() > 0
        } catch (e: Exception) {
            out.delete()          // 半截文件比没有更糟：它会被当成一段合法录音传上去
            false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
        }
    }

    /**
     * 写 7 字节 ADTS 头。
     *
     * 每一帧都要带。这正是它能被随意拼接的原因——解码器在流里任何位置
     * 都能找到下一个同步字（0xFFF）重新对齐，不需要任何全局索引。
     */
    private fun writeAdtsHeader(h: ByteArray, payloadLen: Int) {
        val len = payloadLen + 7
        h[0] = 0xFF.toByte()                                    // 同步字高 8 位
        h[1] = 0xF1.toByte()                                    // 同步字低 4 位 + MPEG-4 + Layer 0 + 无 CRC
        // profile(2) 用 AAC-LC=1，写进去要减 1；采样率下标(4)；声道配置(3)=1 单声道
        h[2] = (((1 shl 6) or (SR_INDEX_16K shl 2) or 0).toInt() and 0xFF).toByte()
        h[3] = ((1 shl 6) or ((len shr 11) and 0x03)).toByte()
        h[4] = ((len shr 3) and 0xFF).toByte()
        h[5] = (((len and 0x07) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
    }
}
