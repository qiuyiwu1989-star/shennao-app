package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 搜索。
 *
 * 判据是「在会议室里 10 秒内查到上次这事谁拍的板」，所以：
 *   · 边打边搜（停手 300 毫秒后发一次），不用点按钮
 *   · 结果一行一条，判断在前——一条判断直接就是答案，
 *     而一个会议标题还要再点进去读
 */
@Composable
fun SearchScreen(client: DeepBrainClient, onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    // 停手之后才发。每敲一个字发一次，在会议室的网络下只会让结果乱跳。
    LaunchedEffect(q) {
        if (q.trim().length < 2) { hits = emptyList(); searched = false; return@LaunchedEffect }
        delay(300)
        busy = true
        val r = withContext(Dispatchers.IO) { client.search(q.trim()) }
        busy = false; searched = true
        hits = if (r is ApiResult.Ok) r.value else emptyList()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("那件事上次是谁说的？") },
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))

        when {
            busy && hits.isEmpty() -> Loading()
            q.trim().length < 2 -> Empty("搜判断、承诺、会议", "输入两个字以上。判断排在最前——它往往直接就是答案。")
            searched && hits.isEmpty() -> Empty("没找到", "换个说法试试，或者那件事还没被录进来。")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(hits, key = { it.kind + it.id }) { h ->
                    DsCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { h.transcriptId?.let(onOpen) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row {
                                Text(kindLabel(h.kind), style = MaterialTheme.typography.labelMedium,
                                     color = MaterialTheme.colorScheme.primary,
                                     fontWeight = FontWeight.Medium)
                                h.who?.let {
                                    Spacer(Modifier.width(8.dp))
                                    Text(it, style = MaterialTheme.typography.labelMedium,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(h.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

private fun kindLabel(k: String) = when (k) {
    "judgment" -> "判断"; "commitment" -> "承诺"; "meeting" -> "会议"; else -> k
}
