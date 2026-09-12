package com.qiuyiwu.shennao.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qiuyiwu.shennao.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * 无障碍门禁：命中区、读屏标签、角色、标题。
 *
 * 这里不测「TalkBack 念出来好不好听」——那要真机。测的是**能用语义树判定的硬指标**：
 *   · 每个能点的节点，触达区 ≥ 48dp（安卓的下限，DS.Size.hit）；
 *   · 每个能点的节点，读屏能念出点什么（有文字或 contentDescription），不是一个哑的图形；
 *   · 开关带自己的名字（Switch 只会念「开 / 关」）；
 *   · 分区小标是标题（读屏用户靠「按标题跳」在长列表里找地方）。
 *
 * 2026-09-13 审计前：周带的圆 32dp 宽、可点的人名 22dp 高、录音大圆在录着时没有任何描述、
 * 三个 Switch 都不知道自己是谁的开关。这些都是「看得见的人永远不会发现」的那类缺陷。
 *
 * 屏幕拉高到 2400dp：语义树里的 bounds 是**裁切后的**，列表底部露一半的行会被算成半高，
 * 门禁就会红在取景框上而不是界面上（ScreenTest 里踩过一次同类坑）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class AccessibilityTest {

    @get:Rule val compose = createComposeRule()

    private fun demoClient(): DeepBrainClient {
        Demo.install()
        return Session.client(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }
    private fun demoToday(): Today = (demoClient().today() as ApiResult.Ok).value

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { ShennaoTheme { content() } }
        compose.waitForIdle()
    }

    private fun clickables(): List<SemanticsNode> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()

    /** 读屏能念出来的话：合并后的文字或描述。 */
    private fun spoken(n: SemanticsNode): String {
        val cd = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("；") ?: ""
        val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("；") { it.text } ?: ""
        val edit = n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""
        return listOf(cd, text, edit).filter { it.isNotBlank() }.joinToString("；")
    }

    private fun describe(n: SemanticsNode) = "「${spoken(n).take(30)}」@${n.boundsInRoot}"

    /** 触达区：语义树的 touchBounds 已经把 minimumInteractiveComponentSize 补的那圈算进去。 */
    private fun assertHitTargets() {
        val min = with(compose.density) { DS.Size.hit.toPx() } - 0.5f   // 半像素的舍入余量
        val small = clickables().filter { n ->
            val b = n.touchBoundsInRoot
            // 文本框：点它是聚焦不是按钮，高度由内容定，不在这条门禁里
            n.config.getOrNull(SemanticsProperties.EditableText) == null &&
                (b.width < min || b.height < min)
        }
        assertTrue(
            "触达区小于 48dp 的可点节点：\n" + small.joinToString("\n") { "${describe(it)} touch=${it.touchBoundsInRoot}" },
            small.isEmpty(),
        )
    }

    /** 每个能点的节点都要念得出东西。 */
    private fun assertLabelled() {
        val mute = clickables().filter { spoken(it).isBlank() }
        assertTrue("读屏念不出名字的可点节点：\n" + mute.joinToString("\n") { "${it.id}@${it.boundsInRoot} role=${it.config.getOrNull(SemanticsProperties.Role)}" },
                   mute.isEmpty())
    }

    private fun headings(): Int =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().size

    // ---- 四屏 ----

    @Test fun `今天：可点的都够大、都念得出`() {
        val today = demoToday()
        show { Column { LiveBar(onClick = {}); TodayScreen(today, {}, {}, {}) } }
        assertHitTargets()
        assertLabelled()
        // 顶上那条是个按钮，不是一段字
        compose.onNodeWithText("点一下开始录").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
    }

    @Test fun `记录：时间线行是按钮、周带的天有名字`() {
        val client = demoClient()
        show { HistoryScreen(client, {}, {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag)).fetchSemanticsNodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "loading" } }
        assertHitTargets()
        assertLabelled()
        // 周带：七天各有一个说得清「周几、几号」的节点
        val days = compose.onAllNodesWithContentDescription("周", substring = true).fetchSemanticsNodes()
        assertEquals("周带该有七天", 7, days.size)
        // 时间线的行合成一个节点并且是按钮：不然读屏只念标题，不知道能点进去
        val rows = clickables().filter { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button && spoken(it).contains("·") }
        assertTrue("没有一行时间线是按钮", rows.isNotEmpty())
    }

    @Test fun `录音台：大圆有描述、开关知道自己是谁的`() {
        show { RecordScreen(onBack = {}) }
        assertHitTargets()
        assertLabelled()
        compose.onNodeWithContentDescription("开始录音").assertExists()
        compose.onNodeWithContentDescription("持续聆听").assertIsOff()
    }

    @Test fun `我的：开关带名字、行是按钮、分区有标题`() {
        val client = demoClient()
        show { MeScreen(client, { _, _ -> }, {}, http = Demo.http!!, versionName = "x.y.z") }
        compose.waitUntil(5_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag)).fetchSemanticsNodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "loading" } }
        assertHitTargets()
        assertLabelled()
        assertTrue("「录音 / 应用 / 数据」三个分区该是标题", headings() >= 3)
        compose.onNodeWithContentDescription("全时聆听").assertIsOff()
        val rows = clickables().filter { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button && spoken(it).startsWith("灵魂卡") }
        assertEquals("「灵魂卡」这一行该是一个合并的按钮节点", 1, rows.size)
    }

    // ---- 设计系统的件 ----

    @Test fun `DsChip 报选中态与角色`() {
        show { Column { DsChip(selected = true, onClick = {}, label = "时间线"); DsChip(selected = false, onClick = {}, label = "内容") } }
        compose.onNodeWithText("时间线").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("内容").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        assertHitTargets()
    }

    @Test fun `SectionLabel 是标题`() {
        show { SectionLabel("还在手机上") }
        assertEquals(1, headings())
    }

    @Test fun `定时页：星期圆报勾选、时段框说清是开始还是结束`() {
        show { ScheduleScreen(onBack = {}) }
        assertHitTargets()
        assertLabelled()
        compose.onNodeWithContentDescription("周一").assertExists()
        compose.onNodeWithContentDescription("开始时间", substring = true).assertExists()
        compose.onNodeWithContentDescription("结束时间", substring = true).assertExists()
    }
}
