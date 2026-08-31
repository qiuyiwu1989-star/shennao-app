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
private val Ink300 = Color(0xFF9096A4)
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
private val ShennaoTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall  = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    titleLarge     = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium    = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleSmall     = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge      = TextStyle(fontSize = 15.sp, lineHeight = 26.sp),   // 长文正文，行高 1.7
    bodyMedium     = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),   // 界面默认
    bodySmall      = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),   // 元信息
    labelLarge     = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium    = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
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
