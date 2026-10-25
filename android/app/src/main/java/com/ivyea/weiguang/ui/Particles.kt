package com.ivyea.weiguang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// 每帧时间（秒）。只在绘制阶段读，不触发重组
@Composable
fun rememberFrameTime(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1e9f }
    }
    return t
}

// 没有真实声音时的"呼吸"频谱，保证粒子一直是活的
fun idleBand(i: Int, n: Int, t: Float, playing: Boolean): Float {
    val x = i / n.toFloat()
    val beat = if (playing) abs(sin(t * 2.1f)).pow(6) else 0f
    val base = if (playing) 0.28f else 0.12f
    return (base + 0.16f * sin(x * 12 + t * (if (playing) 2.6f else 0.8f)) * cos(x * 5 - t * 1.3f) + 0.35f * exp(-x * 3) * beat)
        .coerceIn(0f, 1f)
}

// 背景漂浮微尘
@Composable
fun Dust(modifier: Modifier, time: State<Float>, count: Int = 40) {
    val ps = remember {
        List(count) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 1.6f + 0.5f, Random.nextFloat() * 0.012f + 0.004f, Random.nextFloat() * 6f, Random.nextFloat()) }
    }
    Canvas(modifier) {
        val t = time.value
        for (p in ps) {
            val y = ((p[1] - t * p[3]) % 1f + 1f) % 1f
            val x = p[0] * size.width + sin(t * 0.5f + p[4]) * 8f
            val a = 0.3f + 0.35f * sin(t * 1.3f + p[4])
            val c = when {
                p[5] < 0.3f -> Color(1f, 0.75f, 0.6f, a)
                p[5] < 0.55f -> Color(0.67f, 0.63f, 0.95f, a * 0.8f)
                else -> Color(1f, 1f, 1f, (a + 0.2f).coerceAtMost(1f))
            }
            drawCircle(c, p[2] * density, Offset(x, y * size.height))
        }
    }
}

private class Spark(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float)

// 封面外一圈点阵频谱：每个方向一串小点，长度跟频段强弱走，高能量处迸火花
@Composable
fun SpectrumRing(
    modifier: Modifier,
    bands: State<FloatArray>,
    live: () -> Boolean,
    playing: Boolean,
    accent: Color,
    time: State<Float>,
    innerRatio: Float = 0.36f,
) {
    val sparks = remember { ArrayList<Spark>() }
    val last = remember { floatArrayOf(0f) }
    Canvas(modifier) {
        val t = time.value
        val dt = (t - last[0]).coerceIn(0f, 0.05f); last[0] = t
        val b = bands.value
        val real = live() && playing
        val cx = size.width / 2; val cy = size.height / 2
        val r0 = size.minDimension * innerRatio
        val step = 4.6f * density

        // 底光
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.10f), Color.Transparent), Offset(cx, cy), r0 * 1.9f),
            r0 * 1.9f, Offset(cx, cy),
        )

        val n = 120
        val half = n / 2
        for (i in 0 until n) {
            val a = i / n.toFloat() * 2 * PI.toFloat() - PI.toFloat() / 2 + t * 0.04f
            // 左右对称：低频在顶上，往下两侧逐渐变高频
            val k = if (i < half) i else n - i
            val v = if (real) {
                val f = k / half.toFloat() * (b.size - 1)
                val i0 = f.toInt().coerceIn(0, b.size - 1); val i1 = (i0 + 1).coerceAtMost(b.size - 1)
                b[i0] + (b[i1] - b[i0]) * (f - i0)
            } else idleBand(k, half, t, playing)
            val len = 1.5f + v * 10f
            val dots = len.toInt().coerceAtLeast(1)
            val hue = i / n.toFloat()
            val col = lerp(accent, Wg.lav, abs(hue * 2 - 1))
            val ca = cos(a); val sa = sin(a)
            for (j in 0 until dots) {
                val rr = r0 + j * step
                val fade = 1f - j / (dots + 1f) * 0.8f
                drawCircle(col.copy(alpha = fade), (1.9f - j * 0.12f).coerceAtLeast(0.6f) * density, Offset(cx + ca * rr, cy + sa * rr))
            }
            if (v > 0.82f && playing && Random.nextFloat() < 0.07f && sparks.size < 80) {
                val rr = r0 + len * step
                sparks += Spark(cx + ca * rr, cy + sa * rr, ca * 46f * density, sa * 46f * density, 1f)
            }
        }
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.vx * dt; s.y += s.vy * dt; s.life -= dt * 1.1f
            if (s.life <= 0f) { it.remove(); continue }
            drawCircle(Color.White.copy(alpha = s.life), 1.6f * density, Offset(s.x, s.y))
        }
    }
}

// 粒子光球：斐波那契球面上的点，跟节拍呼吸（空状态、引导页用）
@Composable
fun ParticleOrb(modifier: Modifier, time: State<Float>, energy: () -> Float, warm: Color = Wg.warm) {
    val pts = remember {
        val n = 360
        List(n) { i ->
            val y = 1 - 2 * (i + 0.5f) / n
            val r = kotlin.math.sqrt(1 - y * y)
            val th = i * 2.39996f
            floatArrayOf(cos(th) * r, y, sin(th) * r, Random.nextFloat() * 6f)
        }
    }
    Canvas(modifier) {
        val t = time.value
        val e = energy()
        val cx = size.width / 2; val cy = size.height / 2
        val R = size.minDimension * 0.26f * (1f + 0.05f * e)
        drawCircle(Brush.radialGradient(listOf(Color(1f, 0.84f, 0.6f, 0.7f), warm.copy(alpha = 0.25f), Color.Transparent), Offset(cx, cy), R * 2.2f), R * 2.2f, Offset(cx, cy))
        for (k in 0 until 3) {
            val ph = (t * 0.3f + k / 3f) % 1f
            drawCircle(Color.White.copy(alpha = 0.55f * (1 - ph)), R * (1.15f + ph), Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f * density))
        }
        drawCircle(Brush.radialGradient(listOf(Color(1f, 0.98f, 0.9f, 0.95f), Color(1f, 0.75f, 0.5f, 0.5f), warm.copy(alpha = 0.06f)), Offset(cx - R * 0.25f, cy - R * 0.3f), R), R * 0.92f, Offset(cx, cy))
        val ry = t * 0.35f; val rx = 0.35f
        val c1 = cos(ry); val s1 = sin(ry); val c2 = cos(rx); val s2 = sin(rx)
        for (p in pts) {
            val x = p[0] * c1 + p[2] * s1
            var z = -p[0] * s1 + p[2] * c1
            val y = p[1] * c2 - z * s2
            z = p[1] * s2 + z * c2
            val d = 1 + 0.06f * sin(t * 3 + p[3]) * (0.4f + e)
            val dep = (z + 1) / 2
            drawCircle(Color(1f, 0.78f + dep * 0.2f, 0.62f + dep * 0.3f, 0.15f + dep * 0.75f), (0.5f + dep * 1.5f) * density, Offset(cx + x * R * d, cy + y * R * d))
        }
    }
}
