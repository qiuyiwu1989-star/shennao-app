package com.qiuyiwu.shennao

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * 设计系统在安卓这一侧的落地。真源是 docs/DESIGN-SYSTEM.md。
 *
 * 之前手机端「看着不精致」，根子上缺的是两样网页有而它没有的东西：
 *
 *   一、**表面阶梯**。网页是「页面底 ink-50 / 卡片 white + 1px 发丝边」，
 *       层级先靠底色、再靠边框，几乎不靠阴影。安卓这边整页白、卡片也白，
 *       于是所有东西糊在一个平面上——再怎么调间距也救不回来。
 *   二、**纵向节奏**。文档定了四档（页面 96 / 区块 64 / 块内 32 / 元素 16），
 *       而手机端一直是每屏随手写的 10/14/20。节奏不统一，眼睛会觉得「乱」，
 *       但说不出哪里乱。
 *
 * 手机屏窄，四档按 2/3 缩：64 / 40 / 20 / 12。比例保留，绝对值收窄。
 */
object DS {
    /** 纵向节奏。不要再在别处写字面量 dp。 */
    object Rhythm {
        val page = 64.dp      // 页面级留白
        val block = 40.dp     // 区块之间
        val inner = 20.dp     // 块内
        val element = 12.dp   // 元素之间
        val tight = 6.dp      // 图标与文字
    }

    /** 圆角。控件 12 / 卡片 16 / 浮层 20 / 药丸。中间档一律不用。 */
    object Radius {
        val control = RoundedCornerShape(12.dp)
        val card = RoundedCornerShape(16.dp)
        val sheet = RoundedCornerShape(20.dp)
        val pill = RoundedCornerShape(980.dp)
    }

    /** 卡片内边距：紧凑 / 默认 / 重点。 */
    object Pad {
        val tight = PaddingValues(16.dp)
        val default = PaddingValues(20.dp)
        val focus = PaddingValues(24.dp)
        val screen = PaddingValues(horizontal = 16.dp)
    }

    /** 动效时长。产品页面不用回弹。 */
    object Motion {
        const val fast = 150      // 颜色、透明度
        const val base = 250      // 位移、展开
        const val slow = 400      // 浮层进出
    }
}

/**
 * 卡片。**白底 + 1px 发丝边，不用阴影。**
 *
 * Material3 默认给卡片一个抬升阴影，那是 Material 的语言，不是深脑的：
 * 文档写的是「层级不只靠阴影，先靠底色」。一屏十几张带阴影的卡片叠在一起，
 * 看起来会像一堆浮在半空的纸片，而这一屏要传达的是「这些判断是稳的」。
 */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = DS.Radius.card,
             colors = colors, border = border, elevation = elevation) { content() }
    } else {
        Card(modifier = modifier, shape = DS.Radius.card,
             colors = colors, border = border, elevation = elevation) { content() }
    }
}
