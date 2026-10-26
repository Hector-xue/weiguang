package com.ivyea.weiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 浅色梦境：薰衣草 / 蜜桃 / 天空蓝，暖橙是唯一强调色（与门户页、概念图同一套）
object Wg {
    val ink = Color(0xFF34324D)
    val ink2 = Color(0xFF75738F)
    val ink3 = Color(0xFFA6A4BB)
    val warm = Color(0xFFEE8F6A)
    val warm2 = Color(0xFFFFCF8A)
    val lav = Color(0xFF8F86E0)
    val bg = Color(0xFFF6F2F8)
    val sky1 = Color(0xFFDCD8FA)
    val sky2 = Color(0xFFFDE6D6)
    val sky3 = Color(0xFFD9E9FA)
    val glass = Color(0x8CFFFFFF)
    val glassLine = Color(0xD9FFFFFF)
    val line = Color(0x1A50468C)
    val ok = Color(0xFF6CC89A)
    val paper = Color(0xFFFBF8F5)
    val hair = Color(0x1A2B2940)

    // 标题用宋体（系统自带的思源宋体/Noto Serif CJK），正文用黑体；中文标题不压字距
    val serif = FontFamily.Serif
}

// 页面大标题：宋体、大、不压字距
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier) {
        Text(text, fontFamily = Wg.serif, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, lineHeight = 38.sp)
        if (!sub.isNullOrEmpty()) Text(sub, fontSize = 12.5.sp, color = Wg.ink3, modifier = Modifier.padding(top = 4.dp))
    }
}

// 小节标题：小号、浅色，前面一个强调色小点
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Wg.warm) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(text, fontSize = 12.5.sp, color = Wg.ink2, modifier = Modifier.padding(start = 8.dp))
    }
}

// 发丝线
@Composable
fun Hair(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.6.dp).background(Wg.hair))
}

// 玻璃卡片：半透明白 + 白色发丝边 + 很淡的投影（不做真模糊，省性能）。投影压得很低，靠留白和发丝边撑质感
// 上下边缘渐隐：滚动内容在这里慢慢淡出，不是一刀切
fun Modifier.fadeEdges(top: Dp, bottom: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val t = (top.toPx() / h).coerceIn(0f, 0.5f)
        val b = (bottom.toPx() / h).coerceIn(0f, 0.5f)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, t to Color.Black, 1f - b to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

// 右边缘渐隐：横向滑动的一排标签，露出一半的那个慢慢淡掉
fun Modifier.fadeEnd(width: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = size.width
        if (w <= 0f) return@drawWithContent
        val e = (width.toPx() / w).coerceIn(0f, 0.5f)
        drawRect(Brush.horizontalGradient(0f to Color.Black, 1f - e to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

fun Modifier.glass(radius: Dp = 22.dp, elevation: Dp = 10.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .shadow((elevation.value * 0.45f).dp, shape, ambientColor = Color(0x22785AAA), spotColor = Color(0x18785AAA))
        .clip(shape)
        .background(Wg.glass)
        .border(0.8.dp, Wg.glassLine, shape)
}

// 背景：纸白底 + 两团跟着封面颜色的柔光，慢慢漂；这是「微光」的光
@Composable
fun Backdrop(m: Mood, time: State<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val t = time.value
        drawRect(Wg.paper)
        drawRect(Brush.verticalGradient(listOf(m.a.copy(alpha = 0.55f), Color.Transparent, m.c.copy(alpha = 0.6f))))
        val w = size.width; val h = size.height
        val g1 = Offset(w * (0.18f + 0.05f * sin(t * 0.13f)), h * (0.12f + 0.03f * cos(t * 0.11f)))
        drawCircle(Brush.radialGradient(listOf(m.accent.copy(alpha = 0.20f), m.accent.copy(alpha = 0.06f), Color.Transparent), g1, w * 0.85f), w * 0.85f, g1)
        val g2 = Offset(w * (0.92f - 0.05f * cos(t * 0.09f)), h * (0.62f + 0.04f * sin(t * 0.1f)))
        drawCircle(Brush.radialGradient(listOf(Wg.lav.copy(alpha = 0.16f), m.b.copy(alpha = 0.25f), Color.Transparent), g2, w * 0.9f), w * 0.9f, g2)
    }
}

@Composable
fun WeiguangTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Wg.warm,
            onPrimary = Color.White,
            secondary = Wg.lav,
            background = Wg.bg,
            surface = Wg.bg,
            onBackground = Wg.ink,
            onSurface = Wg.ink,
        ),
        content = content,
    )
}
