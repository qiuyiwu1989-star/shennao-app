package com.qiuyiwu.shennao

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.qiuyiwu.shennao.record.RecordingService
import kotlinx.coroutines.delay

/*
 * 录音页。一个按钮，一行状态。
 *
 * 刻意不做波形、不做音量条：它们好看，但对「这场会到底录上了没有」这个
 * 唯一重要的问题一个字都没回答。这一页真正要说清楚的只有三件事——
 * 在录吗、录了多久、有没有安全送到深脑。
 */

@androidx.compose.runtime.Composable
fun RecordScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var recording by remember { mutableStateOf(RecordingService.recording) }
    var elapsed by remember { mutableStateOf(0L) }
    var pending by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) RecordingService.start(ctx, "手机录音") else denied = true
    }

    // 服务是真相，界面只是照着念。不在界面里另存一份「我以为在录」——
    // 服务被系统杀掉时，那份状态会一直显示「正在录音」，而其实早停了。
    LaunchedEffect(Unit) {
        while (true) {
            recording = RecordingService.recording
            elapsed = RecordingService.elapsedMs
            pending = RecordingService.pendingSegments
            error = RecordingService.lastError
            delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
        }

        Spacer(Modifier.weight(1f))

        Text(
            if (recording) fmt(elapsed) else "准备好了",
            fontSize = if (recording) 44.sp else 24.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                recording -> "正在录。可以切走做别的，录音不会断。"
                pending > 0 -> "还有 $pending 段在传"
                else -> "点一下开始录这场会"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(36.dp))

        Box(
            Modifier.size(96.dp).clip(CircleShape).background(
                if (recording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            ),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(onClick = {
                if (recording) {
                    RecordingService.stop(ctx)
                } else {
                    denied = false
                    val need = Manifest.permission.RECORD_AUDIO
                    if (ContextCompat.checkSelfPermission(ctx, need) == PackageManager.PERMISSION_GRANTED) {
                        RecordingService.start(ctx, "手机录音")
                    } else ask.launch(need)
                }
            }) {
                Text(
                    if (recording) "停止" else "开始",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        if (denied) {
            Text(
                "没有麦克风权限就录不了。到系统设置里给深脑开一下。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // 只显示不可重试的错。网络抖一下就弹红字，用户学会的第一件事就是无视它
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.weight(1f))

        Text(
            "录完自动推到深脑，转写和分析在那边跑。中途断网也不会丢——" +
                "没传完的段留在手机上，下次打开接着传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s < 3600) "%d:%02d".format(s / 60, s % 60)
    else "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
