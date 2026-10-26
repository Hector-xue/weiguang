package com.ivyea.weiguang.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

// 分享卡片：歌词卡、本周回顾卡。纸白底 + 封面取色的柔光 + 宋体，和 App 里一个样子；直接用安卓画布画，不依赖界面
object Card {
    private const val W = 1080
    private const val H = 1440
    private val PAPER = 0xFFFBF8F4.toInt()
    private val INK = 0xFF2E2A3F.toInt()
    private val INK2 = 0xFF6E6982.toInt()
    private val INK3 = 0xFFA29DB3.toInt()
    private val WARM = 0xFFE8875F.toInt()

    private fun serif(bold: Boolean = false) = Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)

    private fun text(size: Float, color: Int, face: Typeface = Typeface.DEFAULT) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = face
    }

    private fun layout(s: String, p: TextPaint, width: Int, spacing: Float = 1.25f, maxLines: Int = 8): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()

    private fun draw(c: Canvas, l: StaticLayout, x: Float, y: Float): Float {
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return y + l.height
    }

    // 系统给的封面可能是硬件位图，软件画布画不了、也读不了像素，先拷一份
    private fun sw(b: Bitmap?): Bitmap? = try {
        if (b == null) null else if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
    } catch (_: Throwable) { null }

    // 封面里最亮眼的颜色当柔光；没有封面就用微光的暖橘和薰衣草
    private fun tones(art: Bitmap?): Pair<Int, Int> {
        if (art == null) return 0xFFF6C3A6.toInt() to 0xFFCFC8F2.toInt()
        val s = Bitmap.createScaledBitmap(art, 8, 8, true)
        var best = 0; var bestSat = -1f; var other = 0
        val hsv = FloatArray(3)
        for (y in 0 until 8) for (x in 0 until 8) {
            val px = s.getPixel(x, y)
            android.graphics.Color.colorToHSV(px, hsv)
            val score = hsv[1] * hsv[2]
            if (score > bestSat) { other = best; best = px; bestSat = score }
        }
        fun soft(px: Int): Int {
            android.graphics.Color.colorToHSV(px, hsv)
            hsv[1] = (hsv[1] * 0.55f).coerceAtMost(0.45f); hsv[2] = 0.97f
            return android.graphics.Color.HSVToColor(hsv)
        }
        return soft(best) to soft(if (other == 0) best else other)
    }

    private fun backdrop(c: Canvas, art: Bitmap?) {
        c.drawColor(PAPER)
        val (a, b) = tones(art)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(W * 0.85f, H * 0.12f, W * 0.9f, intArrayOf(a, a and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = RadialGradient(W * 0.1f, H * 0.92f, W * 0.8f, intArrayOf(b, b and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
    }

    private fun cover(c: Canvas, art: Bitmap?, x: Float, y: Float, size: Float) {
        val r = RectF(x, y, x + size, y + size)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (art == null) {
            p.shader = LinearGradient(x, y, x + size, y + size, 0xFFC8C2F2.toInt(), 0xFFFFE2A8.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, size * 0.16f, size * 0.16f, p)
            return
        }
        c.save()
        val path = android.graphics.Path().apply { addRoundRect(r, size * 0.16f, size * 0.16f, android.graphics.Path.Direction.CW) }
        c.clipPath(path)
        val side = minOf(art.width, art.height)
        val src = Rect((art.width - side) / 2, (art.height - side) / 2, (art.width + side) / 2, (art.height + side) / 2)
        c.drawBitmap(art, src, r, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
    }

    private fun footer(c: Canvas) {
        val p = text(30f, INK3)
        c.drawCircle(96f, H - 108f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WARM })
        c.drawText("微光 · 懂你此刻的听歌伙伴", 122f, H - 98f, p)
    }

    // 歌词卡：一句歌词，宋体大字；下面是歌名歌手和封面
    fun lyric(line: String, title: String, artist: String, cover0: Bitmap?): Bitmap {
        val art = sw(cover0)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        backdrop(c, art)
        val m = 96f
        // 一个大引号压在左上
        c.drawText("“", m - 10f, 330f, text(220f, WARM and 0x55FFFFFF, serif()))
        val size = when { line.length <= 10 -> 84f; line.length <= 20 -> 72f; else -> 60f }
        val l = layout(line, text(size, INK, serif(true)), (W - m * 2).toInt(), 1.45f, 7)
        val top = (H * 0.42f - l.height / 2f).coerceAtLeast(330f)
        draw(c, l, m, top)
        cover(c, art, m, H - 420f, 150f)
        draw(c, layout(Meta.short(title), text(44f, INK, serif(true)), W - 400, 1.1f, 1), m + 186f, H - 410f)
        draw(c, layout(artist, text(32f, INK2), W - 400, 1.1f, 1), m + 186f, H - 340f)
        footer(c)
        return bmp
    }

    // 回顾卡（本周 / 本月）：听歌人格 + 一句话 + 数字 + 最常听的三首
    fun week(r: Recap, cover0: Bitmap?): Bitmap {
        val art = sw(cover0)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        backdrop(c, art)
        val m = 96f
        val df = SimpleDateFormat("M.d", Locale.CHINA)
        c.drawText("${r.title}  ${df.format(r.from)} — ${df.format(r.to)}", m, 190f, text(34f, INK2))
        c.drawText("你是", m, 300f, text(40f, INK2, serif()))
        c.drawText(r.persona, m - 6f, 450f, text(150f, INK, serif(true)))
        var y = draw(c, layout(r.line, text(40f, INK2, serif()), (W - m * 2).toInt(), 1.4f, 3), m, 500f) + 70f

        // 三个数
        // 第二个数：一周看几首歌，一个月看有几天在听
        val second = if (r.to - r.from > 8L * 24 * 3600 * 1000) "${r.days}" to "天在听" else "${r.songs}" to "首歌"
        val nums = listOf("${r.plays}" to "次播放", second, (r.latest ?: "${r.artists}") to (if (r.latest != null) "最晚还在听" else "位歌手"))
        val colW = (W - m * 2) / 3f
        nums.forEachIndexed { i, (v, k) ->
            c.drawText(v, m + colW * i, y + 80f, text(76f, INK, serif(true)))
            c.drawText(k, m + colW * i, y + 130f, text(30f, INK3))
        }
        y += 200f
        c.drawRect(m, y, W - m, y + 2f, Paint().apply { color = 0x1A2E2A3F })
        y += 70f
        c.drawText("最常听", m, y, text(32f, WARM))
        y += 40f
        r.top.forEachIndexed { i, (s, n) ->
            val ty = y + i * 92f
            c.drawText("${i + 1}", m, ty + 52f, text(40f, INK3, serif()))
            draw(c, layout("${Meta.short(s.title)}  ·  ${s.artist}", text(38f, INK), (W - m * 2 - 200).toInt(), 1.1f, 1), m + 60f, ty + 14f)
            val cnt = "×$n"
            val cp = text(32f, INK3)
            c.drawText(cnt, W - m - cp.measureText(cnt), ty + 50f, cp)
        }
        if (r.apps.size > 1) {
            val total = r.apps.sumOf { it.second }
            val s = r.apps.take(3).joinToString("  ·  ") { "${it.first} ${it.second * 100 / total}%" }
            c.drawText(s, m, H - 190f, text(30f, INK2))
        }
        footer(c)
        return bmp
    }

    // 存到缓存目录，用系统分享面板发出去
    fun share(ctx: Context, bmp: Bitmap, name: String) {
        try {
            val dir = File(ctx.cacheDir, "cards").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "$name-${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".cards", f)
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("", uri)
            ctx.startActivity(Intent.createChooser(send, "分享卡片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Throwable) {
            android.widget.Toast.makeText(ctx, "分享没打开", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
