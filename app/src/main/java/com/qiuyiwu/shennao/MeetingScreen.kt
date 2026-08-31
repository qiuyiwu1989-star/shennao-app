package com.qiuyiwu.shennao

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
@Composable
fun MeetingScreen(client: DeepBrainClient, transcriptId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meeting by remember { mutableStateOf<Meeting?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(transcriptId) {
        when (val r = withContext(Dispatchers.IO) { client.meeting(transcriptId) }) {
            is ApiResult.Ok -> meeting = r.value
            is ApiResult.Failed -> error = r.message
            else -> error = "登录失效了"
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回") }
            }
        }

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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(
                            m.durationSec?.let { "${it / 60} 分钟" },
                            m.speakers.size.takeIf { it > 0 }?.let { "$it 人在场" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("这场会", style = MaterialTheme.typography.titleSmall,
                                 fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
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

                if (m.speakers.isNotEmpty()) item {
                    Spacer(Modifier.height(4.dp))
                    Text("在场：" + m.speakers.joinToString("、"),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (m.commitments.isNotEmpty()) {
                    item { SectionHead("这场会里的承诺", "别人说出口、还没有下文的") }
                    items(m.commitments, key = { "c" + it.id }) { c ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row {
                                    Text(c.speakerName, style = MaterialTheme.typography.titleSmall,
                                         fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                    c.dueDate?.let {
                                        Text(it, style = MaterialTheme.typography.labelMedium,
                                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("「${c.quote}」", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                if (m.atoms.isNotEmpty()) {
                    item { SectionHead("读出来的判断", "决定、信号、矛盾") }
                    items(m.atoms, key = { "a" + it.id }) { a -> AtomCard(a) }
                }

                item {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = {
                            ctx.startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("${BuildConfig.API_BASE}/zh/transcript/${m.transcriptId}")))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("在网页里看完整转写") }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(title: String, hint: String) {
    Column(Modifier.padding(top = 14.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AtomCard(a: MeetingAtom) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel(a.atomType), style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                // 认知等级必须显示。手机是一扫而过的场景，
                // 一条「猜想」会被当成事实——比坐在电脑前更危险。
                Text(epistemicLabel(a.epistemic), style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(a.statement, style = MaterialTheme.typography.bodyLarge)
            if (a.quote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("「${a.quote}」", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
