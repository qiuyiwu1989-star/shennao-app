package com.qiuyiwu.shennao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.qiuyiwu.shennao.record.ListenSchedule
import java.util.Calendar

/**
 * 定时聆听的设置页。一屏：哪些天、几点到几点、一句说明、一个按钮。
 * 参考 Hemory 的「一次设置，从此自动聆听」，但说明要诚实：系统不让我们替你开麦克风，到点是提醒。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val notice = LocalNotice.current
    var cfg by remember { mutableStateOf(ListenSchedule.load(ctx)) }
    var picking by remember { mutableStateOf<Boolean?>(null) }   // true = 开始时间，false = 结束时间

    val days = listOf(
        Calendar.MONDAY to "一", Calendar.TUESDAY to "二", Calendar.WEDNESDAY to "三", Calendar.THURSDAY to "四",
        Calendar.FRIDAY to "五", Calendar.SATURDAY to "六", Calendar.SUNDAY to "日",
    )

    Column(Modifier.fillMaxSize()) {
        TopBar(onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(DS.Pad.screen)) {
            Spacer(Modifier.height(DS.Rhythm.block))
            Text("一次设置，从此到点提醒", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(DS.Rhythm.tight))
            Text("到开始时间提醒你开始聆听，到结束时间提醒你停止。", style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionLabel("哪些天")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                days.forEach { (d, label) ->
                    val on = d in cfg.days
                    val cs = MaterialTheme.colorScheme
                    Surface(
                        onClick = { cfg = cfg.copy(days = if (on) cfg.days - d else cfg.days + d) },
                        shape = CircleShape,
                        color = if (on) cs.primary else cs.surfaceVariant,
                        modifier = Modifier.size(DS.Size.hit),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.titleSmall,
                                 color = if (on) cs.onPrimary else cs.onSurface)
                        }
                    }
                }
            }

            SectionLabel("时段")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TimeBox(ListenSchedule.clock(cfg.startMin), Modifier.weight(1f)) { picking = true }
                Text("–", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(horizontal = DS.Rhythm.element))
                TimeBox(ListenSchedule.clock(cfg.endMin), Modifier.weight(1f)) { picking = false }
            }
            if (!cfg.valid) {
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text(if (cfg.days.isEmpty()) "至少选一天。" else "结束要晚于开始。",
                     style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(DS.Rhythm.inner))
            NoticeBox(
                "系统不让深脑替你打开麦克风。到开始时间会推一条通知，点一下才开；到结束时间再推一条，点一下才停。",
                Tone.NEUTRAL,
            )
        }
        Column(Modifier.padding(DS.Pad.screen).padding(bottom = DS.Rhythm.inner)) {
            PrimaryButton(
                if (cfg.enabled) "就这样，保存" else "就这样，开始提醒",
                enabled = cfg.valid, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    ListenSchedule.save(ctx, cfg.copy(enabled = true))
                    notice("定好了：" + ListenSchedule.summary(cfg))
                    onBack()
                },
            )
            if (cfg.enabled) QuietButton("关掉定时", modifier = Modifier.fillMaxWidth(), onClick = {
                ListenSchedule.save(ctx, cfg.copy(enabled = false)); onBack()
            }) else QuietButton("以后再设", modifier = Modifier.fillMaxWidth(), onClick = onBack)
        }
    }

    picking?.let { isStart ->
        val init = if (isStart) cfg.startMin else cfg.endMin
        val state = rememberTimePickerState(initialHour = init / 60, initialMinute = init % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            shape = DS.Radius.sheet,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (isStart) "开始时间" else "结束时间", style = MaterialTheme.typography.titleLarge) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TonalButton("就这个", onClick = {
                    val m = state.hour * 60 + state.minute
                    cfg = if (isStart) cfg.copy(startMin = m) else cfg.copy(endMin = m)
                    picking = null
                })
            },
            dismissButton = { QuietButton("取消", onClick = { picking = null }) },
        )
    }
}

@Composable
private fun TimeBox(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = DS.Radius.control, color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Box(Modifier.padding(DS.Pad.card), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        }
    }
}
