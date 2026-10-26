package com.ivyea.weiguang.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

// 微光自己的音源：网络电台（Radio Browser 公开目录，免费、不用注册），由微光自己的播放器放，不用开别的 App。
// 按心情找对应风格的台；中文台目录里多是新闻/交通台，所以按风格标签找，不限国家
object Radio {
    data class Station(val name: String, val url: String, val icon: String?, val tag: String)

    private const val API = "https://de1.api.radio-browser.info/json/stations/search"

    // 心情 → 电台风格标签（Radio Browser 的 tag 是社区填的英文词）
    private val tags = mapOf(
        "低落" to listOf("acoustic", "piano"),
        "疲惫" to listOf("ambient", "chillout"),
        "烦躁" to listOf("rock", "chillout"),
        "想哭" to listOf("piano", "classical"),
        "平静" to listOf("ambient", "classical"),
        "专注" to listOf("lofi", "study"),
        "开心" to listOf("pop", "hits"),
        "想燃" to listOf("dance", "edm"),
    )

    // 没说心情时：按钟点
    fun moodNow(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) { in 6..9 -> "开心"; in 10..17 -> "专注"; in 18..21 -> "平静"; else -> "疲惫" }
    }

    suspend fun stations(mood: String): List<Station> = withContext(Dispatchers.IO) {
        val out = ArrayList<Station>()
        for (t in tags[mood] ?: listOf("pop")) {
            try {
                val url = "$API?tag=$t&hidebroken=true&is_https=true&order=clickcount&reverse=true&limit=25"
                Sources.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val a = JSONArray(r.body?.string() ?: "[]")
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val u = o.optString("url_resolved").ifEmpty { o.optString("url") }
                        if (u.isEmpty() || o.optString("codec") !in setOf("MP3", "AAC", "AAC+", "OGG")) continue
                        out += Station(o.optString("name").trim(), u, o.optString("favicon").takeIf { it.startsWith("https") }, t)
                    }
                }
            } catch (_: Throwable) {}
            if (out.size >= 10) break
        }
        out.distinctBy { it.url }
    }

    // 放一个这种心情的台；失败返回原因
    suspend fun play(ctx: Context, mood: String = moodNow()): String? {
        val list = stations(mood)
        if (list.isEmpty()) return "电台目录连不上"
        val pick = list.take(8).shuffled()
        val songs = pick.map { st ->
            Song(-1L - (st.url.hashCode().toLong() and 0xffffffL), st.name, "电台 · $mood", null, null, st.icon, src = Local.PKG, loc = "url:" + st.url)
        }
        return withContext(Dispatchers.Main) { if (Local.play(ctx, songs)) null else "微光的播放器没起来" }
    }
}
