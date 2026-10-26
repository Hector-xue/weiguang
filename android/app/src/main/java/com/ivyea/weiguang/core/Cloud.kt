package com.ivyea.weiguang.core

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 在网易云曲库里对上的那首：id=0 表示查过没对上
data class Hit(val id: Long, val pic: String)

// 一句歌词：t=开始时间（毫秒），tr=翻译（没有就是 null）
data class LrcLine(val t: Long, val text: String, val tr: String? = null)

// 网易云网页版的公开接口：整首歌词、专辑封面。不登录、不带任何账号信息，只按歌曲 ID 查
object Cloud {
    private val lyrics = LruCache<Long, List<LrcLine>>(40)

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("Referer", "https://music.163.com/")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
        } finally { c.disconnect() }
    } catch (_: Throwable) { null }

    // null=没拿到（网络问题，下次再试）；空列表=纯音乐/没有歌词
    suspend fun lyric(id: Long): List<LrcLine>? {
        lyrics.get(id)?.let { return it }
        val raw = withContext(Dispatchers.IO) { get("https://music.163.com/api/song/lyric?id=$id&lv=1&tv=-1") } ?: return null
        val lines = try {
            val o = JSONObject(raw)
            if (o.optBoolean("pureMusic") || o.optBoolean("nolyric")) emptyList()
            else {
                val main = parse(o.optJSONObject("lrc")?.optString("lyric").orEmpty())
                val tr = parse(o.optJSONObject("tlyric")?.optString("lyric").orEmpty()).associate { it.t to it.text }
                main.map { l -> l.copy(tr = tr[l.t]?.takeIf { it.isNotBlank() && it != l.text }) }
            }
        } catch (_: Throwable) { return null }
        lyrics.put(id, lines)
        return lines
    }

    private val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    // 标准 LRC，一行可以带多个时间戳；新版歌词开头的署名行是 {"t":0,"c":[{"tx":"作词: "},…]} 这种 JSON
    fun parse(text: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("{")) {
                try {
                    val o = JSONObject(line)
                    val c = o.optJSONArray("c") ?: continue
                    val s = buildString { for (i in 0 until c.length()) append(c.optJSONObject(i)?.optString("tx").orEmpty()) }.trim()
                    if (s.isNotEmpty()) out += LrcLine(o.optLong("t"), s)
                } catch (_: Throwable) {}
                continue
            }
            val stamps = stamp.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val words = line.substring(stamps.last().range.last + 1).trim()
            if (words.isEmpty()) continue
            for (m in stamps) {
                val (mm, ss, frac) = m.destructured
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out += LrcLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + ms, words)
            }
        }
        return out.sortedBy { it.t }
    }

    // 一次最多查 100 首的专辑封面地址；查不到的歌不在结果里
    suspend fun covers(ids: List<Long>): Map<Long, String>? {
        if (ids.isEmpty()) return emptyMap()
        val raw = withContext(Dispatchers.IO) {
            get("https://music.163.com/api/song/detail/?ids=" + java.net.URLEncoder.encode(ids.joinToString(",", "[", "]"), "UTF-8"))
        } ?: return null
        return try {
            val a = JSONObject(raw).optJSONArray("songs") ?: return emptyMap()
            val m = HashMap<Long, String>()
            for (i in 0 until a.length()) {
                val s = a.getJSONObject(i)
                val pic = s.optJSONObject("album")?.optString("picUrl").orEmpty()
                if (pic.startsWith("http")) m[s.getLong("id")] = pic.replace("http://", "https://")
            }
            m
        } catch (_: Throwable) { null }
    }

    // 别的 App 在放的歌，去网易云曲库里对一下，拿到 ID 才有整首歌词和封面。
    // 歌名得对上；歌手或时长再对上一样才算（翻唱、同名歌很多）。null=网络问题，下次再试
    suspend fun match(title: String, artist: String, durMs: Long = 0): Hit? {
        val t = Library.norm(title)
        if (t.isEmpty()) return Hit(0, "")
        val q = (title + " " + artist.split("/", "、", "&", ",").first()).trim()
        val raw = withContext(Dispatchers.IO) {
            get("https://music.163.com/api/cloudsearch/pc?type=1&limit=10&offset=0&s=" + java.net.URLEncoder.encode(q, "UTF-8"))
        } ?: return null
        return try {
            val o = JSONObject(raw)
            // 被限流时 code 不是 200，当网络问题处理，别把这首记成「对不上」
            if (o.optInt("code", 200) != 200) return null
            val a = o.optJSONObject("result")?.optJSONArray("songs") ?: return Hit(0, "")
            val wantAr = artist.split("/", "、", "&", ",").flatMap { aliases(it) }.toSet()
            var best: Hit? = null
            var bestScore = 0
            for (i in 0 until a.length()) {
                val s = a.getJSONObject(i)
                val name = Library.norm(s.optString("name"))
                val titleOk = name == t || (name.startsWith(t) && name.length - t.length <= 6)
                if (!titleOk) continue
                val ars = s.optJSONArray("ar")
                val arOk = ars != null && (0 until ars.length()).any { j -> aliases(ars.getJSONObject(j).optString("name")).any { it in wantAr } }
                val dt = s.optLong("dt")
                val durOk = durMs > 0 && dt > 0 && kotlin.math.abs(dt - durMs) < 4000
                if (!arOk && !durOk) continue
                val score = (if (name == t) 4 else 1) + (if (arOk) 3 else 0) + (if (durOk) 2 else 0)
                if (score > bestScore) {
                    bestScore = score
                    val pic = s.optJSONObject("al")?.optString("picUrl").orEmpty().replace("http://", "https://")
                    best = Hit(s.getLong("id"), if (pic.startsWith("http")) pic else "")
                }
            }
            best ?: Hit(0, "")
        } catch (_: Throwable) { null }
    }

    // 歌手名的几种写法：「冯沁苑(买辣椒也用券)」里外都算。只去空白不去标点，免得「周杰伦.」这种冒名的也对上
    private fun aliases(name: String): List<String> {
        val n = name.lowercase().filterNot { it.isWhitespace() }
        if (n.isEmpty()) return emptyList()
        return (listOf(n) + n.split('(', ')', '（', '）')).filter { it.isNotEmpty() }.distinct()
    }
}
