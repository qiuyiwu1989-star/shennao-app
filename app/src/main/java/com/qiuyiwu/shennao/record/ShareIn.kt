package com.qiuyiwu.shennao.record

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/*
 * 从别的 App「分享」一段音频进来。
 *
 * 这是采集面的第三个入口（上游 A3，P0）。原话：「用户已在用硬件与转写工具，
 * **不愿换设备**」——飞书妙记 / 通义听悟 / 讯飞 / 钉钉 / Plaud / GetSeed 的导出文件，
 * 直接从分享菜单丢进来。
 *
 * **和灵魂卡、手机录音走同一条上传管道。** 不另开一条——Ingest.kt 里那句话
 * 在这里同样成立：另写一条就是把断点续传、分片幂等、409 各种含义那些坑再踩一遍，
 * 而且两条路会慢慢长歪。所以这里做的只有一件事：把文件落成 vault 里一场
 * 「已经录完」的会话，剩下的交给 Uploader。
 */
object ShareIn {

    /** 这个 intent 是不是分享进来的，是的话带了哪些文件。 */
    fun urisOf(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE -> @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
    }

    /** 收进来的结果。给界面一句话用，不给堆栈。 */
    sealed class Result {
        data class Staged(val title: String, val session: String) : Result()
        data class Skipped(val title: String, val why: String) : Result()
    }

    /**
     * 收一个文件：拷进 vault，落成一场完整的会话。
     *
     * 在 IO 线程调。返回值只描述结果，不抛——分享进来的东西五花八门，
     * 一个坏文件不该把整批打断。
     */
    fun stage(ctx: Context, vault: FileVault, uri: Uri): Result {
        val name = displayName(ctx, uri)
        val title = titleOf(name)
        val ext = extOf(name, ctx.contentResolver.getType(uri))

        /*
         * 流式落盘，边拷边算哈希。以前是整个 readBytes() 进内存，再写临时文件测时长，再写 vault——
         * 一小时的 wav 有 115 MB，三份就闪退（012 P0-2）。现在文件从头到尾只在磁盘上，内存里只有 64 KB 的缓冲。
         */
        val tmp = File(ctx.cacheDir, "share-${System.nanoTime()}.$ext")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val copied = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n <= 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); total += n
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied) { tmp.delete(); return Result.Skipped(title, "读不出这个文件") }
        if (total == 0L) { tmp.delete(); return Result.Skipped(title, "文件是空的") }

        /*
         * 时长从文件本身读。读不出来就不收——填一个假时长上去，服务端的时间轴
         * 会按它排，那是把编造写成事实（Ingest.kt 对「解不出时刻就返回 null」是同一条原则）。
         */
        val durationMs = durationOf(tmp)
            ?: run { tmp.delete(); return Result.Skipped(title, "读不出时长，这个格式可能不支持") }

        /*
         * **幂等键由文件内容决定。** 同一个文件分享两次、或者先在网页传过一次再分享，
         * 深脑那边只该有一条。用 UUID 的话每次都是新的——Ingest.kt 那条注释里的事故
         * 在这里会原样重演。内容哈希对分享进来的文件尤其合适：它没有设备文件名可依。
         */
        val meta = SessionMeta(
            clientRequestId = clientRequestId(hex(digest.digest())),
            title = title,
            // 分享进来的文件不知道自己是什么时候录的（元数据常常被剥掉），
            // 只能用现在。这一点和灵魂卡不同——那边文件名里带着录音时刻。
            startedAtEpochMs = System.currentTimeMillis(),
            finished = true,
        )
        val session = vault.newSession(meta)
        val seg = Segment(0, 0, durationMs, Segment.State.SEALED, ext = ext)
        val dst = vault.segmentFile(session, seg)
        // 同一个应用目录下 rename 是原子的；万一跨了文件系统就退回拷贝
        val moved = tmp.renameTo(dst) || runCatching { tmp.copyTo(dst, overwrite = true); tmp.delete(); true }.getOrDefault(false)
        return if (moved) Result.Staged(title, session)
               else { vault.deleteSession(session); tmp.delete(); Result.Skipped(title, "落盘失败") }
    }

    // ── 纯逻辑，JVM 可测 ────────────────────────────────────────

    /**
     * 幂等键。前缀 share- 和灵魂卡的 ble- / 手机的默认前缀分开——
     * **不同来源故意不共用前缀**，同一份内容从两条路进来就是两条，那是两次真实动作。
     */
    internal fun clientRequestId(sha256Hex: String): String = "share-" + sha256Hex.take(24)

    /** 显示名去掉扩展名当标题；没有名字就给一个能认出来源的。 */
    internal fun titleOf(displayName: String?): String {
        val n = displayName?.trim().orEmpty()
        if (n.isBlank()) return "分享进来的录音"
        return n.substringBeforeLast('.', n).take(80)
    }

    /**
     * 扩展名决定 mimeType，mimeType 决定服务端按什么容器解——报错了会解出垃圾且不报错
     * （Vault.kt 里 Segment.mimeType 的注释）。所以先信文件名后缀，再信分享方给的 mime，
     * 都没有就当 m4a：这是手机上最常见的音频容器。
     */
    internal fun extOf(displayName: String?, mime: String?): String {
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 }
        if (fromName != null && fromName in KNOWN) return fromName
        return when (mime?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a", "video/mp4" -> "m4a"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/aac" -> "aac"
            "audio/flac" -> "flac"
            "audio/webm", "video/webm" -> "webm"
            "audio/amr" -> "amr"
            else -> "m4a"
        }
    }

    private val KNOWN = setOf("mp3", "m4a", "wav", "ogg", "opus", "aac", "flac", "webm", "amr", "mp4")

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun durationOf(file: File): Long? = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        }
    } catch (_: Throwable) { null }
}
