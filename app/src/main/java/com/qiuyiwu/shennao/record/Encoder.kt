package com.qiuyiwu.shennao.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 把一段裸 PCM 转成 m4a（AAC）。只在封段时跑一次。
 *
 * 64 kbps 单声道对 16k 语音来说绰绰有余：一分钟约 0.5 MB，
 * 相比裸 PCM 的 1.9 MB 省下四分之三的流量，而转写质量看不出差别。
 *
 * 转完才删 PCM，删之前先确认 m4a 存在且不是空文件——反过来的话，
 * 一次转码失败就等于把这一分钟的录音永久扔了。
 */
object Encoder {
    private const val BIT_RATE = 64_000
    private const val TIMEOUT_US = 10_000L

    /** 成功返回 true。失败时不动 PCM 文件，留着下次再试。 */
    fun pcmToM4a(pcm: File, out: File): Boolean {
        if (!pcm.isFile || pcm.length() < Capture.BYTES_PER_MS * 10) return false   // 不足 10 ms 不值得
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, Capture.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                           android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var track = -1
            var muxing = false
            var presentationUs = 0L
            var eof = false
            val info = MediaCodec.BufferInfo()

            pcm.inputStream().buffered().use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    if (!eof) {
                        val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val buf: ByteBuffer = codec.getInputBuffer(inIdx)!!
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
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start(); muxing = true
                        }
                        outIdx >= 0 -> {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            // 编码器配置帧不是音频数据，混进音轨会让文件解不开
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            }
                            if (info.size > 0 && muxing) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                muxer.writeSampleData(track, ob, info)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return@use
                        }
                    }
                }
            }
            muxer.stop()          // moov 在这里写。到这一步说明数据全进去了
            out.isFile && out.length() > 0
        } catch (e: Exception) {
            out.delete()          // 半截 m4a 比没有更糟：它会被当成一段合法录音传上去
            false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }
    }
}
