package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * 「今天」页：三段提炼过的内容，每条都有路。
 *
 * 顺序是有讲究的，按「多久之内不看就来不及」排：
 *   1. 到期承诺 —— 今天不问，明天就更难开口
 *   2. 该给说法的预测 —— 到期了还悬着，再拖判断力就攒不起来
 *   3. 新洞察 —— 没有时间压力，攒着慢慢看
 *
 * 每段都有硬上限。一屏读得完的三条，比读不完的三十条有用——
 * 这是手机端和网页端最大的区别，网页可以铺开，手机不行。
 */

@Composable
fun TodayScreen(
    today: Today,
    onOpenTranscript: (String) -> Unit,
    onRecord: () -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Header(today) }

        // 「录」放在最上面、在所有内容之前。手机端的录音是随手发生的——
        // 掏出来就要能按下去，不该先滚过三段判断才找得到它。
        item { RecordBar(onRecord) }

        if (today.commitments.isNotEmpty()) {
            item { SectionTitle("下文", "别人说出口、还没有下文的事") }
            items(today.commitments, key = { it.id }) { c ->
                CommitmentCard(c) { c.transcriptId?.let(onOpenTranscript) }
            }
        }

        if (today.predictions.isNotEmpty()) {
            item { SectionTitle("该给个说法", "到期的预测，应验了还是落空了") }
            items(today.predictions, key = { it.id }) { PredictionCard(it) }
        }

        if (today.insights.isNotEmpty()) {
            item { SectionTitle("新判断", "最近沉进大脑的") }
            items(today.insights, key = { it.id }) { i ->
                InsightCard(i) { i.transcriptId?.let(onOpenTranscript) }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新") }
        }
    }
}

@Composable
private fun Header(t: Today) {
    Column {
        Text("今天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        // 四种「空」要分开说。它们在界面上长得一样，
        // 而「坏了」这一类用户永远不会主动报告。
        val line = when {
            t.notReady -> "下文还没准备好——服务端的迁移还没跑。"
            t.failed -> "取数失败了，不是「没有内容」。"
            t.commitments.isEmpty() && t.predictions.isEmpty() && t.insights.isEmpty() ->
                if (t.counts.awaitingSpeaker > 0)
                    "有 ${t.counts.awaitingSpeaker} 条问不了——还没认出是谁说的，到网页里认一下。"
                else "今天没有要紧的事。"
            else -> buildList {
                if (t.counts.overdue > 0) add("${t.counts.overdue} 条承诺过期")
                if (t.predictions.isNotEmpty()) add("${t.predictions.size} 条预测该给说法")
                if (t.insights.isNotEmpty()) add("${t.insights.size} 条新判断")
            }.joinToString(" · ")
        }
        Text(line, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun RecordBar(onRecord: () -> Unit) {
    val live = com.qiuyiwu.shennao.record.RecordingService.recording
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (live) "正在录音" else "录这场会",
                     style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Text(
                    if (live) "点进去看时长、或者停止"
                    else "录完自动推到深脑，转写和分析在那边跑",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRecord) { Text(if (live) "查看" else "开始") }
        }
    }
}

@Composable
private fun SectionTitle(title: String, hint: String) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommitmentCard(c: Commitment, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.speakerName, style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // 逾期用文字说清楚，不只靠颜色——阳光下和色觉障碍面前颜色都不可靠
                val od = c.overdueDays
                Text(
                    when {
                        od != null && od > 0 -> "过期 $od 天"
                        c.dueDate != null -> c.dueDate
                        else -> "期限待确认"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (od != null && od > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            // 原话是主角，不是摘要——「他当时是这么说的」才问得出口
            Text("「${c.quote}」", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Footer(
                meta = listOfNotNull(c.saidDate.takeIf { it.isNotBlank() }, c.context).joinToString(" · "),
                canOpen = c.transcriptId != null,
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun PredictionCard(p: Prediction) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.subject ?: "未指明对象", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                val od = p.overdueDays
                Text(if (od != null && od > 0) "到期 $od 天" else (p.dueAt ?: ""),
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(p.statement, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            // 没有可观测信号的预测是验不了的。空着不能不说——
            // 那说明当初写这条时就没定清楚怎么算数，本身是个该看见的信号。
            Text(
                p.observableSignal?.let { "看这个：$it" } ?: "当初没写清怎么算数——只能凭印象判断",
                style = MaterialTheme.typography.bodySmall,
                color = if (p.observableSignal == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InsightCard(i: Insight, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                i.subject?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                }
                // 认知等级必须显示。手机是一扫而过的场景，
                // 一条「猜想」会被当成事实——比坐在电脑前更危险。
                AssistChip(onClick = {}, label = { Text(epistemicLabel(i.epistemic)) })
            }
            Spacer(Modifier.height(6.dp))
            Text(i.statement, style = MaterialTheme.typography.bodyLarge)
            if (i.quote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("「${i.quote}」", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Footer(meta = atomTypeLabel(i.atomType), canOpen = i.transcriptId != null, onOpen = onOpen)
        }
    }
}

@Composable
private fun Footer(meta: String, canOpen: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(meta, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        // 「路」：点进去回到那场会。没有路的卡片读完就断了，
        // 那就只是又一条信息流。
        if (canOpen) TextButton(onClick = onOpen) { Text("去那场会") }
    }
}

private fun epistemicLabel(e: String) = when (e) {
    "attested" -> "有原话"
    "inferred" -> "推断"
    "conjecture" -> "猜想"
    else -> e.ifBlank { "—" }
}

private fun atomTypeLabel(t: String) = when (t) {
    "decision" -> "决定"
    "signal" -> "信号"
    "contradiction" -> "矛盾"
    "principle" -> "原则"
    "judgment" -> "人物判断"
    "fact" -> "事实"
    "open_question" -> "待解"
    else -> t
}
