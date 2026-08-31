package com.qiuyiwu.shennao

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var state by remember { mutableStateOf(com.qiuyiwu.shennao.record.RecordState.IDLE) }
    var elapsed by remember { mutableStateOf(0L) }
    var pending by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }
    var sessionId by remember { mutableStateOf<String?>(null) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) RecordingService.start(ctx, "手机录音") else denied = true
    }

    // 服务是真相，界面只是照着念。不在界面里另存一份「我以为在录」——
    // 服务被系统杀掉时，那份状态会一直显示「正在录音」，而其实早停了。
    LaunchedEffect(Unit) {
        while (true) {
            recording = RecordingService.recording
            state = RecordingService.state
            elapsed = RecordingService.elapsedMs
            pending = RecordingService.pendingSegments
            error = RecordingService.lastError
            sessionId = RecordingService.serverSessionId
            delay(250)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
        }

        Spacer(Modifier.weight(1f))

        /*
         * 时长用等宽数字。
         *
         * 比例字体下「1」比「8」窄，秒数每跳一下整行都会左右抖——
         * 一个抖动的计时器读起来像是出了问题，而这一屏唯一的职责
         * 就是让人相信「它还在录」。
         */
        Text(
            if (state == com.qiuyiwu.shennao.record.RecordState.IDLE && !recording) "准备好了"
            else fmt(elapsed),
            fontSize = if (recording || state != com.qiuyiwu.shennao.record.RecordState.IDLE) 52.sp else 26.sp,
            fontWeight = FontWeight.Light,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = when (state) {
                com.qiuyiwu.shennao.record.RecordState.INTERRUPTED,
                com.qiuyiwu.shennao.record.RecordState.GAVE_UP -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )

        Spacer(Modifier.height(10.dp))
        Text(
            when (state) {
                // 中断必须一眼看得出来。挂着「正在录音」而其实没在录，
                // 是这套东西能犯的最坏的错——开完会才发现，已经没法补救了。
                com.qiuyiwu.shennao.record.RecordState.INTERRUPTED ->
                    "麦克风被占用了，正在抢回来。已经录到的都在。"
                com.qiuyiwu.shennao.record.RecordState.GAVE_UP ->
                    "麦克风抢不回来，录音已停止。已录到的部分正在推送。"
                com.qiuyiwu.shennao.record.RecordState.RECORDING ->
                    "正在录。可以切走做别的，录音不会断。"
                else -> if (pending > 0) "还有 $pending 段在传" else "点一下开始录这场会"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Spacer(Modifier.height(40.dp))

        // 主按钮：录音时是「停止」，否则是「开始」。
        // 用实心圆而不是矩形按钮——这一屏只有一个动作，它该长得像一个动作。
        Surface(
            onClick = {
                if (recording) RecordingService.stop(ctx)
                else {
                    denied = false
                    val need = Manifest.permission.RECORD_AUDIO
                    if (ContextCompat.checkSelfPermission(ctx, need) == PackageManager.PERMISSION_GRANTED)
                        RecordingService.start(ctx, "手机录音")
                    else ask.launch(need)
                }
            },
            shape = CircleShape,
            color = if (recording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(104.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (recording) "停止" else "开始",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (recording) {
            Spacer(Modifier.height(28.dp))
            HotwordBox(sessionId)
        }

        Spacer(Modifier.height(20.dp))

        if (denied) {
            Text(
                "没有麦克风权限就录不了。到系统设置里给深脑开一下。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        // 只显示不可重试的错。网络抖一下就弹红字，用户学会的第一件事就是无视它
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error,
                 textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }

        Spacer(Modifier.weight(1f))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "录完自动推到深脑，转写和分析在那边跑。中途断网也不会丢——" +
                    "没传完的段留在手机上，下次打开接着传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 本场专有名词。
 *
 * 放在录音页而不是录之前：开会开到一半才发现「这个名字它一直听错」，
 * 是这件事最常发生的时刻。而录之前先填一堆词，等于给按下录音加摩擦——
 * 那是这个 App 最不该有摩擦的一个动作。
 */
@androidx.compose.runtime.Composable
private fun HotwordBox(sessionId: String?) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text("这场会的专有名词", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            // 「只影响之后」必须写出来。不写的话，用户会以为加了词就能把
            // 前面已经听错的地方改过来，然后对结果失望。
            if (sessionId == null) "等第一段传上去之后就能加（约一分钟）"
            else "人名、项目名、生造词。加了只影响之后的识别，前面已经出的字不会回头改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = sessionId != null && !busy,
                placeholder = { Text("用顿号或空格分开") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = sessionId != null && !busy && text.isNotBlank(),
                onClick = {
                    val id = sessionId ?: return@TextButton
                    val pins = text.split('、', ',', '，', ' ', '\n')
                        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    if (pins.isEmpty()) return@TextButton
                    busy = true; failed = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            Session.client(ctx).setHotwordPins(id, (saved + pins).distinct())
                        }
                        busy = false
                        when (r) {
                            is ApiResult.Ok -> { saved = r.value; text = "" }
                            is ApiResult.Failed -> failed = r.message
                            else -> failed = "登录失效了"
                        }
                    }
                },
            ) { Text("加上") }
        }
        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            // 显示服务端真正接受的那些，不是用户输入的那些——
            // 超限或重复的词会被丢掉，照抄输入会让人以为它在起作用。
            Text("已生效：" + saved.joinToString("、"),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.primary)
        }
        failed?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s < 3600) "%d:%02d".format(s / 60, s % 60)
    else "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
