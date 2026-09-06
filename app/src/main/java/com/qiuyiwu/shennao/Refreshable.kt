package com.qiuyiwu.shennao

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * 下拉刷新。onRefresh 是挂起函数：拉完等它真的取完再收起，不是转一下就放。
 * Material3 1.3（BOM 2024.09）把 PullToRefreshContainer 换成了 PullToRefreshBox（012 P3-7 升级时改）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) scope.launch {
                refreshing = true
                try { onRefresh() } finally { refreshing = false }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { content() }
}
