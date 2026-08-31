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
import com.qiuyiwu.shennao.record.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

private data class LocalSession(
    val dir: String,
    val meta: SessionMeta,
    val total: Int,
    val done: Int,
    val recording: Int,
) {
    val bytesLeftLabel: String get() = "$done/$total 段已送达"
}

@Composable
fun HistoryScreen(onRecord: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<LocalSession>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            rows = withContext(Dispatchers.IO) { scan(File(ctx.filesDir, "recordings")) }
            loaded = true
            delay(3000)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("在路上", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (rows.isEmpty() && loaded) "都送到了。已经送达的会在网页版里看。"
                else "这些还在这台手机上，没有全部送到深脑。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }

        items(rows, key = { it.dir }) { s -> SessionRow(s) }

        if (rows.isEmpty() && loaded) {
            item {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onRecord, modifier = Modifier.fillMaxWidth()) { Text("录一场") }
            }
        }
    }
}

@Composable
private fun SessionRow(s: LocalSession) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
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
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text("${s.bytesLeftLabel} · 开始于 ${stamp(s.meta.startedAtEpochMs)}",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
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
