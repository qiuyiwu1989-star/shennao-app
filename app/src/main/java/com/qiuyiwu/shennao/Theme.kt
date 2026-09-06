package com.qiuyiwu.shennao

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 深脑主题 —— 把设计系统的 token 映到 Material 3 的角色上。
 *
 * 为什么是「映射」而不是「照搬」：Android 用户对 Material 有肌肉记忆，
 * 把 Web 的控件形状搬过来只会让 App 显得不像 Android。
 * **所以带过来的是品牌（颜色、字阶、语气），交还给平台的是形制（控件、导航、返回、触感）。**
 *
 * 屏幕里**不要写死颜色**，写死了就绕开了这一层。要用语义色（好/坏/警告/信息）走 [Tone]。
 *
 * 值来自 packages/ui/src/tokens.ts。那边改了这边要跟 —— 两端各存一份是已知代价，
 * Kotlin 读不到 TS。跟的时候只改这一个文件。
 */

// ── ink：中性色 ────────────────────────────────────────────────
private val Ink50  = Color(0xFFF6F7F9)
private val Ink100 = Color(0xFFE8EAEF)
private val Ink200 = Color(0xFFD3D7E0)
// **控件边界档。** 从 #9096A4 压到 #898F9D（深 7/255，肉眼看不出）——
// 输入框的边框是唯一告诉人「这里能打字」的东西，糊掉就没有别的线索了。
// 对白对比度 2.96 → 3.24，过 1.4.11 的 3:1。与 packages/ui/src/tokens.ts 2026-09 同步。
private val Ink300 = Color(0xFF898F9D)
private val Ink400 = Color(0xFF646B7D)
private val Ink600 = Color(0xFF3F4654)
private val Ink800 = Color(0xFF1C1C1E)
private val Ink900 = Color(0xFF0D1117)

// ── 品牌与语义 ─────────────────────────────────────────────────
private val Focus       = Color(0xFF0052D9)
private val FocusSoft   = Color(0xFFE8F0FF)
private val Iris        = Color(0xFF6725FF)
private val RiskSolid   = Color(0xFFDC2626)
private val RiskSurface = Color(0xFFFEF2F2)
private val RiskText    = Color(0xFFB91C1C)

/*
 * 暗色的表面阶梯。**不是把浅色反过来，是另取一套**：
 *
 *   底 NightGround → 卡 NightCard → 卡里的嵌块 NightInset → 控件 NightControl
 *
 * 每一级比上一级亮一点，层级靠底色而不靠描边——深底上一圈 1px 的灰线
 * 画出来的是「空心框」，2026-09-06 真机截图里满屏都是这种框，这是「粗糙」的第一来源。
 * 色相往蓝偏一点（不是纯灰），和品牌蓝是一家的。
 */
// 每级之间至少差 12/255：第一版差 8/255，模拟器截图里卡片直接消失在底色里（2026-09-06）。
private val NightGround  = Color(0xFF0F1115)
private val NightCard    = Color(0xFF1C2029)
private val NightInset   = Color(0xFF272C36)
private val NightControl = Color(0xFF2F3541)
private val NightLine    = Color(0xFF3C4351)
/** 深底上的实心蓝：白字在它上面 4.1:1，够图标和按钮字。 */
private val NightFocus     = Color(0xFF3D7BF0)
/** 深底上的可点文字蓝：在 NightGround 上 8:1。实心和文字用两档是常规做法，一档兼顾不了。 */
private val NightLink      = Color(0xFF8AB4F8)
private val NightFocusSoft = Color(0xFF1B2A47)

private val LightColors = lightColorScheme(
    /*
     * 主色 = 品牌蓝。以前是 ink-900（跟网页），但手机上只有一个实心按钮——
     * 「开始录」和右下角的话筒——它们该是同一个品牌色，黑的实心圆在一屏灰白里像个洞。
     * 蓝只花在这一处实心动作上；其余地方蓝只出现在「可点的文字」里。
     */
    primary            = Focus,
    onPrimary          = Color.White,
    primaryContainer   = FocusSoft,
    onPrimaryContainer = Focus,
    secondary          = Focus,       // 「可点的文字 / 选中」
    onSecondary        = Color.White,
    secondaryContainer = FocusSoft,
    onSecondaryContainer = Focus,
    surfaceVariant     = Ink100,      // 未选中的 chip、骨架条
    surfaceContainer   = Ink50,       // 卡片里的嵌块、输入框底
    surfaceContainerLow = Ink50,
    surfaceContainerHigh = Ink100,
    surfaceContainerHighest = Ink100,
    tertiary           = Iris,
    background         = Ink50,
    onBackground       = Ink800,
    surface            = Color.White,
    onSurface          = Ink800,
    onSurfaceVariant   = Ink400,      // 次要文字。全 App 用得最多的一个角色
    outline            = Ink200,      // 输入框描边
    outlineVariant     = Ink100,      // 分隔线、卡片发丝边
    error              = RiskSolid,
    onError            = Color.White,
    errorContainer     = RiskSurface,
    onErrorContainer   = RiskText,
)

/**
 * 暗色。**Web 端刻意不做暗色，移动端不能不做** —— 系统里有那个开关，
 * 用户切了之后期待 App 跟着变，不跟就是 App 的问题，不是用户的。
 */
