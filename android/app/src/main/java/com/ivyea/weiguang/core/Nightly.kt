package com.ivyea.weiguang.core

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ivyea.weiguang.MainActivity
import com.ivyea.weiguang.R
import com.ivyea.weiguang.service.NightlyReceiver
import java.util.Calendar

// 每晚一首：到点发一条通知——今天听得最多的那首，或者好久没听的一首；周日换成本周回顾，月初换成上个月的回顾。
// 默认关，用户点头才开；用不精确闹钟（前后 15 分钟），不要精确闹钟权限
object Nightly {
    const val CHANNEL = "nightly"
    const val EXTRA = "wg_open"
    const val ID = 7

    data class Note(val title: String, val text: String, val open: String, val play: Long?)

    fun granted(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun label(min: Int = Prefs.nightlyAt) = "%d:%02d".format(min / 60, min % 60)

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, NightlyReceiver::class.java).setAction(NightlyReceiver.FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // 排下一次：今天的点还没过就今天，过了就明天
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (!Prefs.nightly) { am.cancel(pending(ctx)); return }
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Prefs.nightlyAt / 60); set(Calendar.MINUTE, Prefs.nightlyAt % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_MONTH, 1)
        }
        try { am.setWindow(AlarmManager.RTC_WAKEUP, c.timeInMillis, 15 * 60_000L, pending(ctx)) } catch (_: Throwable) {}
    }

    private fun dayKey(at: Long = System.currentTimeMillis()): Int =
        Calendar.getInstance().apply { timeInMillis = at }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }

    // 这一晚说什么；什么都没有（一首歌都没听过）就不打扰
    fun compose(now: Long = System.currentTimeMillis()): Note? {
        val st = Library.state.value
        if (st.plays.isEmpty()) return null
        val c = Calendar.getInstance().apply { timeInMillis = now }

        if (c.get(Calendar.DAY_OF_MONTH) == 1) {
            val m = Recaps.month(now)
            if (m.plays >= 5) return Note("${m.span}的听歌回顾出来了", "你是「${m.persona}」。${m.line}", "recap_month", null)
        }
        if (c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            val w = Recaps.week(now)
            if (w.plays >= 5) return Note("这周你是「${w.persona}」", w.line, "recap_week", null)
        }

        val today = dayKey(now)
        val ps = st.plays.filter { dayKey(it.at) == today }
        val top = ps.groupingBy { it.id }.eachCount().entries.sortedByDescending { it.value }
            .firstNotNullOfOrNull { e -> st.songs[e.key]?.takeIf { it.title.isNotEmpty() }?.let { it to e.value } }
        if (top != null) {
            val (s, n) = top
            val t = if (n >= 2) "今天听了 ${ps.size} 首，它放了 $n 遍。睡前再听一次？" else "今天听了 ${ps.size} 首，睡前用它收个尾？"
            return Note("今天的歌：《${Meta.short(s.title)}》", t, "moment", s.id)
        }

        // 今天没听：挑一首听过好几次、但一周多没碰的
        val last = HashMap<Long, Long>()
        val count = HashMap<Long, Int>()
        for (p in st.plays) { last[p.id] = maxOf(last[p.id] ?: 0L, p.at); count[p.id] = (count[p.id] ?: 0) + 1 }
        val week = now - 7L * 24 * 3600 * 1000
        val old = count.entries.filter { it.value >= 2 && (last[it.key] ?: now) < week }
            .sortedByDescending { it.value }.take(8).mapNotNull { st.songs[it.key] }
            .filter { it.title.isNotEmpty() && (!Prefs.zhOnly || Library.isZh(it)) }
        val pick = old.randomOrNull() ?: return Note("今天还没一起听歌", "睡前来一首吧，说说今天怎么样，我帮你挑。", "moment", null)
        val days = ((now - (last[pick.id] ?: now)) / (24 * 3600 * 1000)).toInt()
        return Note("好久没听《${Meta.short(pick.title)}》了", "上次听它是 $days 天前。今晚放一次？", "moment", pick.id)
    }

    fun fire(ctx: Context) {
        Prefs.init(ctx)
        if (!Prefs.nightly) return
        schedule(ctx)
        val today = dayKey()
        if (Prefs.nightlyLast == today || !granted(ctx)) return
        Library.init(ctx)
        Diary.init(ctx)
        val n = compose() ?: return
        Prefs.nightlyLast = today
        show(ctx, n)
    }

    private fun show(ctx: Context, n: Note) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "每晚一首", NotificationManager.IMPORTANCE_DEFAULT))
        fun open(what: String, code: Int) = PendingIntent.getActivity(
            ctx, code, Intent(ctx, MainActivity::class.java).putExtra(EXTRA, what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(n.title).setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setContentIntent(open(n.open, 1))
            .setAutoCancel(true)
        if (n.play != null) b.addAction(0, "放这首", open("play:${n.play}", 2))
        try { nm.notify(ID, b.build()) } catch (_: SecurityException) {}
    }
}
