package com.qiuyiwu.shennao

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    onOpenSchedule: () -> Unit = {},
    onOpenAgents: () -> Unit = {},
    /** 截图测试传固定值：版本号跟着每次发布变，基准图不该每发一版就红一次 */
    versionName: String = BuildConfig.VERSION_NAME,
    // 可注入，默认才是真的联网。不然这一屏没法在测试里脱网跑。
    http: Http = UrlHttp(),
) {
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

    // 组织。多组织账号以前只能用最早的那个（012 P3-4）。
    var orgs by remember { mutableStateOf<List<Org>>(emptyList()) }
    LaunchedEffect(Unit) {
        orgs = (withContext(Dispatchers.IO) { runCatching { client.orgs() }.getOrNull() } as? ApiResult.Ok)?.value ?: emptyList()
    }

    // 三样要联网的（更新、积分、组织）在上面取，画面在 MeContent。截图测试直接把解析好的三样
    // 喂给 MeContent，不跑这里的协程——Robolectric 下 IO 回主线程的时机不定，拍出来的是半截页。
    MeContent(
        client, state, checking, onCheckUpdate = { check() }, credits = credits, orgs = orgs,
        onOpenWeb = onOpenWeb, onSignOut = onSignOut, onOpenCard = onOpenCard, onOrgSwitched = onOrgSwitched,
        onOpenSchedule = onOpenSchedule, onOpenAgents = onOpenAgents, versionName = versionName,
    )
}

/**
 * 「我的」的画面。要联网才知道的三样——有没有新版 [state]、积分 [credits]、组织 [orgs]——都由外面给；
 * 这里只读本机的事实（豁免、灵魂卡、聆听相位）和画。
 */
