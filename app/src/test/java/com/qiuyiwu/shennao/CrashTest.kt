package com.qiuyiwu.shennao

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class CrashFakeHttp(
    val handler: (method: String, url: String, headers: Map<String, String>, body: String?) -> HttpResponse
        = { _, _, _, _ -> HttpResponse(200, "{}") },
) : Http {
    var lastBody: String? = null
    var calls = 0
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        calls++; lastBody = body
        return handler(method, url, headers, body)
    }
    override fun requestBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray) =
        error("不该走这条")
}

/**
 * 崩溃兜底的测试。**不测「装上了 UncaughtExceptionHandler」这件事本身**——
 * 那是一行系统 API 调用，测它只是在验证 JVM 行为，不是我们的逻辑。
 * 真正会出错的是文件的读写清理和上传时机，这些才是这里要钉住的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `写下的崩溃要能读出完整信息`() {
        val ex = RuntimeException("坏事发生了")
        val write = Crash::class.java.getDeclaredMethod("write", android.content.Context::class.java, Throwable::class.java)
        write.isAccessible = true
        write.invoke(Crash, ctx, ex)

        val f = java.io.File(ctx.filesDir, "last_crash.json")
        assertTrue("崩溃文件应该被写下", f.exists())
        val o = JSONObject(f.readText())
        assertEquals("java.lang.RuntimeException", o.getString("exceptionClass"))
        assertEquals("坏事发生了", o.getString("message"))
        assertTrue("堆栈要包含出错的那一行", o.getString("stack").contains("坏事发生了"))
    }

    @Test fun `没有崩溃文件时上传是空操作，不发请求`() {
        java.io.File(ctx.filesDir, "last_crash.json").delete()
        val http = CrashFakeHttp()
        Crash.uploadLastIfAny(ctx, http, "https://api.test")
        assertEquals(0, http.calls)
    }

    @Test fun `有崩溃文件就发一次，然后不管成败都删掉`() {
        // 诊断数据不值得为了送达率去攒重试队列——见 Crash.kt 里的判据。
        java.io.File(ctx.filesDir, "last_crash.json").writeText(
            JSONObject().put("exceptionClass", "X").put("stack", "at X.y").toString())
        val http = CrashFakeHttp { _, _, _, _ -> HttpResponse(500, "挂了") }
        Crash.uploadLastIfAny(ctx, http, "https://api.test")
        assertEquals(1, http.calls)
        assertFalse("传失败也要删——不然会一直重试同一条",
                    java.io.File(ctx.filesDir, "last_crash.json").exists())
    }

    @Test fun `上传的就是写下的那份原文`() {
        val body = JSONObject().put("exceptionClass", "X").put("stack", "at X.y").toString()
        java.io.File(ctx.filesDir, "last_crash.json").writeText(body)
        val http = CrashFakeHttp()
        Crash.uploadLastIfAny(ctx, http, "https://api.test")
        assertEquals(body, http.lastBody)
    }
}
