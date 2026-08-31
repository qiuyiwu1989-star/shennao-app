package com.qiuyiwu.shennao

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * 深脑设计 token 在安卓这一侧的镜像。
 *
 * 真源是 packages/ui/src/tokens.ts，**不是这里**。这里的每一个色值都必须
 * 和那份文件逐字相同，有测试钉着（ThemeParityTest 直接读 tokens.ts 比对）。
 *
 * 为什么要镜像而不是运行时去取：安卓拿不到 Tailwind class，和图表组件是同一个
 * 处境——那正是仓里「只出 class 不出 hex，图表就只能自己硬编码一份」那条教训。
 * 镜像可以，但漂移不行，所以配一条护栏。
 */

object Ink {
    val c50 = Color(0xFFF6F7F9)
    val c100 = Color(0xFFE8EAEF)
    val c200 = Color(0xFFD3D7E0)
    val c300 = Color(0xFF9096A4)
    val c400 = Color(0xFF646B7D)
    val c500 = Color(0xFF545B6B)
    val c600 = Color(0xFF3F4654)
    val c700 = Color(0xFF2A2F3A)
    val c800 = Color(0xFF1C1C1E)
    val c900 = Color(0xFF0D1117)
}

object Brand {
    /** 链接与小字（白底 6.54:1） */
    val focus = Color(0xFF0052D9)
    /** 只用于焦点环、辉光与大字（4.02:1） */
    val focusBright = Color(0xFF007AFF)
    val focusSoft = Color(0xFFE8F0FF)
    val iris = Color(0xFF6725FF)
}

/** 语义色。表达「发生了什么」。取每组的 text / surface / border 三档。 */
object StateColor {
    val okText = Color(0xFF047857);   val okSurface = Color(0xFFECFDF5);   val okBorder = Color(0xFFA7F3D0)
    val warnText = Color(0xFFB45309); val warnSurface = Color(0xFFFFFBEB); val warnBorder = Color(0xFFFDE68A)
    val riskText = Color(0xFFB91C1C); val riskSurface = Color(0xFFFEF2F2); val riskBorder = Color(0xFFFECACA)
    val infoText = Color(0xFF1D4ED8); val infoSurface = Color(0xFFEFF6FF); val infoBorder = Color(0xFFBFDBFE)
}

private val LightColors = lightColorScheme(
    primary = Brand.focus,
    onPrimary = Color.White,
    primaryContainer = Brand.focusSoft,
    onPrimaryContainer = Brand.focus,
    secondary = Brand.iris,
    background = Color.White,
    onBackground = Ink.c900,
    surface = Color.White,
    onSurface = Ink.c900,
    surfaceVariant = Ink.c50,
    // 次要文字用 ink-500 而不是 ink-300：手机在阳光下看，
    // 更浅的灰在网页上够用，在户外就读不出来了。
    onSurfaceVariant = Ink.c500,
    outline = Ink.c200,
    outlineVariant = Ink.c100,
    error = StateColor.riskText,
    onError = Color.White,
    errorContainer = StateColor.riskSurface,
    onErrorContainer = StateColor.riskText,
)

private val DarkColors = darkColorScheme(
    primary = Brand.focusBright,
    onPrimary = Color.White,
    primaryContainer = Ink.c700,
    onPrimaryContainer = Brand.focusSoft,
    secondary = Brand.iris,
    background = Ink.c900,
    onBackground = Ink.c50,
    surface = Ink.c800,
    onSurface = Ink.c50,
    surfaceVariant = Ink.c700,
    onSurfaceVariant = Ink.c200,
    outline = Ink.c600,
    outlineVariant = Ink.c700,
    error = Color(0xFFFCA5A5),
    onError = Ink.c900,
)

/**
 * 字阶。下限 11sp——中文在移动端小于这个就读不出来了，
 * 网页那边的设计规范里是同一条（tinyFontSize 基线为 0）。
 */
private val Type = Typography(
    headlineSmall = Typography().headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = Typography().titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 15.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 12.sp),
    labelMedium = Typography().labelMedium.copy(fontSize = 12.sp),
    labelSmall = Typography().labelSmall.copy(fontSize = 11.sp),
)

@Composable
fun ShennaoTheme(dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Type, content = content)
}
