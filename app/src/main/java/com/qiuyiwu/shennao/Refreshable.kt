package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll

/*
 * 下拉刷新。全局一份。
 *
 * 每个页面各写一份的话，「什么时候算刷完」这个判据会分叉——
 * 有的页面转圈永远停不下来（忘了收尾），有的一松手就停（还没取完）。
 * 而这两种都表现为「这个 App 的刷新怪怪的」，没人会来报。
 *
 * 判据只有一条：**转圈的开始和结束都由调用方的挂起函数决定**。
 * 函数返回了就停，抛了也停——不做超时兜底，超时会把「网络慢」
 * 变成「刷新失败」，而那两件事对用户是不同的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()

    if (state.isRefreshing) {
        LaunchedEffect(true) {
            try { onRefresh() } finally { state.endRefresh() }
        }
    }

    Box(modifier.fillMaxSize().nestedScroll(state.nestedScrollConnection)) {
        content()
        // 只在真的在拉或在转时才画。静止时也画一个圆圈，会和下面的内容
        // 叠在一起——用户看到的是「刷新按钮压在标题上」，像排版坏了。
        if (state.isRefreshing || state.verticalOffset > 0.5f) {
            PullToRefreshContainer(
                state = state,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}
