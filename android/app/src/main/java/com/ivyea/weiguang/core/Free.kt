package com.ivyea.weiguang.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

// 免费曲库：Audius（音乐人自己上传、免费公开收听的平台，开放接口不用注册）。整首歌、有封面、带心情标签，
// 由微光自己的播放器放。以独立音乐人的电子、Lo-Fi、流行为主，没有主流华语歌
object Free {
    private const val API = "https://api.audius.co/v1"
    private const val APP = "app_name=weiguang"

    // 曲风（Audius 的 genre 原名）→ 显示名
    val genres = linkedMapOf(
        "Lo-Fi" to "Lo-Fi", "Pop" to "流行", "Electronic" to "电子", "Ambient" to "氛围",
        "Acoustic" to "原声", "Jazz" to "爵士", "Classical" to "古典", "Hip-Hop/Rap" to "说唱",
        "Folk" to "民谣", "R&B/Soul" to "R&B", "House" to "House", "Downtempo" to "慢拍",
    )

    // 心情 → 去哪些曲风找、优先哪些心情标签
    private val moods = mapOf(
        "低落" to (listOf("Acoustic", "Lo-Fi") to setOf("Melancholy", "Sentimental", "Yearning", "Tender")),
        "疲惫" to (listOf("Lo-Fi", "Ambient", "Downtempo") to setOf("Peaceful", "Easygoing", "Tender")),
        "烦躁" to (listOf("Alternative", "Downtempo") to setOf("Brooding", "Defiant", "Gritty", "Peaceful")),
        "想哭" to (listOf("Acoustic", "Classical", "Ambient") to setOf("Melancholy", "Sentimental")),
        "平静" to (listOf("Ambient", "Classical", "Jazz") to setOf("Peaceful", "Tender", "Easygoing")),
        "专注" to (listOf("Lo-Fi", "Ambient", "Classical") to setOf("Peaceful", "Sophisticated", "Cool")),
        "开心" to (listOf("Pop", "Funk", "Disco") to setOf("Upbeat", "Excited", "Romantic", "Easygoing")),
        "想燃" to (listOf("Electronic", "House", "Drum & Bass") to setOf("Energizing", "Fiery", "Empowering", "Excited")),
    )

    private fun get(path: String): List<JSONObject> {
        val sep = if (path.contains('?')) "&" else "?"
        Sources.http.newCall(Request.Builder().url("$API$path$sep$APP").build()).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val a = JSONObject(r.body?.string() ?: "{}").optJSONArray("data") ?: return emptyList()
            return List(a.length()) { a.getJSONObject(it) }
        }
    }

    private fun usable(o: JSONObject) = o.optBoolean("is_streamable", true) && !o.optBoolean("is_delete") &&
        o.optInt("duration") in 60..900 && o.optJSONObject("stream_conditions") == null

    fun song(o: JSONObject): Song {
        val id = o.getString("id")
        val art = o.optJSONObject("artwork")
        return Song(
            Library.localId("audius:$id"), o.optString("title").trim(), o.optJSONObject("user")?.optString("name")?.trim() ?: "",
            pic = art?.optString("480x480")?.takeIf { it.startsWith("https") } ?: "",
            src = Local.PKG, nid = 0, loc = "url:$API/tracks/$id/stream?$APP",
        )
    }

    suspend fun genre(g: String): List<Song> = withContext(Dispatchers.IO) {
        try { get("/tracks/trending?genre=${android.net.Uri.encode(g)}&time=month").filter(::usable).map(::song) } catch (_: Throwable) { emptyList() }
    }

    suspend fun mood(m: String): List<Song> = withContext(Dispatchers.IO) {
        val (gs, want) = moods[m] ?: (listOf("Pop") to emptySet())
        val all = gs.flatMap { g ->
            try { get("/tracks/trending?genre=${android.net.Uri.encode(g)}&time=month").filter(::usable) } catch (_: Throwable) { emptyList() }
        }
        val hit = all.filter { it.optString("mood") in want }
        (hit.shuffled() + (all - hit.toSet()).shuffled()).distinctBy { it.optString("id") }.take(24).map(::song)
    }

    // 放一串（先记进曲库）
    fun play(ctx: Context, songs: List<Song>, start: Int = 0): Boolean {
        if (songs.isEmpty()) return false
        Library.addOwn(songs)
        return Local.play(ctx, songs, start)
    }

    // 按心情放：先免费曲库的歌，连不上就放网络电台。返回失败原因
    suspend fun playMood(ctx: Context, m: String = Radio.moodNow()): String? {
        if (Prefs.zhOnly) return Radio.playChinese(ctx)
        val list = mood(m)
        if (list.isNotEmpty()) return withContext(Dispatchers.Main) { if (play(ctx, list)) null else "微光的播放器没起来" }
        return Radio.play(ctx, m)
    }
}
