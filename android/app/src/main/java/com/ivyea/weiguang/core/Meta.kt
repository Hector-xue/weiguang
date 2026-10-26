package com.ivyea.weiguang.core

// 把各家 App 报上来的歌名/歌手还原干净：
// 1) 有的 App 为了车载蓝牙显示歌词，把「当前这句歌词」塞进歌名、把「歌名 — 歌手」塞进歌手（汽水真机截图如此）；
// 2) 播放队列、车机接口的副标题常是「歌手 - 专辑」
object Meta {
    const val SODA = "com.luna.music"

    private val sep = Regex("\\s+[—–-]\\s+")

    data class Clean(val title: String, val artist: String, val lyric: String?)

    fun split(x: String): Pair<String, String>? {
        val p = x.split(sep, limit = 2)
        if (p.size != 2) return null
        val a = p[0].trim()
        val b = p[1].trim()
        return if (a.isEmpty() || b.isEmpty()) null else a to b
    }

    // 同一首歌（歌手栏、时长不变）里歌名一直在变 → 这个 App 在用「歌词当歌名」，记下来
    private var lastPkg = ""
    private var lastArtist = ""
    private var lastDur = 0L
    private var lastTitle = ""
    private var changes = 0

    fun live(pkg: String, title: String, artist: String, dur: Long): Clean {
        val same = pkg == lastPkg && artist == lastArtist && dur == lastDur && dur > 0
        if (!same) changes = 0
        else if (title != lastTitle && title.isNotEmpty() && lastTitle.isNotEmpty() && ++changes >= 2 && split(artist) != null) Prefs.setLyricMode(pkg)
        lastPkg = pkg; lastArtist = artist; lastDur = dur; lastTitle = title
        val sp = split(artist)
        val mode = Prefs.lyricMode(pkg) || (pkg == SODA && artist.contains("—"))
        if (!mode || sp == null) return Clean(title.trim(), artist.trim(), null)
        val line = title.trim().takeIf { it.isNotEmpty() && Library.norm(it) != Library.norm(sp.first) }
        return Clean(sp.first, sp.second, line)
    }

    // 队列/歌单条目：副标题「歌手 - 专辑」取歌手；「歌名 - 歌手」取歌手
    fun subtitle(title: String, sub: String): String {
        val sp = split(sub) ?: return sub
        return if (Library.norm(sp.first) == Library.norm(title)) sp.second else sp.first
    }

    // 旧版存进曲库的脏记录：汽水的「歌词当歌名」直接丢掉（一句歌词一首歌），其余歌手栏去掉专辑
    fun junk(s: Song) = s.id < 0 && s.src == SODA && s.artist.contains("—")
    fun fixArtist(s: Song): Song {
        val a = subtitle(s.title, s.artist)
        return if (a == s.artist) s else s.copy(artist = a)
    }
}
