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
fun HistoryScreen(client: DeepBrainClient, onRecord: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val cache = remember { Cache(File(ctx.cacheDir, "mobile")) }
    var stale by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<LocalSession>>(emptyList()) }
    var served by remember { mutableStateOf<List<SessionCard>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            rows = withContext(Dispatchers.IO) { scan(File(ctx.filesDir, "recordings")) }
            // 服务端那份查得慢一些，但它才知道转写和分析走到哪了。
            // 两份合起来才是完整的一条链：本地管「传没传出去」，服务端管「后面几站」。
            val r = withContext(Dispatchers.IO) { client.sessions() }
            if (r is ApiResult.Ok) {
                served = r.value
                stale = null
                withContext(Dispatchers.IO) {
                    client.rawSessionsOrNull()?.let { cache.save(Cache.SESSIONS, it) }
                }
            } else if (served.isEmpty()) {
                // 没网就拿上次的。这一页的价值是「让卡住变得看得见」，
                // 而没网时最容易让人以为「东西都送到了」。
                val c = withContext(Dispatchers.IO) { cache.load(Cache.SESSIONS) }
                if (c != null) {
                    served = SessionsParser.parse(c.body)
                    if (served.isNotEmpty()) {
                        stale = Cache.staleLabel(c.savedAt, System.currentTimeMillis()) ?: "离线 · 刚才的"
                    }
                }
            }
            loaded = true
            delay(5000)
        }
    }

    Column(Modifier.fillMaxSize()) {
    stale?.let { StaleBanner(it) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("我录过的会", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("每一场走到哪一站，都在这里。",
                 style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
        }

        // 还在这台手机上的排最前：它们是唯一可能丢的
        if (rows.isNotEmpty()) {
            item { MiniHead("还在手机上") }
            items(rows, key = { "l" + it.dir }) { s -> SessionRow(s) }
        }

        if (served.isNotEmpty()) {
            item { MiniHead("已经送到深脑") }
            items(served, key = { "s" + it.sessionId }) { s -> ServedRow(s, onOpen) }
        }

        if (!loaded) item { Loading() }
        else if (rows.isEmpty() && served.isEmpty()) item {
            Empty("还没有录过", "录一场会，它会自己走完转写和分析。", "录一场", onRecord)
        }
    }
    }
}

@Composable
private fun MiniHead(t: String) {
    Text(t, style = MaterialTheme.typography.labelMedium,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
         modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

/**
 * 一场已送达的会，显示它走到链路的第几站。
 *
 * 四站画成一条线而不是一个状态词：状态词只说「现在在哪」，
 * 一条线还说了「还差几步」——而用户真正想知道的是后者。
 */
@Composable
private fun ServedRow(s: SessionCard, onOpen: (String) -> Unit) {
    val failed = s.stage == Stage.FAILED
    // 可点的 Card 那个重载是 onClick 在前、modifier 在后，
    // 按 Modifier 优先的习惯写会匹配不上。
    Card(
        onClick = { s.transcriptId?.let(onOpen) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                s.durationMs?.let {
                    Text("${it / 60000} 分钟", style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Chain(s.stage)
            if (failed && s.problem != null) {
                Spacer(Modifier.height(8.dp))
                Text(s.problem, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
            s.startedAt?.let {
                Spacer(Modifier.height(6.dp))
                Text(day(it), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Chain(stage: Stage) {
    val stops = listOf("录下来", "送到", "转写完", "分析完")
    val reached = when (stage) {
        Stage.RECORDED -> 1
        Stage.DELIVERED -> 2
        Stage.TRANSCRIBED -> 3
        Stage.ANALYZED -> 4
        else -> 0
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        stops.forEachIndexed { i, name ->
            val on = i < reached
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    stage == Stage.FAILED && i == reached -> MaterialTheme.colorScheme.error
                    on -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (i < stops.lastIndex) {
                Text(" → ", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun day(iso: String): String = runCatching {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    val d = f.parse(iso.take(19))!!
    java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(d)
}.getOrElse { iso.take(10) }

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
