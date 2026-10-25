package com.ivyea.weiguang.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 整页颜色跟着封面走：取封面的主色，冲淡成浅色底 + 一个够深的强调色
data class Mood(val a: Color, val b: Color, val c: Color, val accent: Color)

val DefaultMood = Mood(Wg.sky1, Wg.sky2, Wg.sky3, Wg.warm)

suspend fun moodOf(bmp: Bitmap): Mood = withContext(Dispatchers.Default) {
    try {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        fun c(rgb: Int?) = rgb?.let { Color(it) }
        val vib = c(p.vibrantSwatch?.rgb) ?: c(p.dominantSwatch?.rgb) ?: Wg.warm
        val mut = c(p.mutedSwatch?.rgb) ?: c(p.lightMutedSwatch?.rgb) ?: Wg.lav
        val light = c(p.lightVibrantSwatch?.rgb) ?: vib
        Mood(lerp(mut, Color.White, 0.80f), lerp(light, Color.White, 0.82f), lerp(vib, Color.White, 0.86f), accentOf(vib))
    } catch (_: Throwable) {
        DefaultMood
    }
}

// 强调色要压得住浅底：饱和度不低于 0.35，亮度不高于 0.82
private fun accentOf(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.35f)
    hsv[2] = hsv[2].coerceIn(0.45f, 0.82f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
