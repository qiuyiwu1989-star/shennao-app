package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 接入 AI：把深脑当成你 AI 助手的长期记忆（MCP）。
 *
 * 手机上只做「说清楚能干什么」和「去建 key」两件事。建 key 本身在网页版（带登录态的 WebView），
 * 那里有一次性显示的完整 key 和三种配置方式，手机不重做一遍。
 * 不画各家的 logo：那是别人的商标，手画一版既不像也不合适，名字足够认。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AgentsScreen(onBack: () -> Unit, onOpenWeb: (path: String, title: String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(DS.Pad.screen)) {
            Spacer(Modifier.height(DS.Rhythm.inner))
            Text("让你的 AI 用上你的记忆", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(DS.Rhythm.tight))
            Text("通过 MCP 把深脑接成 AI 助手的长期记忆。它就能基于你真实聊过、定过的事来回答和生成，不是凭空编。",
                 style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
            NoticeBox("key 在网页版建：完整的 key 只显示一次，那里有自动配置的提示词和手动配置的 JSON。", Tone.NEUTRAL)
        }
        Column(Modifier.padding(DS.Pad.screen).padding(bottom = DS.Rhythm.inner)) {
            PrimaryButton("去建一个 key", modifier = Modifier.fillMaxWidth(),
                          onClick = { onOpenWeb("/zh/settings/api-keys", "接入 AI") })
        }
    }
}

/** 纯数据。名字不是 logo：别人的商标不手画。 */
internal object Agents {
    val supported = listOf("Claude", "ChatGPT", "Cursor", "Codex", "Kimi", "豆包", "任何支持 MCP 的")
    val examples = listOf(
        "问项目进展" to "S-03 这周聊到哪了？帮我理条进度线",
        "生成汇报" to "把这个月的关键决策做成一份汇报",
        "写日报" to "把今天聊过的事整理成一篇日报",
    )
}