@Composable
fun MeContent(
    client: DeepBrainClient,
    state: UpdateState?,
    checking: Boolean,
    onCheckUpdate: () -> Unit,
    credits: Credits?,
    orgs: List<Org>,
    onOpenWeb: (path: String, title: String) -> Unit,
    onSignOut: () -> Unit,
    onOpenCard: () -> Unit = {},
    onOrgSwitched: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    onOpenAgents: () -> Unit = {},
    versionName: String = BuildConfig.VERSION_NAME,
) {
    val ctx = LocalContext.current
    val notice = LocalNotice.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    val currentOrg = client.orgId()
    val currentName = orgs.firstOrNull { it.id == currentOrg }?.name

    // 录音不被杀（spec 011）：只说查到的事实。
    var exempt by remember { mutableStateOf(com.qiuyiwu.shennao.record.KeepAlive.isExempt(ctx)) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) exempt = com.qiuyiwu.shennao.record.KeepAlive.isExempt(ctx)
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val romHint = remember { com.qiuyiwu.shennao.record.KeepAlive.romHint() }
    val cardLine = remember { com.qiuyiwu.shennao.ble.CardNames(ctx).known() }

    // 全时聆听的相位照服务念，不自己维护。
    var listenPhase by remember { mutableStateOf(com.qiuyiwu.shennao.record.RecordingService.listenPhase) }
    var listened by remember { mutableLongStateOf(com.qiuyiwu.shennao.record.RecordingService.listenedSpeechMs) }
    LaunchedEffect(Unit) {
        while (true) {
            listenPhase = com.qiuyiwu.shennao.record.RecordingService.listenPhase
            listened = com.qiuyiwu.shennao.record.RecordingService.listenedSpeechMs
            kotlinx.coroutines.delay(1_000)
        }
    }

    /*
     * 这一屏是账号模块，不是说明书（2026-09-11 用户反馈：铺的信息多，但没什么有用的）。
     * 顶上一张身份卡：你是谁、在哪个组织、有多少积分。下面三组短行，每行一个词一个数，
     * 解释性的话只在「需要你做点什么」的时候出现。
     */
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.Pad.screen),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall,
             modifier = Modifier.padding(top = DS.Rhythm.section, bottom = DS.Rhythm.inner))

        // ── 身份卡 ──
        val email = client.signedInEmail() ?: "未登录"
        DsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(DS.Pad.card)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 头像：邮箱首字母。没有上传头像的地方，一个字母比一个灰人像更像「我」。
                    Box(
                        Modifier.size(DS.Size.hit).background(MaterialTheme.colorScheme.primary, DS.Radius.control),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(email.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(DS.Rhythm.element))
                    Column(Modifier.weight(1f)) {
                        Text(email, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(DS.Rhythm.hair))
                        // 组织名取不到（没网）就不显示，不要露出 id 前八位那种谁都看不懂的东西
                        currentName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (orgs.size > 1) LinkButton(onClick = { picking = true }, contentPadding = PaddingValues(horizontal = DS.Rhythm.tight)) { Text("切换组织") }
                }
                credits?.let { c ->
                    Spacer(Modifier.height(DS.Rhythm.element))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(DS.Rhythm.element))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${c.balance}", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(DS.Rhythm.tight))
                        Text("积分", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // 显示「已用」不显示「剩余」：剩余是倒计时，已用是陈述（规格 010）。
                    CreditsParser.usageLine(c.month)?.let {
                        Spacer(Modifier.height(DS.Rhythm.hair))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ── 录音 ──
        SectionLabel("录音")
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
            val on = listenPhase != com.qiuyiwu.shennao.record.AlwaysOn.Phase.OFF
            DsRow(
                "全时聆听",
                // 「在听」和「正录着」是两件事，必须分开说。关着时只说代价，一句。
                subtitle = when (listenPhase) {
                    com.qiuyiwu.shennao.record.AlwaysOn.Phase.RECORDING -> "正在录 · 今天录下 " + minutesLabel(listened)
                    com.qiuyiwu.shennao.record.AlwaysOn.Phase.LISTENING -> "在听，有人说话时才录 · 今天录下 " + minutesLabel(listened)
                    else -> "麦克风常开，有人说话才录。费电，录下的要花积分分析。"
                },
                trailingContent = {
                    // 没给麦克风权限就先要权限，不要一声不响地去开。
                    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                    ) { granted -> if (granted) com.qiuyiwu.shennao.record.RecordingService.listen(ctx) }
                    DsSwitch("全时聆听", checked = on, onCheckedChange = { want ->
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
            val sched = rememberListenSchedule(ctx)
            DsRow(
                "定时聆听",
                subtitle = if (sched.enabled) sched.summary else "设一次，到点提醒你开始和停止",
                trailingContent = { if (sched.enabled) Pill("已设", Tone.OK) },
                onClick = onOpenSchedule,
            )
            RowDivider()
            // 已允许就只剩一个词；没允许才解释要做什么。
            DsRow(
                "后台一直录",
                subtitle = if (exempt) null else "锁屏几分钟后录音可能被系统停掉。点一下，系统会问你要不要允许。",
                trailingContent = { Pill(if (exempt) "已允许" else "未允许", if (exempt) Tone.OK else Tone.WARN) },
                onClick = if (exempt) null else ({
                    runCatching { ctx.startActivity(com.qiuyiwu.shennao.record.KeepAlive.requestIntent(ctx)) }
                }),
            )
            if (!exempt && romHint != null) {
                Text(romHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(DS.Pad.row).padding(top = DS.Rhythm.hair))
            }
        }

        // ── 应用 ──
        SectionLabel("应用")
        DsGroup {
            DsRow("外观", trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.hair)) {
                    Appearance.Mode.entries.forEach { m ->
                        DsChip(selected = Appearance.mode.value == m, label = m.label, onClick = { Appearance.save(ctx, m) })
                    }
                }
            })
            RowDivider()
            // 「查不到」和「已是最新」必须分开说：网络不通不等于没有新版。
            DsRow(
                "版本 v$versionName",
                subtitle = when (val s = state) {
                    is UpdateState.Available -> "有新版 v${s.release.versionName} · ${mb(s.release.sizeBytes)} MB"
                    is UpdateState.UpToDate -> "已是最新"
                    is UpdateState.Unknown -> "查不到有没有新版：${s.reason}"
                    null -> null
                },
                trailingContent = {
                    if (checking) CircularProgressIndicator(Modifier.size(DS.Size.icon), strokeWidth = DS.Size.rule)
                    else LinkButton(onClick = onCheckUpdate) { Text("检查更新") }
                },
            )
            (state as? UpdateState.Available)?.let { s -> UpdateBlock(s.release) }
            RowDivider()
            var packing by remember { mutableStateOf(false) }
            DsRow(
                if (packing) "正在打包…" else "反馈问题",
                subtitle = "发一份诊断给我们",
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
            RowDivider()
            // 走 App 内 WebView（带登录态）。
            DsRow("深脑网页版", subtitle = "完整的转写、播放、认人、记忆库", onClick = { onOpenWeb("/zh", "深脑") })
            RowDivider()
            DsRow("接入 AI", subtitle = "让 Claude、ChatGPT、Cursor 用上你的记忆", onClick = onOpenAgents)
        }

        // ── 数据与条款 ──
        SectionLabel("数据")
        DsGroup {
            // 对冲「买的是一张服务年票」的停服恐惧：能整包带走。存储期限说实话。
            DsRow("你的数据", subtitle = "原始音频保留 12 个月，转写与判断永久保留。",
                  onClick = { onOpenWeb("/zh/settings", "导出全部") })
            LinkButton(onClick = { onOpenWeb("/zh/settings", "导出全部") },
                       contentPadding = DS.Pad.row, modifier = Modifier.padding(bottom = DS.Rhythm.tight)) { Text("导出全部 · 网页版") }
            RowDivider()
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
            detail = "正在录的会先停下。没传完的录音留在手机上，登录回这个账号后接着传。",
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

/** 定时聆听那一行要显示的。每次进「我的」读一次。 */
@Composable
private fun rememberListenSchedule(ctx: android.content.Context): com.qiuyiwu.shennao.record.ListenSchedule.Config {
    return remember { com.qiuyiwu.shennao.record.ListenSchedule.load(ctx) }
}
private val com.qiuyiwu.shennao.record.ListenSchedule.Config.summary: String
    get() = com.qiuyiwu.shennao.record.ListenSchedule.summary(this)
