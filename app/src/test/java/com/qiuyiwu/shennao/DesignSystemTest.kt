package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/*
 * 设计系统 4.1 里新加的几个纯函数。它们决定屏幕上「怎么称呼、怎么说状态、Markdown 长什么样」，
 * 会被反复改措辞，所以钉住。
 */
class DesignSystemTest {

    // ---- 会议怎么称呼 ----

    @Test fun `文件名式的标题换成「录音 · 时间」`() {
        assertEquals("录音 · 8月29日 14:03", SessionTitles.display("note20260829-140354", "8月29日 14:03"))
        assertEquals("录音 · 8月29日 14:03", SessionTitles.display("20260829_140354.wav", "8月29日 14:03"))
        assertEquals("录音 · 8月29日 14:03", SessionTitles.display("手机录音", "8月29日 14:03"))
        assertEquals("录音", SessionTitles.display("", null))
    }

    @Test fun `真标题照用`() {
        assertEquals("AI赋能教育的模式选择与代价", SessionTitles.display("AI赋能教育的模式选择与代价", "8月29日"))
        // 名字里有数字不算文件名
        assertEquals("Q3 复盘 20260901", SessionTitles.display("Q3 复盘 20260901", "x"))
    }

    // ---- 卡住的原因 ----

    @Test fun `内部任务名不给用户看`() {
        assertEquals("这场录音没有留下任何音频或转写", Problems.humanize("reaper 收尾：这场录音没有留下任何音频或转写"))
        assertEquals("转写服务超时", Problems.humanize("转写服务超时"))
        // 去掉前缀之后空了就还原
        assertEquals("reaper 收尾：", Problems.humanize("reaper 收尾："))
    }

    // ---- 站点药丸 ----

    @Test fun `分析完和不知道不显示站点，其余一站一个词`() {
        assertNull(stagePill(Stage.ANALYZED))
        assertNull(stagePill(Stage.UNKNOWN))
        assertEquals("转写中" to Tone.INFO, stagePill(Stage.DELIVERED))
        assertEquals("分析中" to Tone.INFO, stagePill(Stage.TRANSCRIBED))
        assertEquals("没成功" to Tone.RISK, stagePill(Stage.FAILED))
    }

    // ---- Markdown：表格、分割线、引用锚 ----

    @Test fun `表格按行解析，对齐行跳过`() {
        val src = "【战略情境判断】\n| 维度 | 判断 | 依据 |\n| :--- | :--- | :--- |\n| 战略阶段 | 转折点 | 已服务海尔 [#claim-1]。 |\n后面一段"
        val blocks = Markdown.parse(src)
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("维度", "判断", "依据"), table.header.map { it.text })
        assertEquals(1, table.rows.size)
        assertEquals(listOf("战略阶段", "转折点", "已服务海尔。"), table.rows[0].map { it.text })
        assertTrue(blocks.last() is MdBlock.Paragraph)
    }

    @Test fun `三个短横是分割线，不是正文`() {
        val blocks = Markdown.parse("上\n---\n下")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.Rule)
    }

    @Test fun `网页的引用锚去掉，别的方括号原样留着`() {
        assertEquals("寻找可沉淀的模式。", Markdown.inline("寻找可沉淀的模式 [#claim-1]。").text)
        assertEquals("见 [附录]", Markdown.inline("见 [附录]").text)
        // 落单的星号不吃
        assertEquals("3*4", Markdown.inline("3*4").text)
    }

    @Test fun `井号后面没空格不是标题`() {
        val blocks = Markdown.parse("方法 #008-mckinsey-strategy")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }
}
