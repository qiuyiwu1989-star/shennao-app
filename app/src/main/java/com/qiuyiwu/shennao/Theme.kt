package com.qiuyiwu.shennao

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
 * 接法也是有意选的：App 里 43 处已经在用 MaterialTheme.colorScheme.* 和 .typography.*，
 * 只要在根上换成 ShennaoTheme，那 43 处自动拿到深脑的值 —— 屏幕代码一行不用改。
 * 反过来说，**以后也不要在屏幕里写死颜色**，写死了就绕开了这一层。
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

private val LightColors = lightColorScheme(
    primary            = Ink900,      // 主按钮定的是 ink-900：克制，且不与蓝色焦点环糊在一起
    onPrimary          = Color.White,
    primaryContainer   = FocusSoft,
    onPrimaryContainer = Focus,
    secondary          = Focus,       // 蓝留给「可点的文字 / 选中 / 焦点」
    onSecondary        = Color.White,
    // 没设这几项时 M3 用默认的淡紫：底栏、选中的 chip、录音页底部说明全是那种紫——
    // 和 ink 黑主色、蓝焦点色完全不是一家（2026-09-06 截图审出来的）。
    secondaryContainer = FocusSoft,
    onSecondaryContainer = Focus,
    surfaceVariant     = Ink100,
    surfaceContainer   = Ink50,
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
    outlineVariant     = Ink100,      // 分隔线
    error              = RiskSolid,
    onError            = Color.White,
    errorContainer     = RiskSurface,
    onErrorContainer   = RiskText,
)

/**
 * 暗色。**Web 端刻意不做暗色，移动端不能不做** —— 系统里有那个开关，
 * 用户切了之后期待 App 跟着变，不跟就是 App 的问题，不是用户的。
 *
 * 这一套不是把浅色反过来，是从 ink 色阶里另取：底用 ink-900，
 * 面用 ink-800 抬一档，蓝色提亮到能在深底上读出来（#0052D9 在 ink-900 上只有 2.1:1）。
 */
private val DarkColors = darkColorScheme(
    primary            = Color(0xFF7BA7F0),   // 深底上 ink-900 当主色是看不见的，改用提亮的蓝
    onPrimary          = Ink900,
    primaryContainer   = Color(0xFF16233A),
    onPrimaryContainer = Color(0xFFCFE0FF),
    secondary          = Color(0xFF7BA7F0),
    onSecondary        = Ink900,
    secondaryContainer = Color(0xFF16233A),
    onSecondaryContainer = Color(0xFFCFE0FF),
    surfaceVariant     = Color(0xFF2C2C33),
    surfaceContainer   = Ink800,
    surfaceContainerLow = Ink800,
    surfaceContainerHigh = Color(0xFF2C2C33),
    surfaceContainerHighest = Color(0xFF2C2C33),
    tertiary           = Color(0xFFA78BFA),
    background         = Ink900,
    onBackground       = Ink100,
    surface            = Ink800,
    onSurface          = Ink100,
    onSurfaceVariant   = Ink300,
    outline            = Color(0xFF3A3A43),
    outlineVariant     = Color(0xFF2C2C33),
    error              = Color(0xFFEF7C7C),
    onError            = Ink900,
    errorContainer     = Color(0xFF3A1C1C),
    onErrorContainer   = Color(0xFFFFD9D9),
)

/**
 * 字阶。对齐设计系统的具名档，但**单位是 sp 不是 px** ——
 * sp 跟随系统字号设置，用户调大字体时界面要跟着变，这是无障碍要求不是可选项。
 *
 * 下限 11sp：10sp 的中文在手机上不可读（§2.2 定的下限，两端同一条）。
 */
/*
 * 字阶。数值本来就定得没问题——**2026-09-01 审计发现问题在用错了**：
 * bodySmall 用了 41 次、bodyMedium 27 次，也就是「屏幕上最多的文字是最小号的」。
 * 正文、说明、元信息全挤进小字，眼睛找不到重点，这是「粗糙」最直接的来源。
 *
 * 所以每一档都写清楚**什么时候用它**。选档不是审美问题，是「这段文字是什么」。
 */
private val ShennaoTypography = Typography(
    /** 巨号。一屏最多一个，且它必须是那一屏的全部意义——目前只有录音时长。 */
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    /** 页面标题。每屏一个。 */
    headlineSmall  = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    /** 区块标题。频道名、分区名。 */
    titleLarge     = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    /** 卡片主行：人名、会议名、判断的第一行。 */
    titleMedium    = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    /** 卡片次行标题。用得应该比 titleMedium 少。 */
    titleSmall     = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),

    /**
     * **正文默认档。** 判断、原话、摘要、说明——凡是「要读的内容」都用它。
     *
     * 审计前这些大多错用了 bodyMedium（14sp）甚至 bodySmall（13sp）。
     * 判据：**如果这段话是用户要读进去的，就用 bodyLarge。**
     */
    bodyLarge      = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    /** 次要正文。只给「读了也行、不读也不影响」的补充说明。 */
    bodyMedium     = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    /**
     * **元信息专用。** 时间、来源、计数、状态。
     *
     * 判据：**一屏之内 bodySmall 不该多于 bodyLarge。**
     * 多了就说明有正文被降级成了元信息——那正是审计抓到的病。
     */
    bodySmall      = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),

    labelLarge     = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    /** 按钮与标签。 */
    labelMedium    = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    /** 药丸内文字。**下限**，不再往下——10sp 的中文在手机上读不出来。 */
    labelSmall     = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ShennaoTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ShennaoTypography,
        content = content,
    )
}

/*
 * 下面三组是**同一批色值的具名出口**。
 *
 * 上面的 private val 给 colorScheme 用；但有些地方拿不到 colorScheme——
 * Canvas 画的声波、图表、以及那条比对 tokens.ts 的护栏测试，都要按名字取 hex。
 * 这正是设计系统 §2.0「只出 class 不出 hex，图表就只能自己硬编码一份」
 * 在安卓侧的同一个形状。
 *
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

/** 语义色。表达「发生了什么」。 */
object StateColor {
    val okText = Color(0xFF047857);   val okSurface = Color(0xFFECFDF5);   val okBorder = Color(0xFFA7F3D0)
    val warnText = Color(0xFFB45309); val warnSurface = Color(0xFFFFFBEB); val warnBorder = Color(0xFFFDE68A)
    val riskText = RiskText;          val riskSurface = RiskSurface;       val riskBorder = Color(0xFFFECACA)
    val infoText = Color(0xFF1D4ED8); val infoSurface = Color(0xFFEFF6FF); val infoBorder = Color(0xFFBFDBFE)
}
