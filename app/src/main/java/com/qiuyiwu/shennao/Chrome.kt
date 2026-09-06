package com.qiuyiwu.shennao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/*
 * 三态：正在取、什么都没有、出事了。
 *
 * 统一成一处是因为这三种在界面上长得太像了——都是「屏幕上没有内容」。
 * 各写各的，迟早有一个面把「出事了」画成了「什么都没有」，
 * 而那正是用户永远不会来报的那一类问题：他只会觉得「这个 App 里没东西」。
 */

/**
 * 骨架屏。
 *
 * 转圈只说「在等」；骨架说「**要来什么、来多少**」——形状和真实卡片一致，
 * 所以内容到位时视觉上不会跳。
 *
 * **刻意不做闪烁动画。** 那是装饰：静态浅色块已经把信息传到了，
 * 而闪烁在低端机上掉帧，且会把眼睛引到「正在动的东西」上——
 * 那正好是屏幕上唯一没有内容的地方。
 */
@Composable
fun SkeletonList(rows: Int = 3) {
    Column(
        Modifier.fillMaxWidth().padding(DS.Pad.screen),
        verticalArrangement = Arrangement.spacedBy(DS.Rhythm.element),
    ) {
        repeat(rows) { i ->
            DsCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(DS.Pad.tight)) {
                    // 第一行短、第二行长、第三行更短——真实卡片就是这个形状
                    Bar(0.42f)
                    Spacer(Modifier.height(DS.Rhythm.element))
                    Bar(0.92f)
                    Spacer(Modifier.height(DS.Rhythm.tight))
                    // 越往下越淡：暗示「这里还有内容，只是还没到」
                    Bar(if (i == 0) 0.66f else 0.55f)
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(DS.Rhythm.element)
            .background(MaterialTheme.colorScheme.surfaceVariant, DS.Radius.pill)
    )
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(DS.Rhythm.block), Alignment.Center) {
        CircularProgressIndicator(strokeWidth = DS.Size.rule)
    }
}

/**
 * 真的没有内容。要说清楚「没有」是什么意思，并给一个能做的动作。
 *
 * 靠上放，不居中：居中的空态在一屏里浮着，像加载失败；靠上的读起来是「这一栏现在是空的」。
 */
@Composable
fun Empty(title: String, hint: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DS.Rhythm.inner, vertical = DS.Rhythm.block),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(DS.Rhythm.tight))
        Text(hint, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(DS.Rhythm.inner))
            TonalButton(actionLabel, onAction)
        }
    }
}

/**
 * 出事了。**绝不能长得像「没有内容」**——
 * 「取不到」和「没有」对用户是两件完全不同的事，而它们都表现为空屏。
 */
@Composable
fun Broken(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DS.Rhythm.inner, vertical = DS.Rhythm.block),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("没取到", style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(DS.Rhythm.tight))
        Text(message, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center)
        Spacer(Modifier.height(DS.Rhythm.inner))
        PrimaryButton("再试一次", onRetry)
    }
}

/** 顶部那条「这是离线的旧数据」。有缓存可看时用，不遮挡内容。 */
@Composable
fun StaleBanner(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(DS.Pad.screen).padding(vertical = DS.Rhythm.tight),
        )
    }
}
