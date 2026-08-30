package com.qiuyiwu.shennao.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream

/*
 * 录音线程。
 *
 * 这一版最要紧的一个决定：**录的时候只往磁盘写裸 PCM，封段时才转 AAC。**
 *
 * 直接录成 m4a 看起来省事，但 MediaMuxer 的 moov 原子是在 stop() 那一刻才写的——
 * 进程被杀，整个文件就是一堆读不出来的字节。安卓杀后台进程不打招呼，
 * 这等于说「被杀那一刻之前录的全部作废」。
 *
 * 裸 PCM 没有这个问题：它没有文件头，每一次 write 落盘的字节都是完整可解的音频。
 * 最坏情况只是最后那一段被截短，前面一秒不少。Mac 端在同一个坑上付过学费
 * （AAC-in-CAF 扛不住 SIGKILL，PCM-in-CAF 扛得住），这里直接把结论搬过来。
 *
 * 代价是磁盘：16k 单声道 16 位 = 32 KB/s，一小时 115 MB。所以封一段就立刻转码、
 * 删掉 PCM，磁盘上同时最多只压着一段的原始数据。
 */
object Capture {
    const val SAMPLE_RATE = 16_000            // 和转写引擎的 16k 模型对齐，全链路不重采样
    const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000    // 单声道 16 位 = 32 字节/毫秒

    /** 一段多长。60 秒 ≈ 1.9 MB PCM ≈ 0.5 MB AAC，离服务端 8 MB 的分片上限很远。 */
    const val SEGMENT_MS = 60_000L

    /** 由 PCM 字节数反推真实时长。封段时用它算真正的结束时刻，不用挂钟。 */
    fun durationMsOf(pcmBytes: Long): Long = pcmBytes / BYTES_PER_MS

    @SuppressLint("MissingPermission")
    fun open(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val rec = AudioRecord(
            // VOICE_RECOGNITION：不上 AGC/降噪那一套。它们是为打电话调的，
            // 会把会议室里远处的说话人压掉，而那恰恰是要转写的内容。
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min * 4, BYTES_PER_MS.toInt() * 400),   // 约 400 ms 缓冲，够扛住调度抖动
        )
        return if (rec.state == AudioRecord.STATE_INITIALIZED) rec else { rec.release(); null }
    }
}

/**
 * 一段正在写的 PCM。
 *
 * 文件名里的结束时刻先按「计划长度」写。真正的结束时刻在封段时由字节数算出来，
 * 那时再改名。这样即使进程在录到一半时被杀，留下的文件名依然是合法的、
 * 能被解析出来的——下次启动才认得出「这里有一段没封完的录音」。
 */
class OpenSegment(private val file: File, val sequence: Int, val startMs: Long) {
    private val out = FileOutputStream(file, true)
    var bytes: Long = file.length(); private set

    fun write(buf: ByteArray, n: Int) {
        out.write(buf, 0, n)
        bytes += n
    }

    /** 落到磁盘。掉电时已经 flush 的部分才算数。 */
    fun sync() { runCatching { out.flush(); out.fd.sync() } }

    fun close() { runCatching { out.flush(); out.fd.sync(); out.close() } }

    val elapsedMs: Long get() = Capture.durationMsOf(bytes)
    val trueEndMs: Long get() = startMs + elapsedMs
}
