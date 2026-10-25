package com.ivyea.weiguang.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.ivyea.weiguang.core.NowPlaying
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// 悬浮光球：在放歌时呼吸、外圈光点慢慢转；暂停时静止不耗电
class OrbView(ctx: Context) : View(ctx) {
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val start = System.nanoTime()

    override fun onDraw(c: Canvas) {
        val playing = NowPlaying.now.value?.playing == true
        val t = (System.nanoTime() - start) / 1e9f
        val cx = width / 2f; val cy = height / 2f
        val breath = if (playing) 0.5f + 0.5f * sin(t * 2.2f) else 0.3f
        val r = width * (0.30f + 0.03f * breath)

        glow.shader = RadialGradient(cx, cy, width / 2f,
            intArrayOf(Color.argb((110 + 80 * breath).toInt(), 255, 190, 140), Color.argb(0, 200, 180, 245)),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, width / 2f, glow)

        core.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.3f,
            intArrayOf(Color.rgb(255, 246, 216), Color.rgb(255, 191, 128), Color.rgb(201, 182, 240)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, core)

        if (playing) {
            for (i in 0 until 6) {
                val a = t * 0.9f + i * (2 * PI / 6).toFloat()
                val rr = r * 1.35f
                dot.alpha = if (sin(a) > 0) 230 else 110
                c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr * 0.55f, width * 0.025f, dot)
            }
            postInvalidateOnAnimation()
        } else {
            // 暂停时每秒看一眼状态就够
            postInvalidateDelayed(1000)
        }
    }
}
