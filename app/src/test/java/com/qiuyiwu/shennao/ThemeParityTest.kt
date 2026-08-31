package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/*
 * 安卓的色值必须和网页的设计 token 逐字相同。
 *
 * 镜像一份是没办法的事（安卓拿不到 Tailwind class，和图表组件同一个处境），
 * 但**漂移不行**：网页那边调一次色，手机上就成了另一个产品，
 * 而这种不一致没有人会去逐个色号核对。所以直接读真源来比。
 *
 * 测试解析的是**值**不是写法：Theme.kt 里既有 `val Ink50 = Color(0xFF...)`
 * 也有 `val c50 = Ink50` 这样的别名。第一版把写法写死在正则里，
 * 结果规范更新换了命名方式就整片红——**测的该是不变量，不是当时的排版**。
 */
class ThemeParityTest {

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "packages/ui/src/tokens.ts").isFile) d = d.parentFile
        assertNotNull("找不到 packages/ui/src/tokens.ts（真源）", d)
        return d!!
    }

    private fun tokensTs(): String = File(repoRoot(), "packages/ui/src/tokens.ts").readText()

    private fun hexOf(src: String, key: String): String {
        val m = Regex("""\b${Regex.escape(key)}:\s*'(#[0-9a-fA-F]{6})'""").find(src)
        assertNotNull("tokens.ts 里找不到 $key", m)
        return m!!.groupValues[1].uppercase()
    }

    private fun theme(): String =
        File("src/main/java/com/qiuyiwu/shennao/Theme.kt").readText()

    /** 把 Theme.kt 里所有 `val 名字 = Color(0xFFxxxxxx)` 收成表，再把别名解开。 */
    private fun colorMap(): Map<String, String> {
        val src = theme()
        val direct = Regex("""val\s+(\w+)\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)""")
            .findAll(src).associate { it.groupValues[1] to "#" + it.groupValues[2].uppercase() }
        val alias = Regex("""val\s+(\w+)\s*=\s*(\w+)\s*(?:$|\n|;)""")
            .findAll(src).mapNotNull { m ->
                direct[m.groupValues[2]]?.let { m.groupValues[1] to it }
            }.toMap()
        return direct + alias
    }

    private fun androidHex(name: String): String {
        val v = colorMap()[name]
        assertNotNull("Theme.kt 里找不到 $name", v)
        return v!!
    }

    @Test fun `中性灰阶和网页逐字相同`() {
        val src = tokensTs()
        // 规范只在 Theme 里声明用得到的那几档，没声明的不强求
        val map = colorMap()
        var checked = 0
        for (n in listOf(50, 100, 200, 300, 400, 500, 600, 700, 800, 900)) {
            val v = map["c$n"] ?: continue
            assertEquals("ink-$n 漂了", hexOf(src, "$n"), v)
            checked++
        }
        assertTrue("一档都没对上，说明解析写错了而不是颜色对", checked >= 6)
    }

    @Test fun `品牌色和网页逐字相同`() {
        val src = tokensTs()
        assertEquals(hexOf(src, "focus"), androidHex("focus"))
        assertEquals(hexOf(src, "focusSoft"), androidHex("focusSoft"))
        assertEquals(hexOf(src, "iris"), androidHex("iris"))
    }

    @Test fun `语义色取的是网页同一组值`() {
        val src = tokensTs()
        fun group(name: String, field: String): String {
            val line = Regex("""$name:\s*\{[^}]*\}""").find(src)!!.value
            return Regex("""\b$field:\s*'(#[0-9a-fA-F]{6})'""").find(line)!!.groupValues[1].uppercase()
        }
        assertEquals(group("risk", "text"), androidHex("riskText"))
        assertEquals(group("ok", "text"), androidHex("okText"))
    }

    /*
     * 表面阶梯（文档 §2.5）：页面底是 ink-50，卡片才是白的。
     * 两个都用白，所有东西会糊在一个平面上——手机端「看着不精致」的根子
     * 就在这里，而它是一行代码的事，很容易在某次「统一一下背景色」时被改回去。
     */
    @Test fun `页面底和卡片不能是同一个颜色`() {
        val t = theme()
        val light = t.substring(t.indexOf("LightColors"), t.indexOf("DarkColors"))
        val bg = Regex("""background\s*=\s*(\w+)""").find(light)!!.groupValues[1]
        val sf = Regex("""surface\s*=\s*([\w.]+)""").find(light)!!.groupValues[1]
        assertEquals("页面底该是 ink-50", "#F6F7F9", colorMap()[bg])
        assertTrue("卡片该是白的，实际 $sf", sf.contains("White"))
    }

    /*
     * 规范 §12：暗色必须做，而且不是把浅色反过来——
     * 深底上 ink-900 当主色是看不见的，蓝色也要提亮（#0052d9 在 ink-900 上只有 2.1:1）。
     */
    @Test fun `暗色的主色不能沿用浅色的主色`() {
        val t = theme()
        val light = t.substring(t.indexOf("LightColors"), t.indexOf("DarkColors"))
        val dark = t.substring(t.indexOf("DarkColors"))
        val lp = Regex("""primary\s*=\s*([\w.()x0-9A-Fa-f]+)""").find(light)!!.groupValues[1]
        val dp = Regex("""primary\s*=\s*([\w.()x0-9A-Fa-f]+)""").find(dark)!!.groupValues[1]
        assertNotEquals("暗色直接沿用浅色主色，深底上会看不见", lp, dp)
    }
}
