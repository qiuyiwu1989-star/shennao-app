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
import androidx.compose.foundation.layout.height
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
    var level by remember { mutableStateOf(0f) }
    var captions by remember { mutableStateOf<List<String>>(emptyList()) }
    var captionState by remember { mutableStateOf<String?>(null) }
    // 声波保留最近这些格。数量按一屏能画下的柱子数定，多了会挤成一片灰。
    val bars = remember { mutableStateListOf<Float>() }

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
            error = RecordingService.micError
            sessionId = RecordingService.serverSessionId
            level = RecordingService.level
            captions = RecordingService.captions
            captionState = RecordingService.captionState
            if (recording) {
                bars.add(level)
                if (bars.size > 48) bars.removeAt(0)
            } else if (bars.isNotEmpty()) bars.clear()
            delay(80)
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

        if (recording || state == com.qiuyiwu.shennao.record.RecordState.INTERRUPTED) {
            Spacer(Modifier.height(18.dp))
            Waveform(bars, Modifier.fillMaxWidth().height(56.dp))
        }

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
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        Surface(
            onClick = {
                /*
                 * 触感。开始和停止录音是**有后果**的动作——按下去之后，
                 * 屏幕上的变化要一秒后才看得出来（服务启动、状态回传）。
                 * 这一秒里手上没有任何确认，人会怀疑自己没按到，然后再按一次。
                 */
                // 同理：先做事，再给手感，且手感不许抛。
                runCatching { haptics.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
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
            Spacer(Modifier.height(24.dp))
            Captions(captions, captionState, sessionId != null)
            Spacer(Modifier.height(24.dp))
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
 * 实时字幕。
 *
 * 只显示最近几句，不做成一份可滚动的稿子——字幕要回答的是「现在在说什么」，
 * 做成稿子会让人低头去读它，而这一屏的用户正在开会。
 *
 * 仓规：实时稿不进长期记忆，也不能被当成亲证事实。所以这里**只显示，
 * 不落盘不上传**——网关自己会把 final 段投影到服务端，那条路有它自己的闸门。
 */
@androidx.compose.runtime.Composable
private fun Captions(lines: List<String>, state: String?, hasSession: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = com.qiuyiwu.shennao.DS.Radius.card,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("实时字幕", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            when {
                lines.isNotEmpty() -> lines.forEachIndexed { i, t ->
                    Text(
                        t,
                        style = MaterialTheme.typography.bodyMedium,
                        // 最后一句是正在说的，前面几句压暗——
                        // 全一样亮的话，眼睛找不到「现在说到哪」。
                        color = if (i == lines.lastIndex) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (i < lines.lastIndex) Spacer(Modifier.height(4.dp))
                }
                // 字幕要等第一段传上去才有会话 id（约一分钟）。
                // 说清楚在等什么，不要给一片空白让人以为坏了。
                !hasSession -> Text("等第一段传上去后开始（约一分钟）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(state ?: "正在接通…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 状态里的坏消息要单独说一次，别被字幕盖过去
            if (lines.isNotEmpty() && state != null && state.contains("断")) {
                Spacer(Modifier.height(6.dp))
                Text(state, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 声波。
 *
 * 计时器只能证明「时间在走」，证明不了「录到了声音」——一个被静音的麦克风，
 * 计时器照样走得好好的。这条声波是唯一能一眼看出「它真的在听」的东西。
 *
 * 从右往左推，新的在右边——和录音笔、和人读时间的方向一致。
 * 静音时保留一条细基线而不是空白：空白看起来像组件没加载出来。
 */
@androidx.compose.runtime.Composable
private fun Waveform(bars: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.Canvas(modifier) {
        val n = 48
        val gap = 3f
        val w = (size.width - gap * (n - 1)) / n
        val mid = size.height / 2f
        for (i in 0 until n) {
            // 右对齐：最新的一格贴右边
            val v = bars.getOrNull(bars.size - n + i) ?: 0f
            val h = (v * size.height).coerceAtLeast(2f)
            val x = i * (w + gap)
            drawRoundRect(
                color = if (v > 0.02f) color else idle,
                topLeft = androidx.compose.ui.geometry.Offset(x, mid - h / 2f),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
            )
        }
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
                // 安卓的命中区下限是 48dp（规范 §12 明写不取交集）。
                // TextButton 默认高度不够，用透明 padding 撑开、不改视觉尺寸。
                modifier = Modifier.heightIn(min = 48.dp),
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
