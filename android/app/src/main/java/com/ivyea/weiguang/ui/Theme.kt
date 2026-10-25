package com.ivyea.weiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
}

// 玻璃卡片：半透明白 + 白色发丝边 + 很淡的紫色投影（不做真模糊，省性能）
fun Modifier.glass(radius: Dp = 22.dp, elevation: Dp = 10.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .shadow(elevation, shape, ambientColor = Color(0x33785AAA), spotColor = Color(0x22785AAA))
        .clip(shape)
        .background(Wg.glass)
        .border(1.dp, Wg.glassLine, shape)
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
