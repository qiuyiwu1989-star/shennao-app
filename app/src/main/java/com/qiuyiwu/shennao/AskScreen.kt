package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { cancelAsk[0]?.invoke() } }
    if (searchMode) {
        Column(Modifier.fillMaxSize()) {
            LinkButton(onClick = { searchMode = false }, contentPadding = PaddingValues(horizontal = DS.Rhythm.inner)) { Text("← 回到问") }
            SearchScreen(client, onOpen)
        }
        return
    }

    fun send() {
        val question = q.trim()
        if (question.length < 2 || busy) return
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

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("上次那件事，他到底怎么说的？") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            // 回车就发。手机上多一个「提问」按钮就是多一次拇指移动。
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                TextButton(onClick = { send() }, enabled = !busy && q.trim().length >= 2) {
                    Text(if (busy) "…" else "问")
                }
            },
        )
        Spacer(Modifier.height(DS.Rhythm.element))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(DS.Rhythm.element)) {
            if (asked == null) item {
                Empty(
                    "问深脑",
                    "直接问，不用想关键词。比如「陈总上次答应了什么」" +
                        "「这个月我改过几次主意」「上周那场会最后定了没」。",
                    "改用关键词搜", { searchMode = true },
                )
            }

            asked?.let { a -> item {
                Text(a, style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.SemiBold)
            } }

            // 智能体模式会翻好几篇文档，二十秒里不出字是常态。
            // 说出它正在做什么——这是这段等待里唯一能让人安心的东西。
            if (busy && answer.isEmpty()) item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
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

            failed?.let { f -> item {
                Text(f, style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.error)
            } }

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
                    item {
                        Text("不过库里这些提到过它：",
                             style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(fallback, key = { it.kind + it.id }) { h ->
                        HitCard(h) { h.transcriptId?.let(onOpen) }
                    }
                }
            }

            item { Spacer(Modifier.height(DS.Rhythm.inner)) }
        }
    }
}
