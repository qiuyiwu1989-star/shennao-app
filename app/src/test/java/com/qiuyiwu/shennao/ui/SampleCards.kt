package com.qiuyiwu.shennao.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qiuyiwu.shennao.*

/**
 * 卡片的样例。**刻意放的是边角形态**，不是好看的那几张：
 * 标题长到放不下、这一场挑不出值得看的一条、进度文字和重试按钮挤在一行、
 * 单数条时最后一格空着。顺的那些形态本来就不会出问题。
 */
private fun item(
    id: String, title: String, whenText: String = "9月9日 14:30",
    highlight: Highlight? = null, progress: Progress? = null, fromRecording: Boolean = true,
) = MaterialCardItem(id, title, whenText, fromRecording, highlight, progress)

@Composable
fun SampleCards() {
    val rows = listOf(
        listOf(
            item(
                "1", "周三产品评审",
                highlight = Highlight("quote", "先把录音这条链路做稳，别急着加新场景。", "邱懿武", "金句"),
                progress = Progress("done", "已分析", null, false),
            ),
            item(
                "2", "和学校的沟通 · 教育版方案对齐会（这个标题故意很长，长到两行都放不下）",
                highlight = Highlight("decision", "教育版先按场次计费，不按设备。", null, "定下来的"),
                progress = Progress("done", "已分析", null, false),
            ),
        ),
        listOf(
            // 挑不出高亮：卡片就只有下半截。这张是用来确认「没内容」看得出来的
            item("3", "随手录的一段", progress = Progress("analyzing", "分析中", null, false)),
            item(
                "4", "客户回访",
                highlight = Highlight("contradiction", "上周说不做私有化，这次又说要给学校单独部署。", null, "有分歧"),
                progress = Progress("empty", "分析跑完了，但没沉下判断，建议重跑", null, true),
            ),
        ),
        listOf(
            item(
                "5", "上传中的一场", fromRecording = false,
                progress = Progress("uploading", "12/30 段已送达", 0.4f, false),
            ),
        ),
    )
    Column(
        Modifier.fillMaxWidth().padding(DS.Pad.screen),
        verticalArrangement = Arrangement.spacedBy(DS.Rhythm.element),
    ) {
        rows.forEach { MaterialCardRow(it, onOpen = {}, onRetry = {}) }
    }
}
