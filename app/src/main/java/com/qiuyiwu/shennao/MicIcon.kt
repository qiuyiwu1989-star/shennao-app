package com.qiuyiwu.shennao

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 麦克风图标，手画的。
 *
 * 为一个图标引 material-icons-extended 会让安装包从 9.9 MB 涨到 14 MB——
 * 那一整套上千个矢量图全打进包里，而我们只用四个（其余三个 Home/Person/Send
 * 在 material3 自带的 core 集里，只有 Mic 不在）。
 * 用户装的是一个录音应用，不该为一套没人看的图标多下 4 MB。
 *
 * 形状对齐 lucide 的 mic（网页那边用的就是 lucide）。
 */
val MicOutlined: ImageVector by lazy {
    ImageVector.Builder(
        name = "MicOutlined",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        val stroke = SolidColor(Color.Black)
        // 话筒头：一个圆角胶囊
        path(stroke = stroke, strokeLineWidth = 2f,
             strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            lineTo(9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            lineTo(15f, 5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            close()
        }
        // 下方的托架
        path(stroke = stroke, strokeLineWidth = 2f,
             strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19f, 10f)
            lineTo(19f, 12f)
            curveTo(19f, 15.87f, 15.87f, 19f, 12f, 19f)
            curveTo(8.13f, 19f, 5f, 15.87f, 5f, 12f)
            lineTo(5f, 10f)
        }
        // 支杆
        path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 19f); lineTo(12f, 22f)
        }
    }.build()
}

/** 「问」栏的图标：带问号的气泡。以前用的是纸飞机（Send），说的是「发送」不是「问」。 */
val AskBubble: ImageVector by lazy {
    ImageVector.Builder(
        name = "AskBubble",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        val stroke = SolidColor(Color.Black)
        // 气泡：圆加一个尾巴
        path(stroke = stroke, strokeLineWidth = 2f,
             strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7.9f, 20f)
            curveTo(9.2f, 20.66f, 10.6f, 21f, 12f, 21f)
            curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
            curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
            curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
            curveTo(3f, 13.4f, 3.34f, 14.8f, 4f, 16.1f)
            lineTo(2f, 22f)
            close()
        }
        // 问号
        path(stroke = stroke, strokeLineWidth = 2f,
             strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(9.09f, 9f)
            curveTo(9.33f, 8.33f, 9.79f, 7.77f, 10.4f, 7.41f)
            curveTo(11f, 7.05f, 11.7f, 6.92f, 12.39f, 7.04f)
            curveTo(13.08f, 7.16f, 13.7f, 7.51f, 14.15f, 8.04f)
            curveTo(14.6f, 8.57f, 14.85f, 9.25f, 14.84f, 9.94f)
            curveTo(14.84f, 12f, 11.84f, 13f, 11.84f, 13f)
        }
        path(stroke = stroke, strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 17f); lineTo(12.01f, 17f)
        }
    }.build()
}
