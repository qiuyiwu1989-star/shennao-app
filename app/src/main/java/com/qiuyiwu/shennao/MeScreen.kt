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
    /** 切了组织：调用方清缓存、重取今天 */
    onOrgSwitched: () -> Unit = {},
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
        Text("我的", style = MaterialTheme.typography.headlineSmall,
             modifier = Modifier.padding(top = DS.Rhythm.section, bottom = DS.Rhythm.inner))

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
            /*
             * 全时聆听。
             *
             * 这一条必须把代价说全，因为它要的授权比「录一场会」大得多：
             * 麦克风一直开着、电量一直在掉、录下来的每一段都要花积分分析。
             * 只写「打开」而不写这些，用户第一次看到账单才知道自己同意了什么。
             *
             * 相位照服务念，不自己维护——被电话抢走麦克风时它是断的，
             * 而那恰恰是用户最需要知道的一刻。
             */
            var listenPhase by remember { mutableStateOf(com.qiuyiwu.shennao.record.RecordingService.listenPhase) }
            var listened by remember { mutableStateOf(com.qiuyiwu.shennao.record.RecordingService.listenedSpeechMs) }
            LaunchedEffect(Unit) {
                while (true) {
                    listenPhase = com.qiuyiwu.shennao.record.RecordingService.listenPhase
                    listened = com.qiuyiwu.shennao.record.RecordingService.listenedSpeechMs
                    kotlinx.coroutines.delay(1_000)
                }
            }
            val on = listenPhase != com.qiuyiwu.shennao.record.AlwaysOn.Phase.OFF
            DsRow(
                "全时聆听",
                subtitle = when (listenPhase) {
                    // 「在听」和「正录着」是两件事，必须分开说：说成一句，
                    // 用户就没法判断此刻到底有没有在把声音录进去。
                    com.qiuyiwu.shennao.record.AlwaysOn.Phase.RECORDING ->
                        "正在录 · 今天录下 " + minutesLabel(listened)
                    com.qiuyiwu.shennao.record.AlwaysOn.Phase.LISTENING ->
                        "在听，有人说话时才录 · 今天录下 " + minutesLabel(listened)
                    else -> "麦克风一直开着，听到有人说话才录。费电，录下来的也要花积分分析。"
                },
                trailingContent = {
                    // 没给麦克风权限就先要权限。不要一声不响地去开——
                    // 开不起来的时候 AlwaysOn 只能说「麦克风被别的应用占着」，
                    // 而真相是它压根没被允许听，这是两件完全不同的事。
                    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) com.qiuyiwu.shennao.record.RecordingService.listen(ctx)
                    }
                    Switch(checked = on, onCheckedChange = { want ->
                        if (!want) com.qiuyiwu.shennao.record.RecordingService.stopListening(ctx)
                        else if (androidx.core.content.ContextCompat.checkSelfPermission(
                                ctx, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) com.qiuyiwu.shennao.record.RecordingService.listen(ctx)
                        else ask.launch(android.Manifest.permission.RECORD_AUDIO)
                    })
                },
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
        // 组织。多组织账号以前只能用最早的那个（012 P3-4）——账号里有个空的测试组织的人，
        // 登进来「今天」永远是空的，而且没有任何地方告诉他为什么。
        var orgs by remember { mutableStateOf<List<Org>>(emptyList()) }
        var orgLoaded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            orgs = (withContext(Dispatchers.IO) { runCatching { client.orgs() }.getOrNull() } as? ApiResult.Ok)?.value ?: emptyList()
            orgLoaded = true
        }
        var picking by remember { mutableStateOf(false) }
        val currentOrg = client.orgId()
        val currentName = orgs.firstOrNull { it.id == currentOrg }?.name
        DsGroup {
            DsRow("账号", subtitle = client.signedInEmail() ?: "未登录")
            if (orgLoaded && orgs.isNotEmpty()) {
                RowDivider()
                DsRow(
                    "组织",
                    subtitle = currentName ?: currentOrg?.take(8) ?: "—",
                    trailing = if (orgs.size > 1) "${orgs.size} 个" else null,
                    onClick = if (orgs.size > 1) ({ picking = true }) else null,
                )
            }
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
                    is UpdateState.Unknown -> "查不到有没有新版：${s.reason}"
                    null -> "直接下载安装的版本"
                },
                trailingContent = {
                    if (checking) CircularProgressIndicator(Modifier.size(DS.Size.icon), strokeWidth = DS.Size.rule)
                    else LinkButton(onClick = { check() }) { Text("检查更新") }
                },
            )
            (state as? UpdateState.Available)?.let { s -> UpdateBlock(s.release) }
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

        if (picking) OrgPicker(
            orgs = orgs, current = currentOrg,
            onPick = { o ->
                picking = false
                if (client.switchOrg(o.id)) { onOrgSwitched(); notice("已切到「${o.name}」") }
            },
            onDismiss = { picking = false },
        )

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

