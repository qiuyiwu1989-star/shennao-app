package com.qiuyiwu.shennao.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/*
 * 节奏与字阶的欠账门禁。
 *
 * 2026-09-01 审计：四档节奏定好了却只用 5 处，其余 150+ 处硬编码散在 8 个值上；
 * 而 bodySmall（元信息档）用了 41 次、多于 bodyMedium 27 次——
 * **屏幕上最多的文字是最小号的**，这是「粗糙」最直接的来源。
 *
 * 收敛之后必须有门禁钉住，否则过两周又散回去。这里记基线，**只准变小**。
 */
class RhythmGuardTest {

    private fun screens(): List<File> {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java/com/qiuyiwu/shennao").isDirectory) d = d.parentFile
        assertNotNull("找不到源码目录", d)
        return File(d, "app/src/main/java/com/qiuyiwu/shennao")
            .listFiles { f -> f.name.endsWith(".kt") }!!.toList()
    }

    private fun sources() = screens().joinToString("\n") { it.readText() }

    /**
     * 屏幕代码，**不含 Theme.kt**。
     *
     * 字阶本身当然要写数字——那是它的定义处。第一版门禁把定义也算成了违规，
     * 于是它在挡一个正确的东西。**一条会误伤的门禁比没有门禁更糟**：
     * 人会去关掉它，而不是去修真正的问题。
     */
    private fun screenSources() = screens()
        .filter { it.name != "Theme.kt" && it.name != "DS.kt" }
        .joinToString("\n") { it.readText() }

    /**
     * 裸 dp 的基线。
     *
     * 不追求归零：命中区 48、发丝边 1、圆角 980、组件固定尺寸（声波高度、
     * 主按钮直径）本来就不该走节奏档——**把它们也塞进四档才是真的乱**。
     * 这里挡的是「又开始随手写间距」。
     */
    @Test fun `屏幕代码里的裸 dp 不许再涨`() {
        val n = Regex("""\b\d+\.dp""").findAll(screenSources()).count()
        assertTrue(
            "裸 dp 涨到了 $n（基线 73）。间距请用 DS.Rhythm / DS.Pad——" +
                "散着写的话，每屏间距都不一样，眼睛会觉得乱但说不出哪里乱。",
            n <= 73,
        )
    }

    /**
     * **一屏之内元信息不该多于正文。**
     *
     * bodySmall 是元信息专用档（时间、来源、计数、状态）。它多于正文档，
     * 就说明有内容被降级成了元信息——审计前正是 41 : 27。
     */
    @Test fun `元信息不许多过正文`() {
        val s = screenSources()
        fun count(name: String) = Regex("""typography\.$name\b""").findAll(s).count()
        val meta = count("bodySmall")
        val body = count("bodyLarge") + count("bodyMedium")
        assertTrue(
            "元信息 $meta 处、正文 $body 处——屏幕上最多的文字成了最小号的。" +
                "判据：要读进去的内容用 bodyLarge，帮人理解怎么做的说明用 bodyMedium，" +
                "只有时间/计数/状态才用 bodySmall。",
            meta < body,
        )
    }

    /** 写死的 sp 只准剩巨号时长那一处——字阶存在的意义就是不出现一串数列。 */
    @Test fun `不许绕过字阶写死字号`() {
        val hits = Regex("""fontSize = \d+\.sp""").findAll(screenSources()).map { it.value }.toList()
        assertTrue(
            "写死了 ${hits.size} 处字号：$hits。除巨号时长外都该走 MaterialTheme.typography。",
            hits.size <= 2,
        )
    }

    /** 10sp 及以下的中文在手机上读不出来。这不是审美问题。 */
    @Test fun `字号下限 11sp`() {
        val tiny = Regex("""fontSize = ([0-9]|10)\.sp""").findAll(sources()).map { it.value }.toList()
        assertTrue("出现了小于 11sp 的字号：$tiny", tiny.isEmpty())
    }
}
