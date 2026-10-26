package com.ivyea.weiguang.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.ivyea.weiguang.MainActivity
import com.ivyea.weiguang.R
import com.ivyea.weiguang.core.Buddy
import com.ivyea.weiguang.core.NowPlaying
import com.ivyea.weiguang.core.Players
import com.ivyea.weiguang.core.Prefs

// 桌面小组件：封面、歌名、微光的一句话，上一首/播放暂停/下一首。点空白处打开微光
class NowWidget : AppWidgetProvider() {
    companion object {
        private const val PREV = "com.ivyea.weiguang.widget.PREV"
        private const val TOGGLE = "com.ivyea.weiguang.widget.TOGGLE"
        private const val NEXT = "com.ivyea.weiguang.widget.NEXT"

        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = try { m.getAppWidgetIds(ComponentName(ctx, NowWidget::class.java)) } catch (_: Throwable) { return }
            if (ids.isEmpty()) return
            val v = views(ctx)
            for (id in ids) try { m.updateAppWidget(id, v) } catch (_: Throwable) {}
        }

        private fun views(ctx: Context): RemoteViews {
            val n = NowPlaying.now.value
            val v = RemoteViews(ctx.packageName, R.layout.widget_now)
            if (n == null || n.title.isEmpty()) {
                v.setTextViewText(R.id.w_title, "微光")
                v.setTextViewText(R.id.w_sub, Buddy.thought.value ?: "放首歌，我陪你听")
                v.setImageViewResource(R.id.w_art, R.drawable.logo_mark)
                v.setImageViewResource(R.id.w_toggle, R.drawable.w_play)
            } else {
                v.setTextViewText(R.id.w_title, n.title)
                v.setTextViewText(R.id.w_sub, n.lyric?.takeIf { Buddy.isLyric(it) } ?: listOf(n.artist, Players.nameOf(n.pkg)).filter { it.isNotEmpty() }.joinToString(" · "))
                val art = n.art?.let { scaled(it) }
                if (art != null) v.setImageViewBitmap(R.id.w_art, art) else v.setImageViewResource(R.id.w_art, R.drawable.logo_mark)
                v.setImageViewResource(R.id.w_toggle, if (n.playing) R.drawable.w_pause else R.drawable.w_play)
            }
            val thought = Buddy.thought.value
            if (n != null && thought != null) {
                v.setViewVisibility(R.id.w_thought, View.VISIBLE)
                v.setTextViewText(R.id.w_thought, "✦ $thought")
            } else v.setViewVisibility(R.id.w_thought, View.GONE)
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            v.setOnClickPendingIntent(R.id.w_prev, action(ctx, PREV, 1))
            v.setOnClickPendingIntent(R.id.w_toggle, action(ctx, TOGGLE, 2))
            v.setOnClickPendingIntent(R.id.w_next, action(ctx, NEXT, 3))
            return v
        }

        private fun action(ctx: Context, a: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, NowWidget::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // 小组件传的图有大小上限，压到 160 像素
        private fun scaled(b: Bitmap): Bitmap? = try {
            val s = 160
            if (b.width <= s && b.height <= s) b else Bitmap.createScaledBitmap(b, s, s * b.height / b.width.coerceAtLeast(1), true)
        } catch (_: Throwable) { null }
    }

    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        Prefs.init(ctx)
        NowPlaying.attach(ctx)
        refresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            PREV, TOGGLE, NEXT -> {
                Prefs.init(ctx)
                NowPlaying.attach(ctx)
                when (intent.action) {
                    PREV -> NowPlaying.prev()
                    TOGGLE -> NowPlaying.toggle()
                    else -> NowPlaying.next()
                }
            }
        }
    }
}
