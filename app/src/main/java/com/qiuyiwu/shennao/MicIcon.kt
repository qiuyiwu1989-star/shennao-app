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