/** 选组织。一行一个，当前那个打勾；个人空间标出来。 */
@Composable
private fun OrgPicker(orgs: List<Org>, current: String?, onPick: (Org) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DS.Radius.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("切换组织", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text("「今天」「记录」「问」都按组织分。正在传的录音留在它录制时的组织里，不会跟着跑。",
                     style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(DS.Rhythm.element))
                orgs.forEach { o ->
                    DsRow(
                        o.name,
                        subtitle = listOfNotNull(if (o.personal && o.name != "个人空间") "个人空间" else null, roleLabel(o.role)).joinToString(" · ").ifBlank { null },
                        trailingContent = { if (o.id == current) Pill("当前", Tone.ACCENT) },
                        onClick = { onPick(o) },
                    )
                }
            }
        },
        confirmButton = { QuietButton("取消", onClick = onDismiss) },
    )
}

/**
 * 有新版时那一块：下载 → 校验 → 交给系统装。以前是把人甩给浏览器，下到哪、装不装得上全靠猜。
 * 校验不过就不装并说清楚；系统没开「允许安装」就先送去开；起不来才退回浏览器。
 */
@Composable
private fun UpdateBlock(release: Release) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember(release.versionCode) { mutableStateOf<Installer.Step>(Installer.Step.Idle) }
    val browser = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url))) }
    fun fetch() {
        step = Installer.Step.Downloading(0, release.sizeBytes)
        scope.launch {
            step = withContext(Dispatchers.IO) {
                Installer.download(ctx, release) { d, t -> step = Installer.Step.Downloading(d, t) }
            }
            (step as? Installer.Step.Ready)?.let { r ->
                if (Installer.canInstall(ctx)) { if (!Installer.install(ctx, r.file)) browser() }
                else Installer.askPermission(ctx)
            }
        }
    }
    Column(Modifier.padding(DS.Pad.row)) {
        when (val st = step) {
            is Installer.Step.Idle -> PrimaryButton(
                "下载并安装 v${release.versionName} · ${mb(release.sizeBytes)} MB",
                modifier = Modifier.fillMaxWidth(), onClick = { fetch() })
            is Installer.Step.Downloading -> {
                LinearProgressIndicator(
                    progress = { if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text(Installer.progressLine(st.done, st.total), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is Installer.Step.Ready -> {
                // 下好且校验过。系统那扇门没开就在这里说清楚，开了回来再点一次就装。
                if (!Installer.canInstall(ctx)) {
                    NoticeBox("系统还没允许深脑安装应用。点下面去打开，回来再点「安装」。", Tone.WARN) {
                        TonalButton("去允许", onClick = { Installer.askPermission(ctx) })
                    }
                    Spacer(Modifier.height(DS.Rhythm.tight))
                }
                PrimaryButton("安装 v${release.versionName}", modifier = Modifier.fillMaxWidth(),
                              onClick = { if (Installer.canInstall(ctx)) { if (!Installer.install(ctx, st.file)) browser() } else Installer.askPermission(ctx) })
            }
            is Installer.Step.Failed -> {
                NoticeBox(st.reason, Tone.RISK) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.tight)) {
                        TonalButton("再试一次", onClick = { fetch() })
                        LinkButton(onClick = browser) { Text("用浏览器下载") }
                    }
                }
            }
        }
        Spacer(Modifier.height(DS.Rhythm.tight))
        Text("装新版不用卸载旧的，登录状态和没传完的录音都会留着。",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 角色说中文。认不出的原样给。 */
internal fun roleLabel(role: String): String? = when (role) {
    "owner" -> "拥有者"; "admin" -> "管理员"; "member" -> "成员"; "viewer" -> "只读"
    "" -> null; else -> role
}
