package com.ivyea.weiguang.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class SpectrumView(ctx: Context) : View(ctx) {
    private var bands = FloatArray(32)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEE8F6A.toInt() }
    private val bg = Paint().apply { color = 0xFFF3EEF8.toInt() }

    fun set(b: FloatArray) { bands = b; invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val n = bands.size
        val w = width / n.toFloat()
        for (i in 0 until n) {
            val h = bands[i].coerceIn(0f, 1f) * height
            c.drawRoundRect(i * w + 2, height - h, (i + 1) * w - 2, height.toFloat(), 4f, 4f, bar)
        }
    }
}
