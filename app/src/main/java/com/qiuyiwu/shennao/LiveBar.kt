package com.qiuyiwu.shennao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.qiuyiwu.shennao.record.AlwaysOn
import com.qiuyiwu.shennao.record.RecordingService
import kotlinx.coroutines.delay

/**
 * 固定在「今天」「记录」顶上的一条：现在在不在录、录了多久。点它进录音台。
 *
 * 2026-09-12 邱看了 Hemory 的顶栏说「录音固定在上方是可以的」。
 * 它回答的是这个 App 唯一要紧的问题：此刻有没有在录。没在录时它是一句邀请，在录时它是一个保证。
 */
@Composable
fun LiveBar(onClick: () -> Unit) {
    var recording by remember { mutableStateOf(RecordingService.recording) }
    var phase by remember { mutableStateOf(RecordingService.listenPhase) }
    var elapsed by remember { mutableStateOf(RecordingService.elapsedMs) }
    LaunchedEffect(Unit) {
        while (true) {
            recording = RecordingService.recording
            phase = RecordingService.listenPhase
            elapsed = RecordingService.elapsedMs
            delay(1_000)
        }
    }
    val cs = MaterialTheme.colorScheme
    val live = recording || phase == AlwaysOn.Phase.RECORDING
    val listening = phase == AlwaysOn.Phase.LISTENING
    val (title, sub) = LiveBarText.of(live, listening, elapsed)
    Surface(
        onClick = onClick, shape = DS.Radius.card,
        color = if (live) cs.primaryContainer else cs.surface,
        border = if (live || LocalDark.current) null else androidx.compose.foundation.BorderStroke(DS.Size.hairline, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(DS.Pad.screen).padding(top = DS.Rhythm.element),
    ) {
        Row(Modifier.padding(DS.Pad.row), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(DS.Rhythm.tight).background(
                    when { live -> cs.error; listening -> cs.primary; else -> cs.outline }, CircleShape)
            )
            Spacer(Modifier.width(DS.Rhythm.element))
            Text(title, style = MaterialTheme.typography.titleSmall,
                 color = if (live) cs.onPrimaryContainer else cs.onSurface)
            Spacer(Modifier.width(DS.Rhythm.tight))
            Text(sub, style = MaterialTheme.typography.bodySmall,
                 color = if (live) cs.onPrimaryContainer else cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Icon(MicOutlined, contentDescription = null, tint = if (live) cs.onPrimaryContainer else cs.onSurfaceVariant,
                 modifier = Modifier.size(DS.Size.icon))
        }
    }
}

/** 顶栏那两句。纯逻辑，JVM 可测。 */
internal object LiveBarText {
    fun of(live: Boolean, listening: Boolean, elapsedMs: Long): Pair<String, String> = when {
        live -> "正在录" to (if (elapsedMs > 0) "已录 ${elapsedMs / 60_000} 分钟" else "")
        listening -> "在听" to "有人说话时才录"
        else -> "点一下开始录" to ""
    }
}
