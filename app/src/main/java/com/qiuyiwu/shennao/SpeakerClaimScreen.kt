package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 认人（S14）。
 *
 * 说话人不落到具体的人，「人的兑现率」「他第三次这么说了」全都立不起来——
 * 认人是地基不是锦上添花。`counts.awaitingSpeaker` 之前一直在回数字，手机上却无处可去。
 *
 * 一次只问一句，配一段样本原话；给候选并说明来源；**「听不出来就跳过」是平等选项**——
 * 逼人猜会污染整个人物档案，这跟预测的「说不清」是同一条原则。
 * 认完一个立刻问下一个，认完全部就回去：这是个苦差事，不给它留任何多余的停顿。
 */
@Composable
fun SpeakerClaimScreen(
    client: DeepBrainClient,
    transcriptId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val notice = LocalNotice.current
    var page by remember { mutableStateOf<SpeakersPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    /** 已处理的（认了或跳过），本地记，不等重取 */
    var handled by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(transcriptId, attempt) {
        when (val r = withContext(Dispatchers.IO) { client.speakers(transcriptId) }) {
            is ApiResult.Ok -> page = r.value
            is ApiResult.Failed -> error = r.message
            else -> error = "登录失效了"
        }
    }

    fun claim(row: SpeakerRow, name: String?) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { client.claimSpeaker(transcriptId, row.id, name) }
            busy = false
            when (r) {
                is ApiResult.Ok -> { handled = handled + row.id; custom = "" }
                is ApiResult.Failed -> notice("没记上：${r.message}")
                else -> notice("没记上：登录失效了")
            }
        }
    }

    val pending = page?.speakers?.filter { !it.confirmed && it.id !in handled }.orEmpty()
    val current = pending.firstOrNull()

    DetailPage(onBack = onBack, title = "认人") {
        when {
            error != null -> item { Broken(error!!) { error = null; attempt++ } }
            page == null -> item { SkeletonList(2) }
            current == null -> item {
                // 认完了，或者本来就没有要认的
                Empty(
                    if (handled.isEmpty()) "这场会里的人都认过了" else "认完了",
                    "认了之后，这个人在所有录音里的话会一起归位——关于他的判断、他答应过什么，都得先有这一步。",
                    "回到这场会", onBack,
                )
            }
            else -> {
                item {
                    Text("还有 ${pending.size} 句不知道是谁说的", style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(DS.Rhythm.element))
                    DsCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(DS.Pad.tight)) {
                            Text(current.label + (current.sampleStartMs?.let { " · " + fmtClock(it) } ?: ""),
                                 style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(DS.Rhythm.tight))
                            Text(current.sampleText ?: "这个人在这场里没说出完整的一句", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(DS.Rhythm.inner))
                    Text("是谁？", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(DS.Rhythm.tight))
                }
                items(page!!.candidates.take(8), key = { "c" + it.name }) { c ->
                    DsCard(Modifier.fillMaxWidth(), onClick = { if (!busy) claim(current, c.name) }) {
                        Row(Modifier.padding(DS.Pad.tight), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                 modifier = Modifier.weight(1f))
                            // 候选从哪来要说：有理由的建议才敢点
                            Text(listOfNotNull(c.role, sourceLabel(c.source)).joinToString(" · "),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(DS.Rhythm.tight))
                }
                item {
                    Spacer(Modifier.height(DS.Rhythm.element))
                    OutlinedTextField(
                        value = custom, onValueChange = { custom = it.take(40) }, singleLine = true,
                        label = { Text("不在上面？写名字") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(DS.Rhythm.tight))
                    Row {
                        Button(enabled = custom.isNotBlank() && !busy, onClick = { claim(current, custom.trim()) }) { Text("就是这个人") }
                        Spacer(Modifier.width(DS.Rhythm.element))
                        TextButton(enabled = !busy, onClick = { claim(current, null) }) { Text("听不出来，跳过") }
                    }
                    Spacer(Modifier.height(DS.Rhythm.inner))
                    Text("认了之后，这个人在所有录音里的那些话会一起归位。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun sourceLabel(s: String) = when (s) {
    "entity" -> "档案里有"; "profile" -> "成员档案"; "member" -> "组织成员"; else -> null
}

internal fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
