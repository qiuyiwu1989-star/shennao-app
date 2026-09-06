package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 问深脑。
 *
 * **这一屏原来是「搜索」。** 换掉它的理由：人在路上想起来的不是关键词，
 * 是问题——「上次陈总到底答应了什么」。搜索把「翻译成关键词」这件事
 * 丢给了用户，而他正一只手扶着地铁扶手。
 *
 * 但关键词搜索没有被删掉，它被挪到了它真正有用的那一刻：
 * **深脑说「依据不够」的时候**。那时用户最需要的正是「那库里到底提到过
 * 什么」，而这恰恰是关键词能答、语义答不了的。
 */
@Composable
fun AskScreen(client: DeepBrainClient, onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var insufficient by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fallback by remember { mutableStateOf<List<Hit>>(emptyList()) }
    val scope = rememberCoroutineScope()
    // 搜索页以前没有入口（012 P1-1 的一半）；问不出来的时候换关键词搜
    var searchMode by remember { mutableStateOf(false) }
    // 切走就掐断流式连接，否则阻塞读要挂到 180 秒（012 P2-5）
    val cancelAsk = remember { arrayOfNulls<() -> Unit>(1) }
    DisposableEffect(Unit) { onDispose { cancelAsk[0]?.invoke() } }
    if (searchMode) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onBack = { searchMode = false })
            SearchScreen(client, onOpen)
        }
        return
    }

    fun send(question0: String = q) {
        val question = question0.trim()
        if (question.length < 2 || busy) return
        q = question
        asked = question; answer = ""; step = null; mode = null
        failed = null; insufficient = false; fallback = emptyList()
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                client.ask(question, onCancel = { c -> cancelAsk[0] = c }) { e ->
                    when (e) {
                        is Ask.Event.Mode -> mode = e.mode
                        is Ask.Event.Step -> step = e.what
                        is Ask.Event.Token -> { answer += e.delta; step = null }
                        is Ask.Event.Insufficient -> insufficient = true
                        is Ask.Event.Done -> busy = false
                        is Ask.Event.Failed -> { failed = e.message; busy = false }
                    }
                }
            }
            busy = false
            // 「依据不够」时才去跑一次关键词搜索。
            // 平时不跑：一次没人看的检索是白花的往返，而在会议室的网络下
            // 它还会拖慢真正要紧的那条流。
            if (insufficient) {
                val r = withContext(Dispatchers.IO) { client.search(question) }
                fallback = if (r is ApiResult.Ok) r.value else emptyList()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(DS.Pad.screen)) {
        Spacer(Modifier.height(DS.Rhythm.inner))
        /*
         * 输入框是填充底、不描边、圆角——和这一屏的其它东西一家。
         * 之前是一圈细线的 OutlinedTextField，右边一个「问」字：深底上又是一个空心框。
         * 发送用图标：它在有字可发时才亮起来。
         */
        val canSend = !busy && q.trim().length >= 2
        TextField(
            value = q, onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("上次那件事，他到底怎么说的？") },
            singleLine = true,
            enabled = !busy,
            shape = DS.Radius.card,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            // 回车就发。手机上多一个「提问」按钮就是多一次拇指移动。
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                if (busy) CircularProgressIndicator(Modifier.size(DS.Size.icon), strokeWidth = DS.Size.rule)
                else IconAction(
                    Icons.AutoMirrored.Outlined.Send, "问",
                    onClick = { send() }, enabled = canSend,
                    tint = if (canSend) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                )
            },
        )
        Spacer(Modifier.height(DS.Rhythm.element))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(DS.Rhythm.element)) {
            if (asked == null) item {
                AskEmpty(onExample = { send(it) }, onSearch = { searchMode = true })
            }

            asked?.let { a -> item {
                Text(a, style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.SemiBold)
            } }

            // 智能体模式会翻好几篇文档，二十秒里不出字是常态。
            // 说出它正在做什么——这是这段等待里唯一能让人安心的东西。
            if (busy && answer.isEmpty()) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(DS.Size.progress), strokeWidth = DS.Size.rule)
                    Spacer(Modifier.width(DS.Rhythm.element))
                    Text(
                        step ?: if (mode == "agent") "在库里翻资料，这个要久一点" else "在想",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (answer.isNotEmpty()) item {
                DsCard(Modifier.fillMaxWidth()) {
                    // 边生成边渲染 Markdown。等全文到齐再渲染的话，
                    // 屏幕上会先跳出一堆 ## 和 **，再突然变样。
                    MarkdownText(answer, Modifier.padding(DS.Pad.default))
                }
            }

            failed?.let { f -> item { NoticeBox(f, Tone.RISK) } }

            if (insufficient) {
                item {
                    // 「我不知道」是一个正当的回答，不是失败。
                    // 深脑只从录进来的东西里答——这句话要说清楚，
                    // 否则用户会以为它坏了，或者更糟：以为库里真的没有。
                    Text(
                        "库里没有足够的依据来回答这个。深脑只从录进来的内容里答，不猜。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (fallback.isNotEmpty()) {
                    item { SectionLabel("不过库里这些提到过它") }
                    items(fallback, key = { it.kind + it.id }) { h ->
                        HitCard(h) { h.transcriptId?.let(onOpen) }
                    }
                }
                item { LinkButton(onClick = { searchMode = true }) { Text("改用关键词搜") } }
            }

            item { Spacer(Modifier.height(DS.Rhythm.page)) }
        }
    }
}

/**
 * 空态：说清楚能问什么，并给三个**能点的**例子——点一下就问出去。
 * 之前是一段文字里夹着三个「」引号的例子，和一个居中浮着的按钮，屏幕四分之三是空的。
 */
@Composable
private fun AskEmpty(onExample: (String) -> Unit, onSearch: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = DS.Rhythm.inner)) {
        Text("问深脑", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(DS.Rhythm.tight))
        Text("直接问，不用想关键词。深脑只从录进来的内容里答，不猜。",
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("比如")
        DsGroup {
            AskExamples.all.forEachIndexed { i, ex ->
                if (i > 0) RowDivider()
                DsRow(ex, onClick = { onExample(ex) })
            }
        }
        Spacer(Modifier.height(DS.Rhythm.element))
        LinkButton(onClick = onSearch) { Text("改用关键词搜") }
    }
}

/** 空态里那几个例句。纯数据，JVM 可测。 */
internal object AskExamples {
    val all = listOf("陈总上次答应了什么", "这个月我改过几次主意", "上周那场会最后定了没")
}
