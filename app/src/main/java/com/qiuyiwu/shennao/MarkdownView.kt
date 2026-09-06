package com.qiuyiwu.shennao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/** 把解析好的块画出来。字阶沿用主题，不另立一套。 */
@Composable
fun MarkdownText(src: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DS.Rhythm.tight)) {
        Markdown.parse(src).forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    b.text,
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = DS.Rhythm.tight),
                )
                is MdBlock.Paragraph -> Text(b.text, style = MaterialTheme.typography.bodyLarge)
                is MdBlock.Bullet -> Row {
                    Text("·", style = MaterialTheme.typography.bodyLarge,
                         modifier = Modifier.padding(end = DS.Rhythm.tight))
                    Text(b.text, style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Ordered -> Row {
                    Text("${b.index}.", style = MaterialTheme.typography.bodyLarge,
                         modifier = Modifier.padding(end = DS.Rhythm.tight))
                    Text(b.text, style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Quote -> Row {
                    // 引用用左侧竖线而不是斜体：中文斜体在安卓上是机器倾斜的，很难看
                    Box(Modifier.width(DS.Size.quoteBar).heightIn(min = DS.Rhythm.inner).background(cs.outline))
                    Spacer(Modifier.width(DS.Rhythm.element))
                    Text(b.text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
                is MdBlock.Rule -> HorizontalDivider(
                    color = cs.outlineVariant, modifier = Modifier.padding(vertical = DS.Rhythm.tight))
                /*
                 * 表格。手机屏放不下三列长文，按行摊开：每行一个嵌块，
                 * 第一列是这一行的标题，其余列前面标上表头名。
                 * 之前是原样显示「| 维度 | 判断 | 依据 |」——竖线满屏，像坏了。
                 */
                is MdBlock.Table -> Column(verticalArrangement = Arrangement.spacedBy(DS.Rhythm.tight)) {
                    b.rows.forEach { row ->
                        Column(
                            Modifier.fillMaxWidth().background(cs.surfaceContainer, DS.Radius.control)
                                .padding(DS.Pad.tight),
                        ) {
                            row.forEachIndexed { i, cell ->
                                if (cell.isBlank()) return@forEachIndexed
                                if (i == 0) {
                                    Text(cell, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                } else {
                                    val head = b.header.getOrNull(i)?.text?.takeIf { it.isNotBlank() }
                                    if (i > 1 || head != null) Spacer(Modifier.height(DS.Rhythm.tight))
                                    if (head != null) Text(head, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                                    Text(cell, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
