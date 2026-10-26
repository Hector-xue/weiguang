package com.ivyea.weiguang.core

import java.util.Calendar

// 听歌回顾（本周 / 本月）：所有音乐 App 合在一起算（这是任何一个音乐 App 自己做不到的），再按听歌习惯起一个「听歌人格」。
// 只用本机的听歌流水，不上传
data class Recap(
    val title: String,
    val span: String,
    val from: Long,
    val to: Long,
    val plays: Int,
    val songs: Int,
    val artists: Int,
    val top: List<Pair<Song, Int>>,
    val topArtist: Pair<String, Int>?,
    val apps: List<Pair<String, Int>>,
    val latest: String?,
    val mood: String?,
    val persona: String,
    val line: String,
    val days: Int = 0,
)

object Recaps {
    private const val DAY = 24L * 3600 * 1000

    fun week(now: Long = System.currentTimeMillis()): Recap = of(now - 7 * DAY, now, "本周听歌", "这周")

    // 本月回顾：月初三天里看上个月整月（上个月听得够多的话），其余时间看这个月到今天
    fun month(now: Long = System.currentTimeMillis()): Recap {
        val c = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = c.timeInMillis
        val day = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_MONTH)
        if (day <= 3) {
            c.add(Calendar.MONTH, -1)
            val last = of(c.timeInMillis, start - 1, "${c.get(Calendar.MONTH) + 1}月听歌", "${c.get(Calendar.MONTH) + 1}月")
            if (last.plays >= 5) return last
        }
        val m = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.MONTH) + 1
        return of(start, now, "${m}月听歌", "这个月")
    }

    fun of(from: Long, now: Long, title: String, span: String): Recap {
        val st = Library.state.value
        val ps = st.plays.filter { it.at in from..now }
        val songs = st.songs
        val c = Calendar.getInstance()
        val hours = ps.map { c.timeInMillis = it.at; c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) }

        val byId = ps.groupingBy { it.id }.eachCount().entries.sortedByDescending { it.value }
        val top = byId.mapNotNull { e -> songs[e.key]?.takeIf { it.title.isNotEmpty() }?.let { it to e.value } }.take(3)

        val art = HashMap<String, Int>()
        for (p in ps) songs[p.id]?.artist?.split("/", "、", "&", ",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { art[it] = (art[it] ?: 0) + 1 }
        val topArtist = art.entries.maxByOrNull { it.value }?.let { it.key to it.value }

        val apps = ps.groupingBy { p -> Players.nameOf(songs[p.id]?.src ?: if (p.id > 0) Ncm.PKG else null) }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }

        // 最晚：凌晨 0~5 点算前一天的「晚」
        val late = hours.maxByOrNull { if (it < 5 * 60) it + 24 * 60 else it }
        val latest = late?.takeIf { it >= 22 * 60 || it < 5 * 60 }?.let { "%d:%02d".format(it / 60, it % 60) }

        val mood = Diary.state.value.moods.filter { it.at in from..now }.groupingBy { it.name }.eachCount().maxByOrNull { it.value }?.key

        val n = ps.size
        val night = hours.count { it >= 23 * 60 || it < 4 * 60 }
        val morning = hours.count { it in 5 * 60 until 9 * 60 }
        val first = byId.firstOrNull()
        val firstSong = first?.let { songs[it.key] }
        val tagged = ps.mapNotNull { songs[it.id] }.filter { it.v != null }
        val bright = if (tagged.size >= 5) tagged.map { it.v!! }.average() else null

        val (persona, line) = when {
            n < 5 -> "刚认识" to "${span}才一起听了 $n 首。多陪我听听，就能认出你是哪种人"
            night >= n * 0.4 -> "夜航人" to "${night * 100 / n}% 的歌在深夜听。夜里的心事，比白天多"
            first != null && firstSong != null && first.value >= maxOf(5, n / 4) -> "单曲循环者" to "《${Meta.short(firstSong.title)}》放了 ${first.value} 遍，它一定替你说了什么"
            topArtist != null && topArtist.second >= n * 0.4 -> "长情的人" to "${topArtist.second * 100 / n}% 的歌是${topArtist.first}。喜欢就一直听"
            art.size >= n * 0.6 -> "漫游者" to "$n 首歌来自 ${art.size} 位歌手，你很少在一个地方停留"
            morning >= n * 0.3 -> "早起的人" to "${morning * 100 / n}% 的歌在清晨听，一天从一首歌开始"
            bright != null && bright < 0.4 -> "安静的人" to "${span}的歌偏暗、偏慢。你可能需要一点时间，我陪着"
            bright != null && bright > 0.65 -> "发光的人" to "${span}的歌都很亮。好心情，接着亮下去"
            else -> "日常派" to "规律地听，像吃饭睡觉一样自然。音乐是你的日常"
        }
        val days = ps.map { c.timeInMillis = it.at; c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR) }.toSet().size
        return Recap(title, span, from, now, n, byId.size, art.size, top, topArtist, apps, latest, mood, persona, line, days)
    }
}
