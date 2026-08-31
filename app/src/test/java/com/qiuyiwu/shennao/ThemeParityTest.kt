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
 */
class ThemeParityTest {

    private fun tokensTs(): String {
        // 从 clients/android/app 往上找到仓库根
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "packages/ui/src/tokens.ts").isFile) d = d.parentFile
        assertNotNull("找不到 packages/ui/src/tokens.ts（真源）", d)
        return File(d, "packages/ui/src/tokens.ts").readText()
    }

    /** 从 tokens.ts 里取某个键的十六进制色值 */
    private fun hexOf(src: String, key: String): String {
        val m = Regex("""\b${Regex.escape(key)}:\s*'(#[0-9a-fA-F]{6})'""").find(src)
        assertNotNull("tokens.ts 里找不到 $key", m)
        return m!!.groupValues[1].uppercase()
    }

    private fun theme(): String =
        File("src/main/java/com/qiuyiwu/shennao/Theme.kt").readText()

    private fun androidHex(name: String): String {
        val m = Regex("""val\s+${Regex.escape(name)}\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)""").find(theme())
        assertNotNull("Theme.kt 里找不到 $name", m)
        return "#" + m!!.groupValues[1].uppercase()
    }

    @Test fun `中性灰阶和网页逐字相同`() {
        val src = tokensTs()
        for (n in listOf(50, 100, 200, 300, 400, 500, 600, 700, 800, 900)) {
            assertEquals("ink-$n 漂了", hexOf(src, "$n"), androidHex("c$n"))
        }
    }

    @Test fun `品牌色和网页逐字相同`() {
        val src = tokensTs()
        assertEquals(hexOf(src, "focus"), androidHex("focus"))
        assertEquals(hexOf(src, "focusBright"), androidHex("focusBright"))
        assertEquals(hexOf(src, "focusSoft"), androidHex("focusSoft"))
        assertEquals(hexOf(src, "iris"), androidHex("iris"))
    }

    @Test fun `语义色取的是网页同一组值`() {
        val src = tokensTs()
        // state 那几行是单行对象，按组取
        fun group(name: String, field: String): String {
            val line = Regex("""$name:\s*\{[^}]*\}""").find(src)!!.value
            return Regex("""\b$field:\s*'(#[0-9a-fA-F]{6})'""").find(line)!!.groupValues[1].uppercase()
        }
        assertEquals(group("ok", "text"), androidHex("okText"))
        assertEquals(group("warn", "text"), androidHex("warnText"))
        assertEquals(group("risk", "text"), androidHex("riskText"))
        assertEquals(group("info", "text"), androidHex("infoText"))
    }

    /*
     * 表面阶梯（文档 §2.5）：页面底是 ink-50，卡片才是白的。
     * 两个都用白，所有东西会糊在一个平面上——手机端「看着不精致」的根子
     * 就在这里，而它是一行代码的事，很容易在某次「统一一下背景色」时被改回去。
     */
    @Test fun `页面底和卡片不能是同一个颜色`() {
        val t = theme()
        val light = t.substring(t.indexOf("LightColors"), t.indexOf("DarkColors"))
        assertTrue("页面底该是 ink-50", Regex("""background\s*=\s*Ink\.c50""").containsMatchIn(light))
        assertTrue("卡片该是白的", Regex("""surface\s*=\s*Color\.White""").containsMatchIn(light))
    }

    @Test fun `卡片描边用 ink-100，不是更重的 ink-200`() {
        val t = theme()
        val light = t.substring(t.indexOf("LightColors"), t.indexOf("DarkColors"))
        // 边框重了，一屏十几张卡片会连成一张网格纸
        assertTrue(Regex("""outlineVariant\s*=\s*Ink\.c100""").containsMatchIn(light))
    }
}
