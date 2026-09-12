package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 接入 AI：把深脑当成你 AI 助手的长期记忆（MCP）。
 *
 * 2026-09-12 邱：「不要先打开一个网页，直接在里面就能做，对 API 也可以直接管理」。
 * 所以这一页现在自己建 key、列 key、停 key。完整的 key 只在建好那一刻显示一次，
 * 界面把它和一段能直接粘贴的 MCP 配置一起给出来。
 * 不画各家的 logo：那是别人的商标，名字足够认。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AgentsScreen(
    client: DeepBrainClient, onBack: () -> Unit,
    /** 截图测试传已加载的列表，不跑取数协程（Robolectric 里回主线程会丢） */
    initialKeys: List<ApiKey>? = null,
) {
    val scope = rememberCoroutineScope()
    val notice = LocalNotice.current
    val clipboard = LocalClipboardManager.current
    var keys by remember { mutableStateOf<List<ApiKey>?>(initialKeys) }
    var error by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf<ApiKey?>(null) }   // 刚建好的那把，带 secret
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        when (val r = withContext(Dispatchers.IO) { client.apiKeys() }) {
            is ApiResult.Ok -> { keys = r.value; error = null }
            is ApiResult.Failed -> error = r.message
            else -> error = "登录失效了"
        }
    }
    LaunchedEffect(Unit) { if (initialKeys == null) reload() }

    Column(Modifier.fillMaxSize()) {
        TopBar(onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(DS.Pad.screen)) {
            Spacer(Modifier.height(DS.Rhythm.inner))
            Text("让你的 AI 用上你的记忆", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(DS.Rhythm.tight))
            Text("通过 MCP 把深脑接成 AI 助手的长期记忆。它就能基于你真实聊过、定过的事来回答和生成，不是凭空编。",
                 style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 刚建好的：只此一次
            fresh?.let { k ->
                Spacer(Modifier.height(DS.Rhythm.inner))
                DsCard(Modifier.fillMaxWidth(), tone = CardTone.ACCENT) {
                    Column(Modifier.padding(DS.Pad.card)) {
                        Text("「${k.name}」建好了。这串只显示这一次，先复制。", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(DS.Rhythm.tight))
                        Text(k.secret ?: "", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(DS.Rhythm.tight))
                        Row(horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.element)) {
                            TonalButton("复制 key", onClick = { clipboard.setText(AnnotatedString(k.secret ?: "")); notice("已复制") })
                            TonalButton("复制 MCP 配置", onClick = {
                                clipboard.setText(AnnotatedString(Agents.mcpConfig(BuildConfig.API_BASE, k.secret ?: "")))
                                notice("已复制，粘到 Claude / Cursor 的 MCP 设置里")
                            })
                        }
                        Spacer(Modifier.height(DS.Rhythm.tight))
                        QuietButton("我存好了", onClick = { fresh = null })
                    }
                }
            }

            SectionLabel("你的 key")
            when {
                error != null -> Broken(error!!) { scope.launch { reload() } }
                keys == null -> Loading()
                keys!!.isEmpty() -> Text("还没有。建一把，粘到你的 AI 助手里就接上了。",
                                         style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> DsGroup {
                    keys!!.forEachIndexed { i, k ->
                        if (i > 0) RowDivider()
                        DsRow(
                            k.name.ifBlank { k.prefix },
                            subtitle = Agents.keyLine(k),
                            trailingContent = {
                                if (k.revoked) Pill("已停", Tone.NEUTRAL)
                                else LinkButton(onClick = {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { client.revokeApiKey(k.id) }
                                        notice(if (r is ApiResult.Ok) "已停掉「${k.name}」" else "没停掉：" + ((r as? ApiResult.Failed)?.message ?: "登录失效了"))
                                        reload()
                                    }
                                }) { Text("停掉") }
                            },
                        )
                    }
                }
            }

            SectionLabel("能接的")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.tight), verticalArrangement = Arrangement.spacedBy(DS.Rhythm.tight)) {
                Agents.supported.forEach { DsChip(selected = false, onClick = {}, label = it) }
            }

            SectionLabel("接上之后可以这样用")
            DsGroup {
                Agents.examples.forEachIndexed { i, (title, ask) ->
                    if (i > 0) RowDivider()
                    DsRow(title, subtitle = "「$ask」")
                }
            }
            Spacer(Modifier.height(DS.Rhythm.inner))
        }
        Column(Modifier.padding(DS.Pad.screen).padding(bottom = DS.Rhythm.inner)) {
            PrimaryButton("建一把新 key", modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { name = ""; naming = true })
        }
    }

    if (naming) AlertDialog(
        onDismissRequest = { naming = false },
        shape = DS.Radius.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("这把 key 给谁用", style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true,
                              placeholder = { Text("比如：Claude 桌面、Cursor") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TonalButton("建", enabled = name.isNotBlank() && !busy, onClick = {
                busy = true; naming = false
                scope.launch {
                    when (val r = withContext(Dispatchers.IO) { client.createApiKey(name.trim()) }) {
                        is ApiResult.Ok -> { fresh = r.value; reload() }
                        is ApiResult.Failed -> notice("没建成：${r.message}")
                        else -> notice("没建成：登录失效了")
                    }
                    busy = false
                }
            })
        },
        dismissButton = { QuietButton("取消", onClick = { naming = false }) },
    )
}

/** 纯数据与纯逻辑，JVM 可测。名字不是 logo：别人的商标不手画。 */
internal object Agents {
    val supported = listOf("Claude", "ChatGPT", "Cursor", "Codex", "Kimi", "豆包", "任何支持 MCP 的")
    val examples = listOf(
        "问项目进展" to "S-03 这周聊到哪了？帮我理条进度线",
        "生成汇报" to "把这个月的关键决策做成一份汇报",
        "写日报" to "把今天聊过的事整理成一篇日报",
    )
    /** 列表里一行的副标题：前缀 · 上次用 / 没用过 */
    fun keyLine(k: ApiKey): String {
        val used = k.lastUsedAt?.let { "上次用 " + it.take(10) } ?: "还没用过"
        return "${k.prefix}… · $used"
    }
    /** 能直接粘进 Claude / Cursor 的 MCP 配置。 */
    fun mcpConfig(apiBase: String, secret: String): String = """
        |{
        |  "mcpServers": {
        |    "shennao": {
        |      "url": "$apiBase/api/mcp",
        |      "headers": { "Authorization": "Bearer $secret" }
        |    }
        |  }
        |}
    """.trimMargin()
}
