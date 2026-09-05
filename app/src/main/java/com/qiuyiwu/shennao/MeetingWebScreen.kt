package com.qiuyiwu.shennao

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在 App 里打开网页版的那一页（带登录态）。
 *
 * **为什么不直接甩给系统浏览器。** 原来的做法是 ACTION_VIEW 开
 * `/zh/transcript/<id>`——用户在 Chrome 里多半没登录，看到的是登录页。
 * 于是这个按钮的实际效果是「把人踢出 App，然后要求他再登一次」。
 *
 * 走的是服务端已经建好的入场券：App 用 Bearer 换一张一分钟有效、只能用一次的券，
 * 让 WebView 自己去请求 /api/mobile/web-open —— **必须由 WebView 自己请求**，
 * cookie 要落在它的 cookie 罐里，App 那边的 HTTP 客户端替它请求是没用的。
 * 券走 URL 参数而 token 不走：券一分钟就死、用一次就废，token 是一整个会话。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MeetingWebScreen(client: DeepBrainClient, path: String, title: String, onBack: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(path, attempt) {
        when (val r = withContext(Dispatchers.IO) { client.webTicket() }) {
            is ApiResult.Ok ->
                url = "${BuildConfig.API_BASE}/api/mobile/web-open" +
                      "?t=${android.net.Uri.encode(r.value)}&to=${android.net.Uri.encode(path)}"
            is ApiResult.Failed -> { error = r.message; loading = false }
            else -> { error = "登录失效了"; loading = false }
        }
    }

    // 网页里点进去几层之后，系统返回键该退回上一页，而不是一下子退出整个页面。
    BackHandler(enabled = web?.canGoBack() == true) { web?.goBack() }

    DetailPage(onBack = onBack, actions = {}) {
        item {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(DS.Rhythm.element))
        }
        // 重试要重新领票据。之前这里是个空回调——点了没反应。
        error?.let { e -> item { Broken(e) { error = null; loading = true; attempt++ } } }
        if (error == null) item {
            Box(Modifier.fillMaxWidth().heightIn(min = 480.dp)) {
                url?.let { u ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                web = this
                                settings.javaScriptEnabled = true   // 网页版是 Next.js，关掉等于白屏
                                settings.domStorageEnabled = true
                                /*
                                 * **这三个必须关掉。** 这个 WebView 里装着一份完整的登录 cookie，
                                 * 一旦它能读本地文件、或者让页面用 file:// 发起请求，
                                 * 一个被注入的页面就能把会话连同本地录音一起带走。
                                 */
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                @Suppress("DEPRECATION")
                                settings.allowFileAccessFromFileURLs = false
                                webViewClient = object : WebViewClient() {
                                    /**
                                     * **只留在自己家的域里。**
                                     *
                                     * 转写里会有别人贴的链接。在这个带着登录 cookie 的
                                     * WebView 里打开外站，等于把会话暴露给那个站点的脚本。
                                     * 外链一律交给系统浏览器——那边没有我们的 cookie。
                                     */
                                    override fun shouldOverrideUrlLoading(
                                        v: WebView, req: WebResourceRequest,
                                    ): Boolean {
                                        val target = req.url.toString()
                                        if (sameOrigin(target)) return false
                                        runCatching {
                                            ctx.startActivity(android.content.Intent(
                                                android.content.Intent.ACTION_VIEW, req.url))
                                        }
                                        return true
                                    }
                                    override fun onPageFinished(v: WebView, u: String) { loading = false }
                                }
                                loadUrl(u)
                            }
                        },
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

/** 与 API_BASE 同源才留在这个 WebView 里。比较 scheme+host+port，不做字符串前缀匹配。 */
internal fun sameOrigin(target: String, base: String = BuildConfig.API_BASE): Boolean {
    val b = runCatching { java.net.URI(base) }.getOrNull() ?: return false
    val t = runCatching { java.net.URI(target) }.getOrNull() ?: return false
    // 前缀匹配会把 https://shennao.zaowuyun.com.evil.com 判成同源。
    return t.scheme == b.scheme && t.host == b.host && t.port == b.port
}
