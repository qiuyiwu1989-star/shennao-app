package com.qiuyiwu.shennao

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qiuyiwu.shennao.record.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * 「历史」：这台手机上录过的会，以及它们到底送到深脑没有。
 *
 * 这一页存在的理由只有一个：**让「还没送到」变得看得见**。
 * 在此之前，一条卡住的录音只在服务器日志里有痕迹——用户看到的是
 * 「我录完了」，而深脑那边什么都没有。今天就是这么丢的。
 *
 * 已经送达的会不在这里列：那属于网页版（有转写、播放、认人），
 * 在 App 里再实现一遍必然比网页那份旧。这一页只管「在路上的」。
 */

data class LocalSession(
    val dir: String,
    val meta: SessionMeta,
    val total: Int,
    val done: Int,
    val recording: Int,
) {
    val bytesLeftLabel: String get() = "$done/$total 段已送达"
}

@Composable
fun HistoryScreen(
    client: DeepBrainClient,
    onRecord: () -> Unit,
    onOpen: (String) -> Unit,
    /** 点灵魂卡状态条：进扫描/连接/同步那一屏 */
    onOpenBle: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val cache = remember { Cache(File(ctx.cacheDir, "mobile")) }
    var stale by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<LocalSession>>(emptyList()) }
    var served by remember { mutableStateOf<List<SessionCard>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // 灵魂卡那一头的状态。它是 Service 上的 @Volatile 字段，和本地/服务端两份一起轮询：
    // 三份合起来才是完整的一条链——卡里还没导出来的、手机上还没传出去的、服务端后几站。
    var card by remember { mutableStateOf(CardStatus.read()) }
    /** 来源分段。null = 全部。服务端没给 source 时分段行不显示，列表照常。 */
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    val notice = LocalNotice.current
    var orphan by remember { mutableStateOf(com.qiuyiwu.shennao.record.OrphanNotice.peek(ctx)) }

    val onDelete: (String) -> Unit = { id ->
        scope.launch {
            val d = withContext(Dispatchers.IO) { client.deleteRecording(id) }
            if (d !is ApiResult.Ok) notice("删不掉：" + ((d as? ApiResult.Failed)?.message ?: "登录失效了"))
            val r = withContext(Dispatchers.IO) { client.sessions() }
            if (r is ApiResult.Ok) served = r.value
        }
    }

    LaunchedEffect(Unit) {
        // 本地状态 5 秒扫一次（便宜）；服务端 30 秒问一次，或者本地刚有段送达时立刻问。
        // 以前每 5 秒打一次网络，这一页开着就是每分钟 12 次（012 P2-1）。
        var tick = 0
        var lastPendingLocal = -1
        while (true) {
            rows = withContext(Dispatchers.IO) { scan(File(ctx.filesDir, "recordings")) }
            card = CardStatus.read()
            val pendingLocal = rows.sumOf { it.total - it.done }
            val justDelivered = lastPendingLocal >= 0 && pendingLocal < lastPendingLocal
            lastPendingLocal = pendingLocal
            val askServer = tick == 0 || tick % 6 == 0 || justDelivered
            tick++
            if (!askServer) { delay(5000); continue }
            // 服务端那份查得慢一些，但它才知道转写和分析走到哪了。
            // 两份合起来才是完整的一条链：本地管「传没传出去」，服务端管「后面几站」。
            val r = withContext(Dispatchers.IO) { client.sessionsWithRaw() }
            if (r is ApiResult.Ok) {
                val (rows2, raw) = r.value
                served = rows2
                stale = null
                withContext(Dispatchers.IO) { cache.save(Cache.SESSIONS, raw) }
            } else if (served.isEmpty()) {
                // 没网就拿上次的。这一页的价值是「让卡住变得看得见」，
                // 而没网时最容易让人以为「东西都送到了」。
                val c = withContext(Dispatchers.IO) { cache.load(Cache.SESSIONS) }
                if (c != null) {
                    served = runCatching { SessionsParser.parse(c.body) }.getOrDefault(emptyList())
                    if (served.isNotEmpty()) {
                        stale = Cache.staleLabel(c.savedAt, System.currentTimeMillis()) ?: "离线 · 刚才的"
                    }
                }
            }
            loaded = true
            delay(5000)
        }
    }

    Column(Modifier.fillMaxSize()) {
    stale?.let { StaleBanner(it) }
    Refreshable(onRefresh = {
        rows = withContext(Dispatchers.IO) { scan(File(ctx.filesDir, "recordings")) }
        val r = withContext(Dispatchers.IO) { client.sessions() }
        if (r is ApiResult.Ok) { served = r.value; stale = null }
    }) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = DS.Pad.default,
        verticalArrangement = Arrangement.spacedBy(DS.Rhythm.element),
    ) {
        item {
            Text("记录", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(DS.Rhythm.tight))
            Text("每一场走到哪一站，都在这里。",
                 style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(DS.Rhythm.element))
            // 灵魂卡那一头。「有什么」和「进来了没有」是同一个问题的两面，
            // 卡的状态不放在这里，用户就得去另一栏对账。
            CardBar(card, onOpenBle)
        }
        // 录音被系统杀掉过：说一句，点「知道了」就走（012 P1-7）
        orphan?.let { at -> item {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = DS.Radius.card, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(DS.Pad.tight)) {
                    Text(com.qiuyiwu.shennao.record.OrphanNotice.line(at), style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { com.qiuyiwu.shennao.record.OrphanNotice.clear(ctx); orphan = null }) { Text("知道了") }
                }
            }
        } }

        // 还在这台手机上的排最前：它们是唯一可能丢的
        if (rows.isNotEmpty()) {
            item { MiniHead("还在手机上") }
            items(rows, key = { "l" + it.dir }) { s ->
                SessionRow(s) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            FileVault(File(ctx.filesDir, "recordings")).deleteSession(s.dir)
                        }
                        rows = withContext(Dispatchers.IO) { scan(File(ctx.filesDir, "recordings")) }
                    }
                }
            }
        }

        if (served.isNotEmpty()) {
            item { MiniHead("已经送到深脑") }
            // 按来源分段：灵魂卡 / 手机 / 分享来的。每个入口各占一格，不做主次视觉差——
            // 一个只用手机的人，界面上不该处处看见「你还没有灵魂卡」。
            if (SourceFilter.available(served)) item {
                Row(Modifier.fillMaxWidth().padding(bottom = DS.Rhythm.tight),
                    horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.tight)) {
                    SourceFilter.options.forEach { (key, label) ->
                        FilterChip(selected = sourceFilter == key, onClick = { sourceFilter = key },
                                   label = { Text(label) })
                    }
                }
            }
            val shown = SourceFilter.apply(served, sourceFilter)
            if (shown.isEmpty()) item {
                Text("这个来源还没有送到深脑的。", style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown, key = { "s" + it.sessionId }) { s -> ServedRow(s, onOpen, onDelete) }
        }

        if (!loaded) item { SkeletonList(3) }
        else if (rows.isEmpty() && served.isEmpty()) item {
            Empty("还没有录过", "录一场会，它会自己走完转写和分析。", "录一场", onRecord)
        }

        // 这本账的边界必须写出来。9-03 那次事故：换了账号，旧账本说「导过了」，
        // 用户把「台账里没有」读成「没导过」。不写这一句，同一个坑再来一次。
        if (loaded) item {
            Spacer(Modifier.height(DS.Rhythm.inner))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(DS.Rhythm.element))
            Text(
                "这里看不到：换账号之前导的、装这个版本之前导的。没落这本账，不代表没导过。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }
    }
}

