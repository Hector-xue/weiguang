package com.ivyea.weiguang.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ivyea.weiguang.MainActivity
import com.ivyea.weiguang.R
import com.ivyea.weiguang.core.Diary
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.Nightly
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Recaps

// 听歌人格小组件：这周你是哪种听歌的人。点开就是本周回顾卡
class RecapWidget : AppWidgetProvider() {
    companion object {
        // 听歌记录变了就刷（App 里每 5 次播放、通知到点、系统 3 小时兜底）
        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = try { m.getAppWidgetIds(ComponentName(ctx, RecapWidget::class.java)) } catch (_: Throwable) { return }
            if (ids.isEmpty()) return
            val v = views(ctx)
            for (id in ids) try { m.updateAppWidget(id, v) } catch (_: Throwable) {}
        }

        internal fun views(ctx: Context): RemoteViews {
            val r = Recaps.week()
            val v = RemoteViews(ctx.packageName, R.layout.widget_recap)
            v.setTextViewText(R.id.r_persona, r.persona)
            v.setTextViewText(R.id.r_line, r.line)
            v.setTextViewText(
                R.id.r_stat,
                if (r.plays == 0) "微光 · 懂你此刻的听歌伙伴"
                else listOfNotNull("${r.plays} 次播放", r.latest?.let { "最晚 $it" } ?: "${r.artists} 位歌手").joinToString(" · "),
            )
            v.setOnClickPendingIntent(R.id.r_root, PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java).putExtra(Nightly.EXTRA, "recap_week")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            return v
        }
    }

    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        // 进程可能刚起来：先把听歌记录读进来
        Prefs.init(ctx)
        Library.init(ctx)
        Diary.init(ctx)
        refresh(ctx)
    }
}
