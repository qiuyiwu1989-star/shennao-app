package com.qiuyiwu.shennao

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」：设备、账号、版本、出口。
 *
 * 形制是**分组的列表行**，不是一张张卡：这一屏全是「看一眼确认一下」的信息，
 * 每条各占一张卡是把版面让给了最不需要注意的东西（2026-09-06 真机截图：五张一样的框）。
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
    // 可注入，默认才是真的联网。不然这一屏没法在测试里脱网跑。
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.Pad.screen),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(top = DS.Rhythm.inner, bottom = DS.Rhythm.element))

        // ── 设备 ──
        // 硬件成为商业模式之后，这一栏从「账号页」升级成硬件的控制台——灵魂卡排第一。
        val cardLine = remember { com.qiuyiwu.shennao.ble.CardNames(ctx).known() }
        // 录音不被杀。会中不看屏幕是第一原则，而国内 ROM 默认锁屏几分钟就杀后台——
        // 这一行只说查到的事实（系统豁免了没），厂商开关查不到就只给路径。
        var exempt by remember { mutableStateOf(com.qiuyiwu.shennao.record.KeepAlive.isExempt(ctx)) }
        val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) exempt = com.qiuyiwu.shennao.record.KeepAlive.isExempt(ctx)
            }
            lifecycle.lifecycle.addObserver(obs)
            onDispose { lifecycle.lifecycle.removeObserver(obs) }
        }
        val (keepTitle, keepBody) = com.qiuyiwu.shennao.record.KeepAlive.summary(exempt, com.qiuyiwu.shennao.record.KeepAlive.romHint())
        DsGroup {
            DsRow(
                "灵魂卡",
                subtitle = when (cardLine.size) {
                    0 -> "还没连过。连上它，录的每一段会自动过来。"
                    1 -> cardLine[0].second
                    else -> cardLine.joinToString(" · ") { it.second }
                },
                onClick = onOpenCard,
            )
            RowDivider()
            DsRow(
                keepTitle, subtitle = keepBody,
                trailingContent = { Pill(if (exempt) "已允许" else "未允许", if (exempt) Tone.OK else Tone.WARN) },
                onClick = if (exempt) null else ({
                    runCatching { ctx.startActivity(com.qiuyiwu.shennao.record.KeepAlive.requestIntent(ctx)) }
                }),
            )
        }

        // ── 账号 ──
        SectionLabel("账号")
        DsGroup {
            DsRow("账号", subtitle = client.signedInEmail() ?: "未登录")
            // 显示「已有」不显示「剩余」：剩余是倒计时，已有是陈述。全行业在另一边，故意反着做（规格 010）。
            credits?.let { c ->
                RowDivider()
                DsRow("积分", subtitle = CreditsParser.usageLine(c.month), trailing = "${c.balance}")
            }
            RowDivider()
            // 版本和「检查更新」同一行。「查不到」和「已是最新」必须分开说：网络不通不等于没有新版。
            DsRow(
                "版本 v${BuildConfig.VERSION_NAME}",
                subtitle = when (val s = state) {
                    is UpdateState.Available -> "有新版 v${s.release.versionName} · ${mb(s.release.sizeBytes)} MB"
                    is UpdateState.UpToDate -> "已是最新 · 直接下载安装的版本"
                    is UpdateState.Unknown -> "查不到有没有新版（${s.reason}）"
                    null -> "直接下载安装的版本"
                },
                trailingContent = {
                    if (checking) CircularProgressIndicator(Modifier.size(DS.Size.icon), strokeWidth = DS.Size.rule)
                    else LinkButton(onClick = { check() }) { Text("检查更新") }
                },
            )
            (state as? UpdateState.Available)?.let { s ->
                Column(Modifier.padding(DS.Pad.row)) {
                    PrimaryButton(
                        "下载新版", modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // 交给系统浏览器下载并安装。应用内静默安装需要
                            // 特权，一个从网页分发的包不该去要那种权限。
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.release.url)))
                        },
                    )
                    Spacer(Modifier.height(DS.Rhythm.tight))
                    Text("装新版不用卸载旧的，登录状态和没传完的录音都会留着。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RowDivider()
            // 反馈问题：把此刻的状态打成一份文字发出去。不崩的问题（装不上、连不上、传一半停了）
            // 之前一点痕迹都没有。放在版本旁边：报问题的人第一句就是「我是哪个版本」。
            // 打包要读 vault、跑 logcat、开 keystore，不能在主线程（012 P1-16）
            var packing by remember { mutableStateOf(false) }
            DsRow(
                if (packing) "正在打包…" else "反馈问题",
                subtitle = "把此刻的状态打成一份诊断发出去",
                trailingContent = { if (packing) CircularProgressIndicator(Modifier.size(DS.Size.icon), strokeWidth = DS.Size.rule) },
                onClick = if (packing) null else ({
                    packing = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Diagnostics.share(ctx) }
                        packing = false
                        if (!ok) notice("打包失败，再试一次")
                    }
                }),
            )
        }

        // ── 出口 ──
        SectionLabel("更多")
        DsGroup {
            // 走 App 内 WebView（带登录态）。之前是甩给系统浏览器，
            // 用户点进去看到的是深脑的登录页——他刚才明明就在 App 里登着。
            DsRow("打开深脑网页版", subtitle = "完整的转写、播放、认人、记忆库都在网页版",
                  onClick = { onOpenWeb("/zh", "深脑") })
            RowDivider()
            /*
             * 你的数据。对冲「买的是一张服务年票」的停服恐惧。深脑本来就有「不训模型、
             * 可私有部署」的底子，这里是它的用户面。导出走网页版（带登录态），
             * 手机上不另做一套导出器。存储期限说实话，不承诺「无限」——那是会被砍的权益。
             */
            DsRow("你的数据", subtitle = "原始音频保留 12 个月，转写与判断永久保留。",
                  onClick = { onOpenWeb("/zh/settings", "导出全部") })
            // 出口单独一行：塞在副标题右边会把两行字挤成四行（暗色截图审出来的）
            LinkButton(onClick = { onOpenWeb("/zh/settings", "导出全部") },
                       contentPadding = DS.Pad.row, modifier = Modifier.padding(bottom = DS.Rhythm.tight)) { Text("导出全部 · 网页版") }
            RowDivider()
            // 隐私与条款。成熟产品该有的出口，用户想找的时候要找得到。
            DsRow("隐私政策", onClick = { onOpenWeb("/zh/privacy", "隐私政策") })
            RowDivider()
            DsRow("服务条款", onClick = { onOpenWeb("/zh/terms", "服务条款") })
        }

        Spacer(Modifier.height(DS.Rhythm.inner))
        var signOut by remember { mutableStateOf(false) }
        QuietButton("退出登录", onClick = { signOut = true }, modifier = Modifier.fillMaxWidth())
        if (signOut) ConfirmDialog(
            title = "退出登录？",
            // 用户最担心的是「我还没传完的录音会不会没」。直接回答它。
            detail = "还没传完的录音会留在手机上，重新登录后接着传。",
            confirmLabel = "退出",
            onConfirm = onSignOut,
            onDismiss = { signOut = false },
        )
        Spacer(Modifier.height(DS.Rhythm.page))
    }
}

private fun mb(b: Long) = "%.1f".format(b / 1048576.0)
