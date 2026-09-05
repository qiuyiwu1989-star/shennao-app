package com.qiuyiwu.shennao

import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * 「这场会讲了什么」。
 *
 * 只给一屏读得完的：一段摘要、这场会的判断、谁在场、谁承诺了什么。
 * 逐句转写不在这里——那要滚很久，而网页那份有播放对齐和认说话人，
 * 在 App 里再实现一遍必然更旧。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MeetingScreen(
    client: DeepBrainClient,
    transcriptId: String,
    onBack: () -> Unit,
    onOpenWeb: ((path: String, title: String) -> Unit)? = null,
    /** 进认人页。null = 手机上还没有认人（服务端没升级） */
    onClaimSpeakers: (() -> Unit)? = null,
    /** 判断反馈：atomId × up / down / hide */
    onFeedback: (String, String) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meeting by remember { mutableStateOf<Meeting?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var shareNote by remember { mutableStateOf<String?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    /*
     * 三个 tab：判断 / 承诺 / 原话。
     *
     * Plaud 用「来源 / 笔记」，得到用六个横向 tab——原件与生成物分开是这个品类的通行结构。
     * 对深脑它不只是省空间：**它是证据链的骨架**，判断在这边、原话在那边，
     * 一条判断凭什么，跳过去就能看。
     *
     * 第一版「原话」tab 放的是这场会里**被引用的片段**（每条判断/承诺自带的 quote）。
     * 逐句转写和「定位到秒」要等 mobile/transcript 返回 segments（Phase 2）——
     * 数据没到就不画，一个滚不动的假逐句比没有更糟。
     */
    var tab by remember { mutableStateOf(MeetingTab.JUDGMENTS) }
    val notice = LocalNotice.current
    val listState = rememberLazyListState()
    /** 原话 tab 里高亮哪一句（从判断的「依据」跳过来的那句） */
    var highlight by remember { mutableStateOf<Int?>(null) }
    /**
     * 「依据 →」：切到原话 tab，滚到那一句并高亮。
     * 证据链的落地动作——一秒回原文是这类产品最难被抄的信任动作。
     * 逐句是服务端 2026-09-05 才给的；没有逐句时退回被引用的片段列表，不跳。
     */
    fun jumpToQuote(quote: String) {
        val m = meeting ?: return
        val idx = MeetingTabs.lineIndexFor(m.segments, quote) ?: run { tab = MeetingTab.QUOTES; return }
        tab = MeetingTab.QUOTES; highlight = idx
        scope.launch { listState.animateScrollToItem(HEADER_ITEMS + idx) }
    }

    // 重新取数：手动排上分析之后要能看到状态从「没有分析」变成「在跑」。
    // 不刷新的话用户点完按钮屏幕一动不动，只能反复点，而每一次都会
    // 撞上服务端的幂等闸——他看到的是「点了没用」。
    suspend fun reload() {
        when (val r = withContext(Dispatchers.IO) { client.meeting(transcriptId) }) {
            is ApiResult.Ok -> meeting = r.value
            else -> Unit
        }
    }

    LaunchedEffect(transcriptId) {
        when (val r = withContext(Dispatchers.IO) { client.meeting(transcriptId) }) {
            is ApiResult.Ok -> meeting = r.value
            is ApiResult.Failed -> error = r.message
            else -> error = "登录失效了"
        }
    }

    DetailPage(
        onBack = onBack,
        state = listState,
        actions = {
            // 分享。走系统的分享面板，不自己做选择器——
            // 用户已经知道怎么用它，而且我们做的那个永远比系统的少几个入口。
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !sharing && meeting != null,
                    onClick = {
                        sharing = true; shareNote = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { client.share(transcriptId) }
                            sharing = false
                            when (r) {
                                is ApiResult.Ok -> {
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT,
                                                 "${meeting?.title ?: "一场会"}\n${r.value}")
                                    }
                                    ctx.startActivity(android.content.Intent.createChooser(send, "分享给"))
                                }
                                is ApiResult.Failed -> shareNote = r.message
                                else -> shareNote = "登录失效了"
                            }
                        }
                    },
            ) { Text(if (sharing) "生成中…" else "分享") }
        },
    ) {
        shareNote?.let { n -> item {
            Text(n, style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.error)
        } }

        val m = meeting
        when {
            error != null -> item {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            m == null -> item {
                Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                item {
                    Text(m.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(DS.Rhythm.tight))
                    Text(
                        listOfNotNull(
                            m.durationSec?.let { "${it / 60} 分钟" },
                            m.speakers.size.takeIf { it > 0 }?.let { "$it 人在场" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(DS.Rhythm.element))
                }

                item {
                    val t = MeetingTabs.of(m)
                    TabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                        MeetingTab.entries.forEach { mt ->
                            Tab(
                                selected = tab == mt,
                                onClick = { tab = mt },
                                text = { Text(mt.label(t), style = MaterialTheme.typography.titleSmall) },
                            )
                        }
                    }
                    Spacer(Modifier.height(DS.Rhythm.element))
                }
                if (tab == MeetingTab.JUDGMENTS) item {
                    DsCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(DS.Pad.tight)) {
                            Text("这场会", style = MaterialTheme.typography.titleSmall,
                                 fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(DS.Rhythm.tight))
                            // 摘要是 Markdown。当纯文本显示的话，屏幕上就是一堆 ## 和 **，
                            // 用户看到的是「这个 App 坏了」。
                            if (m.summary != null) MarkdownText(m.summary)
                            else Text(
                                // 没有摘要 = 分析还没跑完。说清楚，不要显示一片空白——
                                // 空白会被理解成「这场会什么都没讲」。
                                "分析还没跑完，等一会儿再来看。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 分析正文。这才是「深脑读出了什么」的主体——
                // 之前只给了一段摘要，等于把大半藏起来了。
                if (tab == MeetingTab.JUDGMENTS) m.analysis?.let { a ->
                    if (a.methods.isNotEmpty()) item {
                        Spacer(Modifier.height(DS.Rhythm.tight))
                        // 一场分析常常是好几个方法合出来的。只显示一个，
                        // 用户会以为深脑只用了一种看法。
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.tight),
                        ) {
                            a.methods.forEach { m2 ->
                                AssistChip(onClick = {}, label = {
                                    Text(m2, style = MaterialTheme.typography.labelSmall)
                                })
                            }
                        }
                        a.routingReason?.let { r ->
                            Spacer(Modifier.height(DS.Rhythm.tight))
                            Text("为什么选这几个方法：$r",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (a.markdown != null) item {
                        SectionHead("分析", "深脑读出来的")
                        DsCard(Modifier.fillMaxWidth()) {
                            MarkdownText(a.markdown, Modifier.padding(DS.Pad.default))
                        }
                    } else item {
                        // 「在跑」和「跑挂了」必须分开说。
                        // 这里原来写的是 `a.status != "completed"`——那个枚举值
                        // 根本不存在（真值是 done），条件永远为真，于是一场
                        // **失败**的分析会永远显示成「还在跑」，用户就一直等。
                        // 枚举见 0001_init.sql：queued / routing / analyzing /
                        // self_check / persisting / done / failed。
                        if (a.status == "failed") {
                            Text("这场分析失败了。可以重跑一次。",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("分析还在跑（${a.status}）",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // **没有分析时也要说话。**
                // 原来这一整块挂在 `m.analysis?.let`下面，分析为 null 时
                // 屏幕上一个字都没有——一条 60 秒的录音传上来、转写好了、
                // 详情页却什么都不显示，用户唯一能得出的结论是「这东西坏了」。
                // 实际上是按「不到 5 分钟不自动分析」跳过的。
                if (tab == MeetingTab.JUDGMENTS && m.analysis == null) item {
                    SectionHead("分析", "这条为什么没有")
                    DsCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(DS.Pad.default)) {
                            Text(m.analysisAbsentReason ?: "这条还没有分析。",
                                 style = MaterialTheme.typography.bodyLarge,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(DS.Rhythm.element))
                            // 门槛的意思是「默认不花这个钱」，不是「不许分析」。
                            // 所以出口必须在这里——用户正是在这一刻想要它。
                            // 按钮上写明要花积分：花钱的动作不该让人点完才知道。
                            Button(
                                enabled = !analyzing,
                                modifier = Modifier.heightIn(min = 48.dp),
                                onClick = {
                                    analyzing = true
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { client.analyze(m.transcriptId) }
                                        analyzing = false
                                        when (r) {
                                            is ApiResult.Ok -> { notice("排上了，分析在跑"); reload() }
                                            is ApiResult.Failed -> notice(r.message)
                                            else -> notice("登录失效了")
                                        }
                                    }
                                },
                            ) { Text(if (analyzing) "排队中…" else "还是分析这条（消耗积分）") }
                        }
                    }
                }

                // ── 判断 tab ──
                if (tab == MeetingTab.JUDGMENTS) {
                    if (m.atoms.isNotEmpty()) {
                        item { SectionHead("读出来的判断", "决定、信号、矛盾") }
                        items(m.atoms, key = { "a" + it.id }) { a -> AtomCard(a, onJump = { jumpToQuote(a.quote) }, onFeedback = { v -> onFeedback(a.id, v) }) }
                    } else if (m.analysis != null) item {
                        Empty("这场没读出判断", "有时候一场会就是没有决定、没有分歧。那也是一个结论。")
                    }
                }

                // ── 承诺 tab ──
                if (tab == MeetingTab.COMMITMENTS) {
                    if (m.commitments.isEmpty()) item {
                        Empty("这场会里没人答应什么", "有人说出口「下周给你」这类话时，会出现在这里。")
                    } else {
                        item { SectionHead("这场会里的承诺", "别人说出口、还没有下文的。在这里也能落账。") }
                        items(m.commitments, key = { "c" + it.id }) { c ->
                            MeetingCommitmentCard(c) { action ->
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { client.settleCommitment(c.id, action) }
                                    // 失败要说出来。乐观更新用起来顺手，但失败必须收回，否则账上没这一笔而用户以为记过了。
                                    if (r !is ApiResult.Ok) {
                                        notice(when (r) {
                                            is ApiResult.Failed -> "没记上：${r.message}"
                                            is ApiResult.Unauthorized -> "没记上：登录失效了"
                                            else -> "没记上，请再点一次"
                                        })
                                        reload()
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 原话 tab ──
                if (tab == MeetingTab.QUOTES) {
                    // 还有「说话人N」这种没认的标签就给认人入口。判据只看标签形状，不另发请求。
                    val unnamed = m.speakers.count { it.matches(Regex("""说话人\s*\d+""")) }
                    if (unnamed > 0 && onClaimSpeakers != null) item {
                        DsCard(Modifier.fillMaxWidth(), onClick = onClaimSpeakers) {
                            Row(Modifier.padding(DS.Pad.tight), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("有 $unnamed 个说话人还不知道是谁", style = MaterialTheme.typography.titleSmall)
                                    Text("认了之后，关于他的判断和承诺才能归到人头上",
                                         style = MaterialTheme.typography.bodySmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("认人 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(DS.Rhythm.tight))
                    }
                    if (m.speakers.isNotEmpty()) item {
                        Text("在场：" + m.speakers.joinToString("、"),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val quotes = MeetingTabs.quotesOf(m)
                    if (m.segments.isNotEmpty()) {
                        item { SectionHead("逐句 · ${m.segments.size} 句", "从判断的「依据」跳过来的那句会亮着。逐句校对在网页版。") }
                        itemsIndexed(m.segments, key = { i, _ -> "l$i" }) { i, l ->
                            val hot = highlight == i
                            Surface(
                                color = if (hot) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                shape = DS.Radius.control, modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(DS.Pad.tight)) {
                                    Text(listOfNotNull(l.startMs?.let { fmtClock(it) }, l.speaker).joinToString(" · "),
                                         style = MaterialTheme.typography.labelMedium,
                                         color = if (hot) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(DS.Rhythm.tight))
                                    Text(l.text, style = MaterialTheme.typography.bodyLarge,
                                         color = if (hot) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            Spacer(Modifier.height(DS.Rhythm.tight))
                        }
                    } else if (quotes.isEmpty()) item {
                        Empty("这场会还没有被引用的原话", "分析跑完之后，每条判断和承诺依据的那句话会列在这里。")
                    } else {
                        item { SectionHead("被引用的原话 · ${quotes.size} 处", "每条判断和承诺凭的就是这些。逐句转写在网页版。") }
                        items(quotes, key = { "q" + it.key }) { q ->
                            DsCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(DS.Pad.tight)) {
                                    Text(q.who, style = MaterialTheme.typography.labelMedium,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(DS.Rhythm.tight))
                                    Text("「${q.text}」", style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.height(DS.Rhythm.tight))
                                    Text(q.supports, style = MaterialTheme.typography.bodySmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(DS.Rhythm.element))
                    OutlinedButton(
                        onClick = {
                            val path = "/zh/transcript/${m.transcriptId}"
                            // 在 App 里开（带登录态）。原来是甩给系统浏览器一个裸链接，
                            // 用户在 Chrome 里多半没登录，看到的是登录页——
                            // 这个按钮的实际效果成了「把人踢出 App，再要求他重登一次」。
                            if (onOpenWeb != null) onOpenWeb(path, m.title)
                            else ctx.startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("${BuildConfig.API_BASE}$path")))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("在网页版看逐句转写 ↗") }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(title: String, hint: String) {
    Column(Modifier.padding(top = 14.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AtomCard(a: MeetingAtom, onJump: () -> Unit = {}, onFeedback: (String) -> Unit = {}) {
    // 猜想默认折叠——折叠的是原话那一串看起来像证据的上下文。判据见 TodayScreen.foldByDefault。
    var open by remember(a.id) { mutableStateOf(!foldByDefault(a.epistemic)) }
    var said by remember(a.id) { mutableStateOf<String?>(null) }
    if (said == "hide") return
    DsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(DS.Pad.tight)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel(a.atomType), style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                // 认知等级必须显示。手机是一扫而过的场景，
                // 一条「猜想」会被当成事实——比坐在电脑前更危险。
                Text(epistemicLabel(a.epistemic), style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(DS.Rhythm.tight))
            Text(a.statement, style = MaterialTheme.typography.bodyLarge)
            if (!open) {
                TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) { Text("看它凭什么这么说") }
            } else if (a.quote.isNotBlank()) {
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text("「${a.quote}」", style = MaterialTheme.typography.bodyLarge,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 依据 →：跳到原话那一句。这是证据链在界面上的兑现。
                TextButton(onClick = onJump, contentPadding = PaddingValues(0.dp)) { Text("回到原话 →") }
                FeedbackRow(said) { v -> said = v; onFeedback(v) }
            } else if (foldByDefault(a.epistemic)) {
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text("没有直接原话支撑。它来自跨录音联想。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 承诺卡：这一场里也能落账，不用回首屏找。 */
@Composable
private fun MeetingCommitmentCard(c: Commitment, onSettle: (String) -> Unit) {
    var done by remember(c.id) { mutableStateOf<String?>(if (c.status == "open") null else c.status) }
    DsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(DS.Pad.tight)) {
            Row {
                Text(c.speakerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                c.dueDate?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(DS.Rhythm.tight))
            Text(c.statement, style = MaterialTheme.typography.bodyLarge)
            if (c.quote.isNotBlank() && c.quote != c.statement) {
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text("「${c.quote}」", style = MaterialTheme.typography.bodyLarge,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(DS.Rhythm.tight))
            if (done == null) Row {
                TextButton(onClick = { done = "kept"; onSettle("kept") }) { Text("兑现了") }
                TextButton(onClick = { done = "cancelled"; onSettle("cancelled") }) { Text("取消了") }
            } else Text(
                "已记：" + when (done) { "kept" -> "兑现了"; "cancelled" -> "取消了"; else -> done },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 纯逻辑，JVM 可测 ────────────────────────────────────────────

/** 原话 tab 里逐句列表之前固定有几个 item：标题、tab 行、（在场）、（认人卡）、SectionHead。按最多算，滚过头一格无妨。 */
private const val HEADER_ITEMS = 5

enum class MeetingTab { JUDGMENTS, COMMITMENTS, QUOTES;
    fun label(t: MeetingTabs) = when (this) {
        JUDGMENTS -> if (t.judgments > 0) "判断 ${t.judgments}" else "判断"
        COMMITMENTS -> if (t.commitments > 0) "承诺 ${t.commitments}" else "承诺"
        QUOTES -> if (t.quotes > 0) "原话 ${t.quotes}" else "原话"
    }
}

/** 一条被引用的原话：谁说的、说了什么、它支撑哪一条。 */
data class Quote(val key: String, val who: String, val text: String, val supports: String)

/** 三个 tab 各有几条。数字直接标在标签上——不点进去就知道哪一栏有东西。 */
data class MeetingTabs(val judgments: Int, val commitments: Int, val quotes: Int) {
    companion object {
        fun of(m: Meeting) = MeetingTabs(m.atoms.size, m.commitments.size, quotesOf(m).size)

        /**
         * 原话 tab 的内容：这场会里**被引用的**片段。
         *
         * 来源两处：判断自带的 quote、承诺自带的 quote。空的不收——
         * 猜想常常没有原话，那是它的属性，不该在原话 tab 里占一行空白。
         * 同一句被多条引用只列一次，注明支撑几条。
         */
        /**
         * 「依据 →」要跳到哪一句。引用是从原话里抠出来的，可能被 ASR 修饰过、可能跨句，
         * 所以先找包含整句的，再找包含前 12 个字的；都没有就 null——**不跳到一个错的地方**，
         * 跳错比不跳更伤信任。
         */
        fun lineIndexFor(lines: List<Line>, quote: String): Int? {
            val q = quote.trim().trim('「', '」', '"', '“', '”')
            // 两三个字的片段谁都能对上，那是巧合不是证据——不够 6 个字一律不猜
            if (q.length < 6 || lines.isEmpty()) return null
            lines.indexOfFirst { it.text.contains(q) }.takeIf { it >= 0 }?.let { return it }
            val head = q.take(12)
            if (head.length < 6) return null
            return lines.indexOfFirst { it.text.contains(head) }.takeIf { it >= 0 }
        }

        fun quotesOf(m: Meeting): List<Quote> {
            val byText = LinkedHashMap<String, Quote>()
            m.commitments.filter { it.quote.isNotBlank() }.forEach { c ->
                byText.putIfAbsent("c" + c.quote, Quote("c" + c.id, c.speakerName, c.quote, "支撑：${c.speakerName}的承诺"))
            }
            m.atoms.filter { it.quote.isNotBlank() }.forEach { a ->
                val who = a.subject?.takeIf { it.isNotBlank() } ?: "会上"
                val label = "支撑：" + a.statement.take(24) + if (a.statement.length > 24) "…" else ""
                val k = "a" + a.quote
                val old = byText[k]
                byText[k] = if (old == null) Quote("a" + a.id, who, a.quote, label) else old.copy(supports = old.supports + "；另一条")
            }
            return byText.values.toList()
        }
    }
}

private fun typeLabel(t: String) = when (t) {
    "decision" -> "决定"; "signal" -> "信号"; "contradiction" -> "矛盾"
    "principle" -> "原则"; "judgment" -> "人物判断"; "fact" -> "事实"
    "open_question" -> "待解"; else -> t.ifBlank { "判断" }
}

private fun epistemicLabel(e: String) = when (e) {
    "attested" -> "有原话"; "inferred" -> "推断"; "conjecture" -> "猜想"
    else -> e.ifBlank { "—" }
}
