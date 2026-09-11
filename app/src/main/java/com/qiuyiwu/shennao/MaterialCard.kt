package com.qiuyiwu.shennao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * 已送到深脑的那一场，画成一张**带内容预览**的卡。双栏。
 *
 * 为什么改：这一页原来对已送达的会是「一行一条」，理由写在 HistoryScreen 里——
 * 「卡留给还在路上的，一屏里有卡有行，眼睛才分得出要管的和看看就行的」。
 * 那条判据本身没错，但它解决的是**注意力分级**，而这一页真正的毛病是另一个：
 * 一屏十几条「智能纪要 · 9 月 9 日」，人要一条条点进去才知道哪场有用。
 * 一行标题 + 一个日期，不管排得多干净，都答不了「哪一场值得看」。
 *
 * 所以分级改由**位置和宽度**承担，不再由卡与行承担：
 *   还在手机上的 → 通栏，排最前（唯一可能丢的，必须先看见）
 *   已经送到的   → 双栏卡片，在下面（看看就行的，但要能一眼挑）
 *
 * 卡片顶部摆的是这场里最值得看的一条，不是标题。挑哪一条**由服务端决定**
 * （core 的 pickHighlight：核验过的原话 > 决策 > 矛盾 > 待解 > 概述），
 * 客户端只负责显示。判据抄一份到手机上，下次改必然只改一处。
 *
 * 挑不出来就不摆——**不要拿标题把这块填满**，那会让「有内容」和「没内容」长得一样。
 */

/** 服务端挑出的那一条。kind 决定配色，label 是它自己带的标签，客户端不拼。 */
data class Highlight(val kind: String, val text: String, val speaker: String?, val label: String)

/** 服务端给的处理进度。label 直接显示。 */
data class Progress(val stage: String, val label: String, val ratio: Float?, val retriable: Boolean)

/**
 * 双栏。**不用 LazyVerticalStaggeredGrid**：这一页整体是一个 LazyColumn
 * （页头、灵魂卡状态、还在手机上的、来源分段、账本边界），在它里面嵌一个
 * 纵向懒加载容器会因为高度无穷而崩。两两成对铺进现有列表即可——
 * 瀑布流的参差是锦上添花，一眼挑得出来才是这次要的。
 *
 * 奇数条时最后一格留空，不拉伸剩下那张去占满整行：一张双倍宽的卡会被读成
 * 「这条更重要」，而它只是排在最后而已。
 */
fun materialCardPairs(items: List<MaterialCardItem>): List<List<MaterialCardItem>> = items.chunked(2)

data class MaterialCardItem(
    val id: String,
    val title: String,
    val whenText: String,
    val fromRecording: Boolean,
    val highlight: Highlight?,
    val progress: Progress?,
)

/** 一整行（两张卡，或一张卡 + 一格空）。顶对齐，两张自然高度不同，看着就是参差的。 */
@Composable
fun MaterialCardRow(
    pair: List<MaterialCardItem>,
    onOpen: (MaterialCardItem) -> Unit,
    onRetry: (MaterialCardItem) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.element),
        verticalAlignment = Alignment.Top,
    ) {
        pair.forEach { item ->
            Box(Modifier.weight(1f)) { MaterialCard(item, onOpen, onRetry) }
        }
        // 补满那一格。Spacer 而不是让剩下那张变宽。
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MaterialCard(
    item: MaterialCardItem,
    onOpen: (MaterialCardItem) -> Unit,
    onRetry: (MaterialCardItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    DsCard(Modifier.fillMaxWidth(), onClick = { onOpen(item) }) {
        Column {
            // 上半：内容预览。没有就整块不出现。
            item.highlight?.let { h ->
                val tone = highlightTone(h.kind)
                val c = tone.colors()
                Column(
                    Modifier.fillMaxWidth().background(c.bg).padding(DS.Pad.tight),
                    verticalArrangement = Arrangement.spacedBy(DS.Rhythm.hair),
                ) {
                    Text(h.label, style = MaterialTheme.typography.labelMedium, color = c.fg)
                    Text(
                        h.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        // 原话用斜体，和「我们替你归纳的」区分开：卡片上这两种东西
                        // 长得一样的话，模型的话会被当成当事人说过的话。
                        fontStyle = if (h.kind == "quote") FontStyle.Italic else FontStyle.Normal,
                    )
                    // 署名只有服务端确认过说话人才会给。客户端不自己署——
                    // 把一句话安到没确认的人头上，比不署名严重得多。
                    h.speaker?.let {
                        Text("—— $it", style = MaterialTheme.typography.labelMedium, color = c.fg)
                    }
                }
            }

            // 下半：这是哪一场、什么时候、走到哪了。
            Column(
                Modifier.padding(DS.Pad.tight),
                verticalArrangement = Arrangement.spacedBy(DS.Rhythm.hair),
            ) {
                Text(
                    item.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(if (item.fromRecording) "录音" else "导入", item.whenText.ifBlank { null })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                item.progress?.let { p ->
                    Spacer(Modifier.height(DS.Rhythm.hair))
                    ProgressLine(p) { onRetry(item) }
                }
            }
        }
    }
}

/**
 * 「走到哪了」那一行。三种形态：
 *   上传有确定比例 → 细进度条。这是唯一能诚实给出比例的阶段
 *   分析中         → 不确定进度条。**不画假的百分比**
 *   完成 / 失败 / 跑完没沉下判断 → 一行字，后两者带重试
 */
@Composable
private fun ProgressLine(p: Progress, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tone = when (p.stage) {
        "failed", "empty" -> cs.error
        "done" -> cs.onSurfaceVariant
        else -> cs.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(DS.Rhythm.hair)) {
        when {
            p.ratio != null ->
                LinearProgressIndicator(progress = { p.ratio }, modifier = Modifier.fillMaxWidth())
            p.stage == "analyzing" || p.stage == "queued" || p.stage == "recording" ->
                LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        // 状态文字**独占一行**。双栏只有半屏宽，按钮一旦和它并排，
        // 「分析跑完了，但没沉下判断，建议重跑」就会被截成「但没沉下判断…」——
        // 那句话的后半截（建议重跑）正是它存在的理由，截掉就只剩一句坏消息。
        Text(
            p.label, style = MaterialTheme.typography.bodySmall, color = tone,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
        )
        // 重试是「再跑一次」，不是跳走，所以用安静按钮而不是链接蓝。
        if (p.retriable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            QuietButton("重试", onClick = onRetry)
        }
    }
}

/**
 * 高亮块的底色：靠底色区分类型，不靠边框——卡片本身在浅色下已经有一圈发丝边，
 * 再套一圈就成了框中框。
 *
 * 一律走 [Tone]，不自己调颜色：Tone 才知道深浅色两套该取什么值。
 */
internal fun highlightTone(kind: String): Tone = when (kind) {
    "quote" -> Tone.NEUTRAL        // 金句：安静的底，让那句话自己说话
    "decision" -> Tone.ACCENT      // 定下来的
    "contradiction" -> Tone.RISK   // 有分歧
    "open_question" -> Tone.WARN   // 还没解决的
    else -> Tone.NEUTRAL
}