@Composable
private fun MiniHead(t: String) {
    Text(t, style = MaterialTheme.typography.labelMedium,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
         modifier = Modifier.padding(top = DS.Rhythm.tight, bottom = 2.dp))
}

/**
 * 一场已送达的会，显示它走到链路的第几站。
 *
 * 四站画成一条线而不是一个状态词：状态词只说「现在在哪」，
 * 一条线还说了「还差几步」——而用户真正想知道的是后者。
 */
@Composable
fun ServedRow(s: SessionCard, onOpen: (String) -> Unit, onDelete: ((String) -> Unit)? = null) {
    val failed = s.stage == Stage.FAILED
    // 可点的 Card 那个重载是 onClick 在前、modifier 在后，
    // 按 Modifier 优先的习惯写会匹配不上。
    DsCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { s.transcriptId?.let(onOpen) },
    ) {
        Column(Modifier.padding(DS.Pad.tight)) {
            // 标题在前、一行紧凑的元信息在下——参考的是"智能纪要"那类列表
            // 的读法：先给结论（这是什么），日期和时长是找它的时候才用得上的。
            // 之前是标题和时长并排、日期又落到卡片最下面，读的时候视线要
            // 跳两趟才拼出"这是哪场、什么时候"。
            Text(s.title, style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold)
            val meta = listOfNotNull(
                s.startedAt?.let { day(it) },
                s.durationMs?.let { "${it / 60000} 分钟" },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 分析完的不用再看"走到第几步了"——四个绿勾对一场已经办完的事
            // 只是噪音，参考设计对处理完的条目就是这么干净、什么流程状态都不带。
            if (s.stage != Stage.ANALYZED) {
                Spacer(Modifier.height(DS.Rhythm.element))
                Chain(s.stage)
            }
            if (failed && s.problem != null) {
                Spacer(Modifier.height(DS.Rhythm.element))
                // 失败的原因要说清楚**该怎么办**，而不只是「出错了」。
                // 服务端已经把内部术语翻成人话了，这里照着显示。
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(s.problem, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onErrorContainer,
                         modifier = Modifier.padding(DS.Pad.tight))
                }
                if (onDelete != null) {
                    // 给一条出路。一条救不回来的录音一直挂在列表上，
                    // 用户每次打开都要重新判断一次「这个要不要管」。
                    var confirming by remember { mutableStateOf(false) }
                    TextButton(onClick = { confirming = true }) {
                        Text("删掉这条", color = MaterialTheme.colorScheme.error)
                    }
                    if (confirming) ConfirmDialog(
                        title = "删掉这条录音？",
                        // 说清楚做完会怎样，而不是「确定删除吗」——那是句废话
                        detail = "音频会从深脑删除，找不回来。这条已经处理失败，" +
                                 "删掉不影响其它录音。",
                        confirmLabel = "删掉",
                        destructive = true,
                        onConfirm = { onDelete(s.sessionId) },
                        onDismiss = { confirming = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun Chain(stage: Stage) {
    val stops = listOf("录下来", "送到", "转写完", "分析完")
    val reached = when (stage) {
        Stage.RECORDED -> 1
        Stage.DELIVERED -> 2
        Stage.TRANSCRIBED -> 3
        Stage.ANALYZED -> 4
        else -> 0
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        stops.forEachIndexed { i, name ->
            val on = i < reached
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    stage == Stage.FAILED && i == reached -> MaterialTheme.colorScheme.error
                    on -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (i < stops.lastIndex) {
                Text(" → ", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun day(iso: String): String = runCatching {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    val d = f.parse(iso.take(19))!!
    java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(d)
}.getOrElse { iso.take(10) }

@Composable
fun SessionRow(s: LocalSession, onDelete: () -> Unit) {
    DsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(DS.Pad.tight)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.meta.title, style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        s.recording > 0 -> "正在录"
                        s.meta.finished -> "上传中"
                        else -> "等待收尾"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(DS.Rhythm.tight))
            LinearProgressIndicator(
                progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(DS.Rhythm.tight))
            Text("${s.bytesLeftLabel} · 开始于 ${stamp(s.meta.startedAtEpochMs)}",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 正在录的那一条绝不能给删除入口——这是唯一一份还没落地的音频。
            // 2026-09-02 用户实录：有两条卡在这里几天传不上去（早年时间轴
            // 断裂的老 bug，服务端永远不会接受），一直占着「还在手机上」
            // 这一栏，没有任何办法清掉，只能眼睁睁看着。
            if (s.recording == 0) {
                var confirming by remember { mutableStateOf(false) }
                Spacer(Modifier.height(DS.Rhythm.tight))
                TextButton(onClick = { confirming = true }) {
                    Text("删掉这条", color = MaterialTheme.colorScheme.error)
                }
                if (confirming) ConfirmDialog(
                    title = "删掉这条录音？",
                    detail = "音频只存在这台手机上，删了找不回来。",
                    confirmLabel = "删掉",
                    destructive = true,
                    onConfirm = { onDelete() },
                    onDismiss = { confirming = false },
                )
            }
        }
    }
}

private fun scan(root: File): List<LocalSession> {
    val vault = FileVault(root)
    return vault.sessions().mapNotNull { d ->
        val meta = vault.readMeta(d) ?: return@mapNotNull null
        val segs = vault.segments(d)
        LocalSession(
            dir = d, meta = meta, total = segs.size,
            done = segs.count { it.state == Segment.State.UPLOADED },
            recording = segs.count { it.state == Segment.State.RECORDING },
        )
    }.sortedByDescending { it.meta.startedAtEpochMs }
}

private fun stamp(ms: Long): String =
    java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))

/**
 * 灵魂卡此刻的一句话。纯逻辑，JVM 可测——这一行会被反复改措辞。
 *
 * 只说一件事：连没连上、在不在同步。不给百分比（人要的是「我能不能走开」），
 * 不显示成故障（卡不在身边是常态，标成红的会天天吓人）。
 */
data class CardStatus(val line: String, val busy: Boolean, val attention: Boolean) {
    companion object {
        fun read(): CardStatus = of(
            com.qiuyiwu.shennao.ble.BleImportService.conn,
            com.qiuyiwu.shennao.ble.BleImportService.state,
            com.qiuyiwu.shennao.ble.BleImportService.syncDone,
            com.qiuyiwu.shennao.ble.BleImportService.syncTotal,
            com.qiuyiwu.shennao.ble.BleImportService.lastError,
        )

        fun of(
            conn: com.qiuyiwu.shennao.ble.BleState,
            state: com.qiuyiwu.shennao.ble.ImportState,
            syncDone: Int,
            syncTotal: Int,
            lastError: String?,
        ): CardStatus = when {
            conn == com.qiuyiwu.shennao.ble.BleState.FAILED ->
                CardStatus("灵魂卡 · " + (lastError ?: "连不上"), busy = false, attention = true)
            conn == com.qiuyiwu.shennao.ble.BleState.SCANNING ||
                conn == com.qiuyiwu.shennao.ble.BleState.CONNECTING ->
                CardStatus("正在找灵魂卡…", busy = true, attention = false)
            conn == com.qiuyiwu.shennao.ble.BleState.READY && syncTotal > 0 && syncDone < syncTotal ->
                CardStatus("灵魂卡 · 正在同步 ${syncDone + 1}/$syncTotal，可以直接切走", busy = true, attention = false)
            conn == com.qiuyiwu.shennao.ble.BleState.READY &&
                state is com.qiuyiwu.shennao.ble.ImportState.Downloading ->
                CardStatus("灵魂卡 · 正在导入", busy = true, attention = false)
            conn == com.qiuyiwu.shennao.ble.BleState.READY ->
                CardStatus("灵魂卡 · 已连接，没有待同步的", busy = false, attention = false)
            else -> CardStatus("灵魂卡 · 未连接", busy = false, attention = false)
        }
    }
}

@Composable
private fun CardBar(c: CardStatus, onOpen: () -> Unit) {
    DsCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        // 间距一律走 DS 档：RhythmGuardTest 钉着裸 dp 的基线，只准降不准涨。
        Row(Modifier.padding(DS.Pad.tight), verticalAlignment = Alignment.CenterVertically) {
            if (c.busy) CircularProgressIndicator(Modifier.size(DS.Rhythm.element))
            else Box(
                Modifier.size(DS.Rhythm.tight).background(
                    if (c.attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    androidx.compose.foundation.shape.CircleShape,
                )
            )
            Spacer(Modifier.width(DS.Rhythm.tight))
            Text(c.line, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
    Spacer(Modifier.height(DS.Rhythm.element))
}

/** 记录页的来源分段。纯逻辑，JVM 可测。 */
internal object SourceFilter {
    /** 键 → 标签。null 键 = 全部。 */
    val options: List<Pair<String?, String>> = listOf(null to "全部", "card" to "灵魂卡", "phone" to "手机", "share" to "分享来的")

    /** 服务端没派生 source（还没部署）就不显示分段行——不画点了没反应的东西。 */
    fun available(rows: List<SessionCard>): Boolean = rows.any { it.source != null }

    fun apply(rows: List<SessionCard>, filter: String?): List<SessionCard> =
        if (filter == null) rows else rows.filter { it.source == filter }
}
