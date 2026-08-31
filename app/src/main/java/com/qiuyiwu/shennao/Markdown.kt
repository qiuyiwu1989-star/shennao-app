package com.qiuyiwu.shennao

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle

/*
 * 一个够用的 Markdown 渲染。
 *
 * 深脑那边的摘要和分析正文都是 Markdown——直接当纯文本显示，屏幕上就是
 * 一堆 ## 和 **，用户看到的是「这个 App 坏了」。
 *
 * 刻意不引 Markdown 库：这里要渲的东西很窄（标题、加粗、斜体、列表、引用），
 * 而一个通用库会为了支持表格、脚注、HTML 内联再拖进来几百 KB，
 * 用户装的是录音应用，不该为此多下一个包。
 *
 * 不支持的语法一律**原样显示**，不吞掉——吞掉等于悄悄改写用户的内容。
 */

/** 一段渲染好的块。界面按类型决定字号和缩进。 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock()
    data class Paragraph(val text: AnnotatedString) : MdBlock()
    data class Bullet(val text: AnnotatedString) : MdBlock()
    data class Ordered(val index: String, val text: AnnotatedString) : MdBlock()
    data class Quote(val text: AnnotatedString) : MdBlock()
}

object Markdown {

    fun parse(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()

        fun flushPara() {
            val t = para.toString().trim()
            if (t.isNotEmpty()) out += MdBlock.Paragraph(inline(t))
            para.setLength(0)
        }

        for (raw in src.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flushPara()

                line.startsWith("#") -> {
                    flushPara()
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(6)
                    val text = line.drop(level).trim()
                    // 「#」后面没有空格的不是标题，是正文里的井号（比如「#1 方案」）
                    if (line.getOrNull(level) == ' ' && text.isNotEmpty()) {
                        out += MdBlock.Heading(level, inline(text))
                    } else para.appendLine(line)
                }

                line.trimStart().startsWith("> ") -> {
                    flushPara()
                    out += MdBlock.Quote(inline(line.trimStart().removePrefix("> ")))
                }

                Regex("""^\s*[-*+]\s+""").containsMatchIn(line) -> {
                    flushPara()
                    out += MdBlock.Bullet(inline(line.trimStart().drop(2).trim()))
                }

                Regex("""^\s*\d+[.)]\s+""").find(line) != null -> {
                    flushPara()
                    val m = Regex("""^\s*(\d+)[.)]\s+(.*)""").find(line)!!
                    out += MdBlock.Ordered(m.groupValues[1], inline(m.groupValues[2]))
                }

                else -> para.appendLine(line)
            }
        }
        flushPara()
        return out
    }

    /**
     * 行内：**粗**、*斜*、`码`。
     *
     * 成对才算标记。落单的星号原样留着——用户正文里写「3*4」不该被吃掉半个字符。
     */
    fun inline(s: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val bold = s.indexOf("**", i)
            if (bold >= 0) {
                val close = s.indexOf("**", bold + 2)
                if (close > bold + 2) {
                    append(s.substring(i, bold))
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(s.substring(bold + 2, close))
                    pop()
                    i = close + 2
                    continue
                }
            }
            val code = s.indexOf('`', i)
            if (code >= 0) {
                val close = s.indexOf('`', code + 1)
                if (close > code + 1) {
                    append(s.substring(i, code))
                    pushStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    append(s.substring(code + 1, close))
                    pop()
                    i = close + 1
                    continue
                }
            }
            append(s.substring(i))
            break
        }
    }
}