private val DarkColors = darkColorScheme(
    primary            = NightFocus,
    onPrimary          = Color.White,
    primaryContainer   = NightFocusSoft,
    onPrimaryContainer = Color(0xFFC7DBFF),
    secondary          = NightLink,
    onSecondary        = Ink900,
    secondaryContainer = NightFocusSoft,
    onSecondaryContainer = Color(0xFFC7DBFF),
    surfaceVariant     = NightControl,
    surfaceContainer   = NightInset,
    surfaceContainerLow = NightInset,
    surfaceContainerHigh = NightControl,
    surfaceContainerHighest = NightControl,
    tertiary           = Color(0xFFA78BFA),
    background         = NightGround,
    onBackground       = Ink100,
    surface            = NightCard,
    onSurface          = Ink100,
    onSurfaceVariant   = Ink300,
    outline            = NightLine,
    outlineVariant     = NightInset,
    error              = Color(0xFFF28B8B),
    onError            = Ink900,
    errorContainer     = Color(0xFF3B1D1F),
    onErrorContainer   = Color(0xFFFFD4D4),
)

/*
 * 字阶。单位是 sp 不是 px——sp 跟随系统字号设置，用户调大字体时界面要跟着变。
 * 下限 11sp：10sp 的中文在手机上不可读。
 *
 * 每一档都写清楚**什么时候用它**。选档不是审美问题，是「这段文字是什么」。
 * 2026-09-01 审计发现的病是用错：屏幕上最多的文字是最小号的。
 */
private val ShennaoTypography = Typography(
    /** 巨号。一屏最多一个，且它必须是那一屏的全部意义——目前只有录音时长。 */
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    /** 页面标题。每屏一个。 */
    headlineSmall  = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    /** 区块标题。频道名、分区名。 */
    titleLarge     = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    /** 卡片主行：人名、会议名、判断的第一行。 */
    titleMedium    = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    /** 卡片次行标题、列表行的标题。 */
    titleSmall     = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),

    /** **正文默认档。** 判断、原话、摘要——凡是「要读的内容」都用它。 */
    bodyLarge      = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    /** 次要正文。「读了也行、不读也不影响」的补充说明。 */
    bodyMedium     = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    /** **元信息专用。** 时间、来源、计数、状态。一屏之内不该多于 bodyLarge。 */
    bodySmall      = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),

    /** 按钮字。 */
    labelLarge     = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    /** 分区小标、药丸、标签。 */
    labelMedium    = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    /** **下限**，不再往下。 */
    labelSmall     = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ShennaoTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = ShennaoTypography,
            content = content,
        )
    }
}

/** 现在是不是暗色。DsCard 靠它决定「发丝边还是底色抬一级」。 */
val LocalDark = androidx.compose.runtime.staticCompositionLocalOf { false }

/*
 * 下面几组是**同一批色值的具名出口**。
 *
 * 上面的 private val 给 colorScheme 用；但有些地方拿不到 colorScheme——
 * Canvas 画的声波、以及那条比对 tokens.ts 的护栏测试，都要按名字取 hex。
 * 值逐字复用上面的常量，不重新写一遍——重新写就是两处真源。
 */
object Ink {
    val c50 = Ink50; val c100 = Ink100; val c200 = Ink200; val c300 = Ink300
    val c400 = Ink400; val c500 = Color(0xFF545B6B); val c600 = Ink600
    val c700 = Color(0xFF2A2F3A); val c800 = Ink800; val c900 = Ink900
}

object Brand {
    val focus = Focus
    val focusBright = Color(0xFF007AFF)
    val focusSoft = FocusSoft
    val iris = Iris
}

/** 语义色（浅色那套，逐字对网页）。屏幕里不要直接用它，用 [Tone]——那才分明暗。 */
object StateColor {
    val okText = Color(0xFF047857);   val okSurface = Color(0xFFECFDF5);   val okBorder = Color(0xFFA7F3D0)
    val warnText = Color(0xFFB45309); val warnSurface = Color(0xFFFFFBEB); val warnBorder = Color(0xFFFDE68A)
    val riskText = RiskText;          val riskSurface = RiskSurface;       val riskBorder = Color(0xFFFECACA)
    val infoText = Color(0xFF1D4ED8); val infoSurface = Color(0xFFEFF6FF); val infoBorder = Color(0xFFBFDBFE)
}

/**
 * 语义色，表达「发生了什么」：好 / 警告 / 坏 / 信息 / 中性。
 *
 * 一对色：底 + 字。药丸、提示条、状态块都从这里取，**不自己配**——
 * 以前 StateColor 只有浅色一套，暗色下拿浅黄底配深棕字，一块亮斑贴在黑底上。
 */
enum class Tone { NEUTRAL, OK, WARN, RISK, INFO, ACCENT }

data class ToneColors(val bg: Color, val fg: Color)

@Composable
fun Tone.colors(): ToneColors {
    val dark = LocalDark.current
    val cs = MaterialTheme.colorScheme
    return when (this) {
        Tone.NEUTRAL -> ToneColors(cs.surfaceVariant, cs.onSurfaceVariant)
        Tone.ACCENT  -> ToneColors(cs.secondaryContainer, cs.onSecondaryContainer)
        Tone.OK      -> if (dark) ToneColors(Color(0xFF13291F), Color(0xFF6EE7B7)) else ToneColors(StateColor.okSurface, StateColor.okText)
        Tone.WARN    -> if (dark) ToneColors(Color(0xFF2E2410), Color(0xFFFCD34D)) else ToneColors(StateColor.warnSurface, StateColor.warnText)
        Tone.RISK    -> ToneColors(cs.errorContainer, cs.onErrorContainer)
        Tone.INFO    -> if (dark) ToneColors(NightFocusSoft, NightLink) else ToneColors(StateColor.infoSurface, StateColor.infoText)
    }
}
