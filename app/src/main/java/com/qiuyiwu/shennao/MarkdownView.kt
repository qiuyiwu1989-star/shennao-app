package com.qiuyiwu.shennao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 把解析好的块画出来。字阶沿用主题，不另立一套。 */
@Composable
fun MarkdownText(src: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    modifier = Modifier.padding(top = 6.dp),
                )
                is MdBlock.Paragraph -> Text(b.text, style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Bullet -> Row {
                    Text("·", style = MaterialTheme.typography.bodyMedium,
                         modifier = Modifier.padding(end = 8.dp))
                    Text(b.text, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Ordered -> Row {
                    Text("${b.index}.", style = MaterialTheme.typography.bodyMedium,
                         modifier = Modifier.padding(end = 8.dp))
                    Text(b.text, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Quote -> Row {
                    // 引用用左侧竖线而不是斜体：中文斜体在安卓上是机器倾斜的，很难看
                    Box(Modifier.width(3.dp).heightIn(min = 18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant))
                    Spacer(Modifier.width(10.dp))
                    Text(b.text, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
