package com.qiuyiwu.shennao

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」：账号、版本、更新。
 *
 * 更新入口放在这里而不是首页：它是低频动作，而首页的位置应该留给
 * 每天都要看的东西。但**检查要自动做一次**——用户不会主动来点，
 * 而停在旧版的代价是「录了传不上去」这类他自己看不出的问题。
 */
@Composable
fun MeScreen(
    client: DeepBrainClient,
    onOpenWeb: (path: String, title: String) -> Unit,
    onSignOut: () -> Unit,
    /** 进灵魂卡那一页（扫描 / 连接 / 同步 / 改名） */
    onOpenCard: () -> Unit = {},
    // 可注入，默认才是真的联网。不然这一屏没法在测试里脱网跑——
    // 之前一直是 Update.check(UrlHttp(), ...) 写死在里面，
    // 是这一版顺手改的，不是重点，但既然要给这一屏加测试就该改掉。
    http: Http = UrlHttp(),
) {
    val ctx = LocalContext.current
    val notice = LocalNotice.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun check() {
        checking = true
        scope.launch {
            state = withContext(Dispatchers.IO) { Update.check(http, BuildConfig.VERSION_CODE) }
            checking = false
        }
    }
    // 进来就自动查一次。用户不会主动来点，而停在旧版是看不出来的。
    LaunchedEffect(Unit) { check() }
    // 用量。服务端没这个端点（还没部署 Phase 2）时是 Failed，这一行就不显示——不画点了没反应的东西。
    var credits by remember { mutableStateOf<Credits?>(null) }
    LaunchedEffect(Unit) {
        (withContext(Dispatchers.IO) { runCatching { client.credits() }.getOrNull() } as? ApiResult.Ok)?.value
            ?.takeIf { it.balance >= 0 }?.let { credits = it }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.Pad.default),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall)

        // 硬件成为商业模式之后，这一栏从「账号页」升级成硬件的控制台——灵魂卡排第一。
        // 点进去是 BleScreen 整页，不是一个二级设置。
        val cardLine = remember { com.qiuyiwu.shennao.ble.CardNames(ctx).known() }
        DsCard(Modifier.fillMaxWidth(), onClick = onOpenCard) {
            Row(Modifier.padding(DS.Pad.tight), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("灵魂卡", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(DS.Rhythm.tight))
                    Text(
                        when (cardLine.size) {
                            0 -> "还没连过。连上它，录的每一段会自动过来。"
                            1 -> cardLine[0].second
                            else -> cardLine.joinToString(" · ") { it.second }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
            }
        }

        // 录音不被杀。会中不看屏幕是第一原则，而国内 ROM 默认锁屏几分钟就杀后台——
        // 这张卡只说查到的事实（系统豁免了没），厂商开关查不到就只给路径。
        var exempt by remember { mutableStateOf(com.qiuyiwu.shennao.record.KeepAlive.isExempt(ctx)) }
        val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) exempt = com.qiuyiwu.shennao.record.KeepAlive.isExempt(ctx)
            }
            lifecycle.lifecycle.addObserver(obs)
            onDispose { lifecycle.lifecycle.removeObserver(obs) }
        }
        val (keepTitle, keepBody) = com.qiuyiwu.shennao.record.KeepAlive.summary(exempt, com.qiuyiwu.shennao.record.KeepAlive.romHint())
        DsCard(Modifier.fillMaxWidth(), onClick = {
            if (!exempt) runCatching { ctx.startActivity(com.qiuyiwu.shennao.record.KeepAlive.requestIntent(ctx)) }
        }) {
            Column(Modifier.padding(DS.Pad.tight)) {
                Text(keepTitle, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text(keepBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 账号与版本合成一张卡。密度规则：首屏 ≤ 5 块。这两样都是「看一眼确认一下」的信息，
        // 分两张卡各占一块，是把版面让给了最不需要注意的东西。
        DsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(DS.Pad.tight)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("账号", style = MaterialTheme.typography.titleSmall)
                        Text(client.signedInEmail() ?: "未登录",
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // 显示「已有」不显示「剩余」：剩余是倒计时，已有是陈述。全行业在另一边，故意反着做。
                        credits?.let { c ->
                            Text("积分 ${c.balance}", style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // 规格 010：显示「已用」不显示「剩余」。倒计时式的焦虑条是这个品类差评的第一来源。
                            CreditsParser.usageLine(c.month)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("版本 v${BuildConfig.VERSION_NAME} · 直接下载安装的版本",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { check() }) { Text("检查更新") }
                }
                // 反馈问题：把此刻的状态打成一份文字发出去。不崩的问题（装不上、连不上、传一半停了）
                // 之前一点痕迹都没有。放在版本旁边：报问题的人第一句就是「我是哪个版本」。
                TextButton(onClick = { if (!Diagnostics.share(ctx)) notice("打包失败，再试一次") },
                           contentPadding = PaddingValues()) { Text("反馈问题 · 发一份诊断") }

                when (val s = state) {
                    is UpdateState.Available -> {
                        Spacer(Modifier.height(DS.Rhythm.element))
                        Text("有新版 v${s.release.versionName} · ${mb(s.release.sizeBytes)} MB",
                             style = MaterialTheme.typography.bodyMedium,
                             fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(DS.Rhythm.element))
                        Button(
                            onClick = {
                                // 交给系统浏览器下载并安装。应用内静默安装需要
                                // 特权，一个从网页分发的包不该去要那种权限。
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.release.url)))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("下载新版") }
                        Spacer(Modifier.height(DS.Rhythm.tight))
                        Text("装新版不用卸载旧的，登录状态和没传完的录音都会留着。",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UpdateState.UpToDate -> {
                        Spacer(Modifier.height(DS.Rhythm.element))
                        Text("已是最新", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UpdateState.Unknown -> {
                        Spacer(Modifier.height(DS.Rhythm.element))
                        // 「查不到」和「已是最新」必须分开说：网络不通不等于没有新版
                        Text("查不到有没有新版（${s.reason}）",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                    null -> Unit
                }
            }
        }

        DsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(DS.Pad.tight)) {
                Text("在网页里打开", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text("完整的转写、播放、认人、记忆库都在网页版。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(DS.Rhythm.element))
                // 走 App 内 WebView（带登录态）。之前是甩给系统浏览器，
                // 用户点进去看到的是深脑的登录页——他刚才明明就在 App 里登着。
                OutlinedButton(onClick = { onOpenWeb("/zh", "深脑") }) { Text("打开深脑网页版") }
                // 隐私与条款。成熟产品该有的出口，用户想找的时候要找得到——
                // 不用等到真出了纠纷才发现 App 里压根没有这条路。并在这张卡里，不单占一块。
                Row(horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.inner)) {
                    TextButton(onClick = { onOpenWeb("/zh/privacy", "隐私政策") }) { Text("隐私政策") }
                    TextButton(onClick = { onOpenWeb("/zh/terms", "服务条款") }) { Text("服务条款") }
                }
            }
        }

        /*
         * 你的数据。对冲「买的是一张服务年票」的停服恐惧——搜狗录音笔停服让
         * 「终身免费」破产，Limitless 卖身后数据限期导出。深脑本来就有「不训模型、
         * 可私有部署」的底子，这里是它的用户面。导出走网页版（带登录态），
         * 手机上不另做一套导出器。存储期限说实话，不承诺「无限」——那是会被砍的权益。
         */
        DsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(DS.Pad.tight)) {
                Text("你的数据", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text("音频、转写、判断都可以整包带走。我们不训模型，也不做「服务年票」。",
                     style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text("原始音频保留 12 个月，转写与判断永久保留。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(DS.Rhythm.element))
                OutlinedButton(onClick = { onOpenWeb("/zh/settings", "导出全部") }) { Text("导出全部 · 网页版") }
            }
        }

        var signOut by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { signOut = true }, modifier = Modifier.fillMaxWidth()) {
            Text("退出登录")
        }
        if (signOut) ConfirmDialog(
            title = "退出登录？",
            // 用户最担心的是「我还没传完的录音会不会没」。直接回答它。
            detail = "还没传完的录音会留在手机上，重新登录后接着传。",
            confirmLabel = "退出",
            onConfirm = onSignOut,
            onDismiss = { signOut = false },
        )
        Spacer(Modifier.height(DS.Rhythm.element))
    }
}

private fun mb(b: Long) = "%.1f".format(b / 1048576.0)
