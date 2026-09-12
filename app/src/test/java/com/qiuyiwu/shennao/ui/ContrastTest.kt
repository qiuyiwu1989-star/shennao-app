package com.qiuyiwu.shennao.ui

import androidx.compose.ui.graphics.Color
import com.qiuyiwu.shennao.DarkColors
import com.qiuyiwu.shennao.LightColors
import com.qiuyiwu.shennao.Tone
import com.qiuyiwu.shennao.colors
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 颜色对比度门禁（WCAG 2.1 的相对亮度算法，纯逻辑，不起 Compose）。
 *
 * 判据：正文 4.5:1（1.4.3），标签 / 药丸 / 按钮字 3:1（大字或 UI 组件，1.4.11），
 * 输入框描边 3:1（1.4.11 非文字组件）。
 *
 * 为什么是「算」而不是「看」：截图基准能看出「暗色下次要文字糊了」，但看的人要先想到去看；
 * 而每次换一个色值，受影响的是几十个界面，没有人会逐个核对。2026-09-13 审计时，
 * 暗色的 onSurfaceVariant（ink-300）对嵌块底只有 4.3、对控件底 3.8，浅色的输入框描边对白只有 1.4——
 * 都是「看起来还行」但过不了线的那种。这里把每一对钉死，亮暗两套都算。
 */
class ContrastTest {

    /** WCAG 相对亮度。Color 是 sRGB，先解伽马再按 0.2126 / 0.7152 / 0.0722 加权。 */
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double { val x = v.toDouble(); return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    /** 对比度 = (亮 + 0.05) / (暗 + 0.05)。 */
    internal fun ratio(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun hex(c: Color) = "#%06X".format(c.value.shr(32).toLong() and 0xFFFFFF)

    private fun check(what: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertTrue("$what：${hex(fg)} 在 ${hex(bg)} 上只有 %.2f:1，要 %.1f:1".format(r, min), r >= min)
    }

    private val BODY = 4.5
    private val LABEL = 3.0

    private fun schemes() = listOf("浅色" to LightColors, "暗色" to DarkColors)

    @Test fun `算法本身对得上标准样例`() {
        // 黑对白 21:1；#777777 对白 4.48（WCAG 官方例子里刚好不过 4.5 的那个灰）
        assertTrue(ratio(Color.Black, Color.White) > 20.9)
        val g = ratio(Color(0xFF777777), Color.White)
        assertTrue("$g", g > 4.4 && g < 4.5)
    }

    /** 主文字与次要文字在每一级表面上都得是正文级。次要文字是全 App 用得最多的一个角色。 */
    @Test fun `正文在每一级表面上都过 4_5`() {
        for ((name, cs) in schemes()) {
            for ((sname, s) in listOf("surface" to cs.surface, "background" to cs.background,
                                      "surfaceVariant" to cs.surfaceVariant, "surfaceContainer" to cs.surfaceContainer)) {
                check("$name 主文字 onSurface 在 $sname", cs.onSurface, s, BODY)
            }
            // 次要文字：在卡片和页面底上是正文档；在嵌块 / 控件底上只作标签用（见下一条）
            check("$name 次要文字 onSurfaceVariant 在 surface", cs.onSurfaceVariant, cs.surface, BODY)
            check("$name 次要文字 onSurfaceVariant 在 background", cs.onSurfaceVariant, cs.background, BODY)
            check("$name 次要文字 onSurfaceVariant 在 surfaceContainer", cs.onSurfaceVariant, cs.surfaceContainer, LABEL)
            check("$name 次要文字 onSurfaceVariant 在 surfaceVariant", cs.onSurfaceVariant, cs.surfaceVariant, LABEL)
            // 顶上那条「正在录」：titleSmall + bodySmall 都在 primaryContainer 上
            check("$name onPrimaryContainer 在 primaryContainer", cs.onPrimaryContainer, cs.primaryContainer, BODY)
            check("$name onErrorContainer 在 errorContainer", cs.onErrorContainer, cs.errorContainer, BODY)
            check("$name onSecondaryContainer 在 secondaryContainer", cs.onSecondaryContainer, cs.secondaryContainer, BODY)
            // 可点的文字（LinkButton、人名）
            check("$name 链接字 secondary 在 surface", cs.secondary, cs.surface, BODY)
            check("$name 链接字 secondary 在 background", cs.secondary, cs.background, BODY)
            check("$name 错误字 error 在 surface", cs.error, cs.surface, BODY)
        }
    }

    /** 实心按钮 / 悬浮话筒上的白字与图标。 */
    @Test fun `实心动作上的字过 3`() {
        for ((name, cs) in schemes()) {
            check("$name onPrimary 在 primary", cs.onPrimary, cs.primary, LABEL)
            check("$name onError 在 error", cs.onError, cs.error, LABEL)
            // 周带里「今天」的那个字、搜索结果里的高亮字
            check("$name primary 当文字用在 surface", cs.primary, cs.surface, LABEL)
        }
    }

    /**
     * 药丸与提示块。Pill 是 labelMedium（标签档 3:1），
     * 但 NoticeBox 用同一对色写 bodyMedium 的正文——所以按正文 4.5 来要求。
     */
    @Test fun `每种语气的底和字都过 4_5`() {
        for ((name, cs) in schemes()) {
            val dark = name == "暗色"
            for (t in Tone.entries) {
                val c = t.colors(dark, cs)
                check("$name Tone.$t", c.fg, c.bg, BODY)
            }
        }
    }

    /** 输入框描边是「这里能打字」唯一的线索，非文字组件也要 3:1。 */
    @Test fun `输入框描边过 3`() {
        for ((name, cs) in schemes()) {
            check("$name outline 在 surface", cs.outline, cs.surface, LABEL)
            check("$name outline 在 background", cs.outline, cs.background, LABEL)
        }
    }

    /** 层级还得在：次要文字不能亮到和主文字分不开，否则「疏密」就没了。 */
    @Test fun `次要文字仍比主文字淡`() {
        for ((name, cs) in schemes()) {
            val main = ratio(cs.onSurface, cs.surface)
            val muted = ratio(cs.onSurfaceVariant, cs.surface)
            assertTrue("$name：次要文字 %.1f 和主文字 %.1f 拉不开".format(muted, main), main - muted >= 3.0)
        }
    }
}
