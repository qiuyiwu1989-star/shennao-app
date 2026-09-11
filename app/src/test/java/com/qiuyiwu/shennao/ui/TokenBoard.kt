package com.qiuyiwu.shennao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qiuyiwu.shennao.DS

/**
 * 色板：把设计系统在这块屏上的样子一次摆全。
 *
 * 存在的理由是「精致」这件事没法靠读代码判断。表面阶梯分不分得开、
 * 次要文字在暗色下还看不看得见、卡片边框有没有糊掉——都得看一眼才知道。
 * 这个 composable 只在截图测试里用，不进包。
 */
@Composable
fun TokenBoard() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.Pad.screen),
        verticalArrangement = Arrangement.spacedBy(DS.Rhythm.inner),
    ) {
        Spacer(Modifier.height(DS.Rhythm.inner))
        Text("深脑 · 色板", style = MaterialTheme.typography.headlineSmall)
        Text("表面阶梯、文字层级、语义色。这张图看不出层次，手机上就一定糊。",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)

        Section("表面阶梯") {
            Swatch("background 页面底", cs.background, cs.onBackground)
            Swatch("surface 卡片", cs.surface, cs.onSurface)
            Swatch("surfaceVariant", cs.surfaceVariant, cs.onSurfaceVariant)
        }
        Section("文字层级") {
            Text("标题 · onSurface", color = cs.onSurface, style = MaterialTheme.typography.titleMedium)
            Text("正文 · onSurface", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text("次要 · onSurfaceVariant（暗色下最容易糊的一档）",
                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Section("语义色") {
            Swatch("primary 主色", cs.primary, cs.onPrimary)
            Swatch("primaryContainer", cs.primaryContainer, cs.onPrimaryContainer)
            Swatch("error 风险", cs.error, cs.onError)
            Swatch("errorContainer", cs.errorContainer, cs.onErrorContainer)
        }
        Section("控件") {
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.element)) {
                Button(onClick = {}, shape = DS.Radius.control) { Text("主按钮") }
                OutlinedButton(onClick = {}, shape = DS.Radius.control) { Text("次按钮") }
            }
            Card(shape = DS.Radius.card) {
                Column(Modifier.padding(DS.Pad.default)) {
                    Text("卡片标题", style = MaterialTheme.typography.titleSmall)
                    Text("卡片和页面底分不分得开，全看这一块的边界。",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(DS.Rhythm.page))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DS.Rhythm.element)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Swatch(name: String, bg: Color, fg: Color) {
    Row(
        Modifier.fillMaxWidth().background(bg, DS.Radius.control).padding(DS.Pad.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(name, color = fg, style = MaterialTheme.typography.bodyMedium) }
}
