package com.qiuyiwu.shennao

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.qiuyiwu.shennao.ble.BleImportService
import com.qiuyiwu.shennao.record.FileVault
import com.qiuyiwu.shennao.record.KeepAlive
import com.qiuyiwu.shennao.record.Segment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 诊断包：「反馈问题」按一下，把此刻的状态打成一份文字，走系统分享发出去。
 *
 * Crash.kt 只抓崩溃。可「应用未安装」「卡连不上」「传一半停了」这类不崩的问题，
 * 之前一点痕迹都没有——用户能说的只有一句「不行」，我们能问的只有「再试试」。
 * 真机阶段每一个问题都要靠这份东西，所以它排在功能前面。
 *
 * 原则：**只放能定位问题的，不放能定位人的。** 邮箱打码，凭证一个字节不进去，录音内容不进去。
 * 日志只取本进程的 logcat（安卓允许应用读自己的日志，不用 READ_LOGS），最多几百行。
 */
object Diagnostics {

    /** 一份快照。纯数据，render 是纯函数，JVM 可测。 */
    data class Snapshot(
        val versionName: String,
        val versionCode: Int,
        val device: String,
        val android: String,
        val emailMasked: String?,
        val orgId: String?,
        val keepAliveExempt: Boolean,
        val romHint: String?,
        val ble: String,
        val knobs: String,
        val pendingSegments: Int,
        val recordingSegments: Int,
        val sessions: Int,
        val lastCrash: String?,
        val logcat: String,
        val at: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
    )

    fun collect(ctx: Context): Snapshot {
        val creds = runCatching { Session.client(ctx).credentials() }.getOrNull()
        val vault = FileVault(File(ctx.filesDir, "recordings"))
        var pending = 0; var recording = 0; var sessions = 0
        runCatching {
            vault.sessions().forEach { s ->
                sessions++
                vault.segments(s).forEach {
                    when (it.state) { Segment.State.SEALED -> pending++; Segment.State.RECORDING -> recording++; else -> Unit }
                }
            }
        }
        val crash = File(ctx.filesDir, "last_crash.json").takeIf { it.exists() }?.let { runCatching { it.readText().take(2000) }.getOrNull() }
        return Snapshot(
            versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            emailMasked = creds?.email?.let(::mask), orgId = creds?.orgId,
            keepAliveExempt = KeepAlive.isExempt(ctx), romHint = KeepAlive.romHint(),
            ble = bleLine(), knobs = com.qiuyiwu.shennao.ble.LinkTuning.load(ctx).let {
                "fastInterval=${it.fastInterval} mtu=${it.mtu} phy2m=${it.phy2m}"
            }, pendingSegments = pending, recordingSegments = recording, sessions = sessions,
            lastCrash = crash, logcat = ownLogcat(),
        )
    }

    /** 邮箱打码：q***@gmail.com。能认出是哪类账号，认不出是谁。 */
    internal fun mask(email: String): String {
        val at = email.indexOf('@'); if (at <= 0) return "***"
        return email.take(1) + "***" + email.substring(at)
    }

    private fun bleLine(): String = with(BleImportService) {
        "conn=$conn state=${state::class.simpleName} addr=${connectedAddress ?: "-"} " +
            "sync=$syncDone/$syncTotal received=$received err=${lastError ?: "-"} " +
            "battery=${info.batteryLabel ?: "-"} storage=${info.storageLabel ?: "-"} fw=${info.firmware ?: "-"} " +
            "speed=${lastKbps?.let { "%.1fKB/s".format(it) } ?: "-"}"
    }

    private fun ownLogcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "400", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }.getOrElse { "（读不到日志：${it.message}）" }.let { scrub(it) }

    /** 日志里万一带了 Bearer / token 之类，一律抹掉。宁可多抹。 */
    internal fun scrub(s: String): String =
        s.replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+"), "$1***")
         .replace(Regex("(?i)(token|refresh_token|password|ksPass)[\"'=: ]+[^\\s\"',]+"), "$1=***")

    fun render(s: Snapshot): String = buildString {
        appendLine("深脑 安卓诊断 · ${s.at}")
        appendLine("版本 ${s.versionName} (${s.versionCode}) · ${s.device} · ${s.android}")
        appendLine("账号 ${s.emailMasked ?: "未登录"} · org ${s.orgId ?: "-"}")
        appendLine("后台存活 系统豁免=${if (s.keepAliveExempt) "是" else "否"}${s.romHint?.let { " · 厂商开关：$it" } ?: ""}")
        appendLine("灵魂卡 ${s.ble}")
        appendLine("传输实验 ${s.knobs}")
        appendLine("本地录音 会话 ${s.sessions} · 待传段 ${s.pendingSegments} · 录音中段 ${s.recordingSegments}")
        s.lastCrash?.let { appendLine(); appendLine("最近一次崩溃："); appendLine(it) }
        appendLine(); appendLine("── 本进程日志（最近 400 行）──"); append(s.logcat)
    }

    /** 落成文件，走系统分享。文件在 cache/diag 下，系统会自己清。 */
    fun share(ctx: Context): Boolean = runCatching {
        val dir = File(ctx.cacheDir, "diag").apply { mkdirs() }
        val f = File(dir, "shennao-诊断-${SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())}.txt")
        f.writeText(render(collect(ctx)))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "把诊断发给谁").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
