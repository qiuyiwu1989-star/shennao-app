package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/*
 * 页型（对应设计系统 §4.8 在手机端的落法）。
 *
 * 网页那边实测的病是：68 个页面各自决定多宽、页头怎么写、空了显示什么，
 * 于是同为详情页出现四种容器宽度。手机端是同一个病的小号版本——
 * 五个屏各写各的边距和骨架，读起来「说不出哪里乱，但就是乱」。
 *
 * 页型就是把「这是一种什么页」变成一个有答案的问题。手机端只需要四种：
 *
 *   列表页  抬头 + 可选筛选 + 列表 + 空态出口     今天 / 会议 / 搜索结果
 *   详情页  带返回的抬头 + 正文                    会议 / 人物
 *   看板页  抬头 + 指标行 + 分区                   （暂无，留给驾驶舱）
 *   工具页  控件占满，无滚动                        录音台
 *
 * 三条铁律照搬网页：**同型必同宽**（这里是同边距 DS.Pad.screen）、**页头形制统一**、
 * **每一处页面级空态都给下一步动作**。
 */

/**
 * 列表页。
 *
 * 空态强制要求给出口：网页那边 32 处空态没有下一步动作，用户落在一张空页上
 * 只能退出去——这条在手机上更致命，因为手机没有「退到哪里」可选。
 * 所以 `empty` 是必填参数，不是可选的。
 */
@Composable
fun ListPage(
    title: String,
    subtitle: String? = null,
    /** 顶部附加区（频道条、搜索框等），跟着抬头一起固定不滚 */
    sticky: (@Composable () -> Unit)? = null,
    isEmpty: Boolean,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        PageHead(title, subtitle)
        sticky?.invoke()
        if (isEmpty) {
            Box(Modifier.fillMaxSize(), Alignment.TopCenter) { empty() }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = DS.Pad.list(),
                verticalArrangement = Arrangement.spacedBy(DS.Rhythm.element),
                content = content,
            )
        }
    }
}

/**
 * 详情页。返回件收进顶栏的固定位置——**不许各屏自己在正文里摆一个**，
 * 那正是网页 §4.8 刚治过的病（返回件散落在各页正文顶部，位置每页不同）。
 */
@Composable
fun DetailPage(
    onBack: () -> Unit,
    title: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 调用方要程序化滚动（如「依据 → 回到原话」定位）时传自己的 state；默认各自一份 */
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        TopBar(onBack, actions)
        title?.let {
            Text(it, style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.padding(DS.Pad.screen).padding(top = DS.Rhythm.tight))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = DS.Pad.list(),
            verticalArrangement = Arrangement.spacedBy(DS.Rhythm.element),
            content = content,
        )
    }
}

/** 页头。抬头形制统一：主标题一档、副标题一档，间距固定。 */
@Composable
fun PageHead(title: String, subtitle: String? = null) {
    Column(Modifier.padding(DS.Pad.screen).padding(top = DS.Rhythm.section, bottom = DS.Rhythm.inner)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let {
            Spacer(Modifier.height(DS.Rhythm.tight))
            Text(it, style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
