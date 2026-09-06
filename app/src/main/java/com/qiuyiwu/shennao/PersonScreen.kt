package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一个人的页。
 *
 * 回答的不是「这个人是谁」，是**「他说过什么、兑现了多少」**——
 * 一条孤立的承诺没有分量，「他第三次这么说了」才有。
 */
@Composable
fun PersonScreen(
    client: DeepBrainClient,
    personId: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRecord: () -> Unit,
) {
    var person by remember { mutableStateOf<Person?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 「再试一次」必须真的再取一次。之前只把 error 清空，LaunchedEffect 只认 personId，
    // 于是落进骨架屏永远转——一个点了没反应的重试比没有重试更糟。
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(personId, attempt) {
        when (val r = withContext(Dispatchers.IO) { client.person(personId) }) {
            is ApiResult.Ok -> person = r.value
            is ApiResult.Failed -> error = r.message
            else -> error = "登录失效了"
        }
    }

    val p = person
    DetailPage(onBack = onBack, title = p?.name) {
        when {
            error != null -> item { Broken(error!!) { error = null; attempt++ } }
            p == null -> item { SkeletonList(2) }
            else -> {
                p.role?.let { r -> item {
                    Text(r, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }

                item { Ledger(p) }

                if (p.openCommitments.isNotEmpty()) {
                    item { Head("还欠着的", "说出口、还没有下文") }
                    items(p.openCommitments, key = { "o" + it.id }) { c ->
                        DsCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(DS.Pad.tight)) {
                                Text("「${c.quote}」", style = MaterialTheme.typography.bodyLarge)
                                c.dueDate?.let {
                                    Spacer(Modifier.height(DS.Rhythm.tight))
                                    Text(it, style = MaterialTheme.typography.labelMedium,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                c.transcriptId?.let { t ->
                                    TextButton(onClick = { onOpen(t) }, contentPadding = PaddingValues()) { Text("去那场会") }
                                }
                            }
                        }
                    }
                }

                if (p.judgments.isNotEmpty()) {
                    item { Head("关于他的判断", "深脑从这些会里读出来的") }
                    items(p.judgments, key = { "j" + it.id }) { j ->
                        DsCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(DS.Pad.tight)) {
                                Text(j.statement, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(DS.Rhythm.tight))
                                Text(
                                    when (j.epistemic) {
                                        "attested" -> "有原话"; "inferred" -> "推断"
                                        "conjecture" -> "猜想"; else -> "—"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (p.openCommitments.isEmpty() && p.judgments.isEmpty()) {
                    // 空态必须给下一步。「等他在会上说点什么」是句废话——
                    // 用户此刻能做的是去录一场，或者回去看别的。
                    item { Empty("还没有关于他的东西", "他还没在录过的会里出现过。", "去录一场", onRecord) }
                }
            }
        }
    }
}

/**
 * 承诺账。
 *
 * 兑现率没有结论时显示「还没有结论」而不是 0%——
 * 一个数字比一句「不知道」更容易被当真，而这里被当真的代价是冤枉一个人。
 */
@Composable
private fun Ledger(p: Person) {
    DsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(DS.Pad.tight)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    p.keptRate?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (p.keptRate == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(DS.Rhythm.element))
                Text(
                    if (p.keptRate == null) "还没有一条有结论" else "兑现率",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(DS.Rhythm.element))
            Text(
                "兑现 ${p.kept} · 落空 ${p.broken} · 在途 ${p.open}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (p.keptRate != null) {
                Spacer(Modifier.height(DS.Rhythm.tight))
                // 分母写出来。一个不知道怎么算的比率，用起来会比没有更危险。
                Text("比率只算已有结论的 ${p.kept + p.broken} 条，在途的不算。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Head(t: String, hint: String) {
    Column(Modifier.padding(top = 14.dp, bottom = 2.dp)) {
        Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
