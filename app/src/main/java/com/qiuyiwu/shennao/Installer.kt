package com.qiuyiwu.shennao

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/*
 * 应用内装新版。
 *
 * 以前「下载新版」是把人甩给系统浏览器：下载到哪、下完去哪找、点了为什么装不上，
 * 全靠用户自己摸索——这是 4.0 之后「版本更新总是出错」的另一半来源。
 *
 * 现在：App 自己下载到私有缓存 → **逐字节核 sha256**（清单里那一串）→ 交给系统安装器。
 * 核不上就不装、删掉，说清楚。一个从网页分发的包，能替用户做的就这么多：
 * 静默安装要系统特权，不该去要。
 *
 * 第一次装会被系统问「允许这个 App 安装未知应用吗」——那是系统的门，绕不过；
 * 这里把人送到那扇门前，并说明为什么。
 */
object Installer {

    sealed class Step {
        object Idle : Step()
        data class Downloading(val done: Long, val total: Long) : Step()
        data class Ready(val file: File) : Step()
        data class Failed(val reason: String) : Step()
    }

    /** 缓存里的包放哪。同一版只下一次：已经在且校验过就直接装。 */
    fun cacheFile(ctx: Context, release: Release): File =
        File(File(ctx.cacheDir, "apk").apply { mkdirs() }, "shennao-${release.versionName}.apk")

    /**
     * 下载 + 校验。挂起在 IO 上跑。返回 Ready 或 Failed，中途通过 onProgress 报进度。
     *
     * 清单没给 sha256 就不装：不知道该长什么样的包，宁可让人去浏览器自己下。
     */
    fun download(ctx: Context, release: Release, onProgress: (Long, Long) -> Unit): Step {
        if (!Verify.usable(release.sha256)) return Step.Failed("清单里没有校验值，这一版请用浏览器下载")
        val file = cacheFile(ctx, release)
        if (file.isFile && Verify.matches(release.sha256, sha256Hex(file))) return Step.Ready(file)
        val tmp = File(file.path + ".part")
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return try {
            client.newCall(okhttp3.Request.Builder().url(release.url).build()).execute().use { r ->
                if (r.code >= 400) return Step.Failed("下载失败（${r.code}）")
                val body = r.body ?: return Step.Failed("下载失败：没收到内容")
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); done += n
                            onProgress(done, total)
                        }
                    }
                }
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (!Verify.matches(release.sha256, actual)) {
                    tmp.delete()
                    return Step.Failed("下载的包和清单对不上（校验失败），没有安装。再试一次，或用浏览器下载。")
                }
                if (!tmp.renameTo(file)) { tmp.delete(); return Step.Failed("存不下来，清一下手机空间") }
                Step.Ready(file)
            }
        } catch (e: Exception) {
            tmp.delete()
            Step.Failed("下载失败：${e.message ?: "网络不通"}")
        }
    }

    /**
     * 清掉已经过时的包：版本不高于当前装着的这一版的都删（V5 1.5）。
     * 比当前新的留着——那是下好了还没装的。开 App 时调一次。纯逻辑部分在 [staleVersions]。
     */
    fun prune(ctx: Context, currentVersionName: String) {
        val dir = File(ctx.cacheDir, "apk").takeIf { it.isDirectory } ?: return
        val names = dir.listFiles().orEmpty().map { it.name }
        staleVersions(names, currentVersionName).forEach { File(dir, it).delete() }
    }

    /** 文件名 shennao-<版本>.apk 里版本不高于 current 的那些。认不出版本的也删。 */
    fun staleVersions(fileNames: List<String>, currentVersionName: String): List<String> {
        val cur = parseVersion(currentVersionName) ?: return emptyList()
        return fileNames.filter { n ->
            val v = Regex("""^shennao-(.+)\.apk$""").find(n)?.groupValues?.get(1)?.let(::parseVersion)
            v == null || compareVersion(v, cur) <= 0
        }
    }
    private fun parseVersion(s: String): List<Int>? =
        s.split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }
    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrNull(i) ?: 0) - (b.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    /** 系统允不允许这个 App 装包。Android 8 以前没有这道门。 */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 把人送到「允许安装未知应用」那扇门前。 */
    fun askPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 交给系统安装器。返回 false = 起不来（那就退回浏览器）。 */
    fun install(ctx: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 校验的判据。纯逻辑，JVM 可测。 */
    object Verify {
        /** 清单给的值像不像一个 sha256（64 位十六进制）。发布脚本以前写过占位符 "x"。 */
        fun usable(expected: String): Boolean = Regex("^[0-9a-fA-F]{64}$").matches(expected.trim())
        fun matches(expected: String, actual: String): Boolean =
            usable(expected) && expected.trim().equals(actual.trim(), ignoreCase = true)
    }

    /** 进度那一行的话。纯逻辑。 */
    fun progressLine(done: Long, total: Long): String {
        val mb = "%.1f".format(done / 1048576.0)
        return if (total > 0) "下载中 ${(done * 100 / total).coerceIn(0, 100)}% · $mb MB" else "下载中 · $mb MB"
    }
}
