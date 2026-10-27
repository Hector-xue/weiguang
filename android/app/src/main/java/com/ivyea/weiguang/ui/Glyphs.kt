package com.ivyea.weiguang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

enum class G { Play, Pause, Next, Prev, Heart, HeartFill, More, Spark, Disc, Stack, Back }

// 自绘的几个控制图标（Material 核心图标里没有暂停/切歌）
@Composable
fun Glyph(kind: G, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        when (kind) {
            G.Play -> drawPath(Path().apply {
                moveTo(w * 0.28f, h * 0.18f); lineTo(w * 0.84f, h * 0.5f); lineTo(w * 0.28f, h * 0.82f); close()
            }, color)
            // 底栏三枚：此刻=一颗四角星光，播放=一张唱片，曲库=三本立着的书
            G.Spark -> drawPath(Path().apply {
                val c = w * 0.5f; val r = w * 0.4f; val k = w * 0.07f
                moveTo(c, c - r); quadraticTo(c + k, c - k, c + r, c); quadraticTo(c + k, c + k, c, c + r)
                quadraticTo(c - k, c + k, c - r, c); quadraticTo(c - k, c - k, c, c - r); close()
            }, color, style = Stroke(w * 0.075f, join = StrokeJoin.Round))
            G.Disc -> {
                drawCircle(color, w * 0.36f, Offset(w * 0.5f, h * 0.5f), style = Stroke(w * 0.075f))
                drawCircle(color, w * 0.09f, Offset(w * 0.5f, h * 0.5f))
            }
            G.Stack -> {
                val sw = Stroke(w * 0.075f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.24f, h * 0.2f), Offset(w * 0.24f, h * 0.8f), sw.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.44f, h * 0.28f), Offset(w * 0.44f, h * 0.8f), sw.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.24f), Offset(w * 0.8f, h * 0.78f), sw.width, StrokeCap.Round)
            }
            G.Back -> drawPath(Path().apply { moveTo(w * 0.6f, h * 0.22f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.6f, h * 0.78f) },
                color, style = Stroke(w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            G.More -> for (i in 0 until 3) drawCircle(color, w * 0.075f, Offset(w * (0.25f + i * 0.25f), h * 0.5f))
            G.Pause -> {
                val bw = w * 0.2f; val cr = CornerRadius(bw * 0.35f)
                drawRoundRect(color, Offset(w * 0.24f, h * 0.18f), Size(bw, h * 0.64f), cr)
                drawRoundRect(color, Offset(w * 0.56f, h * 0.18f), Size(bw, h * 0.64f), cr)
            }
            G.Next, G.Prev -> {
                val flip = kind == G.Prev
                fun x(v: Float) = if (flip) w - v else v
                drawPath(Path().apply {
                    moveTo(x(w * 0.2f), h * 0.2f); lineTo(x(w * 0.68f), h * 0.5f); lineTo(x(w * 0.2f), h * 0.8f); close()
                }, color)
                drawRoundRect(color, Offset(if (flip) w * 0.18f else w * 0.72f, h * 0.2f), Size(w * 0.1f, h * 0.6f), CornerRadius(w * 0.04f))
            }
            G.Heart, G.HeartFill -> drawPath(Path().apply {
                moveTo(w * 0.5f, h * 0.84f)
                cubicTo(w * 0.1f, h * 0.58f, w * 0.06f, h * 0.3f, w * 0.28f, h * 0.2f)
                cubicTo(w * 0.4f, h * 0.15f, w * 0.48f, h * 0.24f, w * 0.5f, h * 0.32f)
                cubicTo(w * 0.52f, h * 0.24f, w * 0.6f, h * 0.15f, w * 0.72f, h * 0.2f)
                cubicTo(w * 0.94f, h * 0.3f, w * 0.9f, h * 0.58f, w * 0.5f, h * 0.84f)
                close()
            }, color, style = if (kind == G.HeartFill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
