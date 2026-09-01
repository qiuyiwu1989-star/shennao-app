package com.qiuyiwu.shennao

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import org.json.JSONObject

/**
 * 崩溃兜底。
 *
 * **之前 App 崩溃我们完全不知道。** 用户看到的是「闪退」，我们看到的
 * 是零信号——不知道多少人遇到过、遇到的是不是同一个坑。
 *
 * 抓法很朴素：`setDefaultUncaughtExceptionHandler` 写一份文件到本地，
 * **不在崩溃现场联网上传**——那一刻进程已经在死亡边缘，起一个网络请求
 * 大概率来不及完成，还可能把系统的正常崩溃处理（写 tombstone、
 * 弹「应用已停止」）也拖慢。写完文件后照旧调用系统默认处理器，
 * 该崩就崩，只是这次崩溃**留了痕迹**。
 *
 * 上传挪到下一次正常启动——那时候进程是健康的，一次 fire-and-forget
 * 的 POST 花不了多久。传没传成都删文件：这是诊断遥测，不是必须送达的
 * 数据，为了「保证送达」去做重试队列不值得，而且会让崩溃报告本身
 * 变成第二个「越攒越多打不出去」的坑。
 */
object Crash {
    private const val FILE = "last_crash.json"

    fun install(ctx: Context) {
        val appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching { write(appCtx, ex) }
            // 交还给系统默认处理器（在这之前是 Android runtime 自己的），
            // 不吞掉它——用户仍然要看到系统的「应用已停止」，
            // 进程仍然要正常终止，不然会留下一个状态错乱的僵尸进程。
            previous?.uncaughtException(thread, ex) ?: Runtime.getRuntime().exit(1)
        }
    }

    private fun write(ctx: Context, ex: Throwable) {
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        val o = JSONObject()
            .put("exceptionClass", ex.javaClass.name)
            .put("message", ex.message ?: "")
            .put("stack", sw.toString())
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("androidSdk", Build.VERSION.SDK_INT)
        File(ctx.filesDir, FILE).writeText(o.toString())
    }

    /**
     * 上次是不是崩溃退出的，有就传一次。
     *
     * 调用方负责挑一个健康的时机（App 正常启动时），不在这个函数里自己
     * 判断——「什么时候算安全」是调用方的知识，不是这个文件的。
     */
    fun uploadLastIfAny(ctx: Context, http: Http, apiBase: String) {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return
        val body = runCatching { f.readText() }.getOrNull()
        // 传没传成都删——诊断数据不值得为了送达率去攒重试队列。
        f.delete()
        if (body.isNullOrBlank()) return
        runCatching {
            http.request("POST", "$apiBase/api/mobile/crash",
                mapOf("Content-Type" to "application/json"), body)
        }
    }
}
