package com.ivyea.weiguang.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class Song(val id: Long, val title: String, val artist: String)
data class Playlist(val id: String, val name: String, val songIds: List<Long>)
data class Played(val id: Long, val at: Long, val count: Int)

data class LibState(
    val songs: Map<Long, Song> = emptyMap(),
    val playlists: List<Playlist> = emptyList(),
    val history: List<Played> = emptyList(),
    val syncedAt: Long = 0L,
    val syncing: Boolean = false,
    val syncMsg: String = "",
)

// 本机曲库索引：从网易云车机浏览服务读歌单 + 自己记的听歌记录。只存 ID、歌名、歌手
object Library {
    private val _state = MutableStateFlow(LibState())
    val state: StateFlow<LibState> = _state

    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastPlayed: Long? = null

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "library.json")
        load()
    }

    // ---------- 听歌记录 ----------

    fun recordPlay(id: Long, title: String, artist: String) {
        if (file == null || lastPlayed == id) return
        lastPlayed = id
        val s = _state.value
        val songs = s.songs.toMutableMap()
        songs[id] = Song(id, title, artist.ifEmpty { s.songs[id]?.artist ?: "" })
        val old = s.history.firstOrNull { it.id == id }
        val history = listOf(Played(id, System.currentTimeMillis(), (old?.count ?: 0) + 1)) +
            s.history.filter { it.id != id }.take(999)
        _state.value = s.copy(songs = songs, history = history)
        save()
    }

    // ---------- 同步歌单 ----------

    suspend fun sync(ctx: Context) {
        if (_state.value.syncing) return
        set { it.copy(syncing = true, syncMsg = "连接网易云…") }
        val b = connect(ctx)
        if (b == null) {
            set { it.copy(syncing = false, syncMsg = connectError) }
            return
        }
        try {
            val root = all(b, b.root)
            val mine = root.firstOrNull { it.mediaId == "my_music" }
                ?: root.firstOrNull { it.description.title?.contains("我的") == true }
            if (mine == null) {
                set { it.copy(syncing = false, syncMsg = "网易云没给出「我的音乐」") }
                return
            }
            val lists = all(b, mine.mediaId!!).filter { it.isBrowsable && it.mediaId != null }
            val songs = _state.value.songs.toMutableMap()
            val playlists = ArrayList<Playlist>()
            lists.forEachIndexed { i, pl ->
                val name = pl.description.title?.toString() ?: "歌单"
                set { it.copy(syncMsg = "读取歌单 ${i + 1}/${lists.size} · $name") }
                val ids = ArrayList<Long>()
                for (item in all(b, pl.mediaId!!)) {
                    if (!item.isPlayable) continue
                    val id = Ncm.songIdOf(item.mediaId) ?: continue
                    val artist = item.description.subtitle?.toString()?.takeIf { it.isNotBlank() } ?: songs[id]?.artist ?: ""
                    songs[id] = Song(id, item.description.title?.toString() ?: songs[id]?.title ?: "", artist)
                    ids += id
                }
                playlists += Playlist(pl.mediaId!!, name, ids)
            }
            set { it.copy(songs = songs, playlists = playlists, syncedAt = System.currentTimeMillis(), syncing = false, syncMsg = "") }
            save()
        } finally {
            try { b.disconnect() } catch (_: Throwable) {}
            if (_state.value.syncing) set { it.copy(syncing = false) }
        }
    }

    private inline fun set(f: (LibState) -> LibState) { _state.value = f(_state.value) }

    private var connectError = ""

    private suspend fun connect(ctx: Context): MediaBrowser? {
        if (Ncm.version(ctx) == null) { connectError = "还没装网易云"; return null }
        val svc = ctx.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(Ncm.PKG), 0)
            .firstOrNull()?.serviceInfo
        if (svc == null) { connectError = "这个版本的网易云没有开放歌单接口"; return null }
        connectError = "网易云拒绝了连接。确认已给微光开「通知使用权」；小米手机还要在应用设置里允许微光「关联启动」"
        var refused = false
        val b = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<MediaBrowser?> { cont ->
                lateinit var b: MediaBrowser
                b = MediaBrowser(ctx, ComponentName(svc.packageName, svc.name), object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { if (cont.isActive) cont.resume(b) }
                    override fun onConnectionFailed() { refused = true; if (cont.isActive) cont.resume(null) }
                }, null)
                cont.invokeOnCancellation { main.post { try { b.disconnect() } catch (_: Throwable) {} } }
                b.connect()
            }
        }
        if (b == null && !refused) connectError = "连接网易云超时，稍后再试"
        return b
    }

    // 分页读完一个节点；服务不认分页时第一页就是全部（没有新条目就停）
    private suspend fun all(b: MediaBrowser, id: String): List<MediaBrowser.MediaItem> {
        val out = LinkedHashMap<String, MediaBrowser.MediaItem>()
        val size = 200
        for (p in 0 until 40) {
            val items = page(b, id, p, size) ?: break
            val before = out.size
            for (it in items) it.mediaId?.let { mid -> out.putIfAbsent(mid, it) }
            if (items.isEmpty() || out.size == before || items.size < size) break
        }
        return out.values.toList()
    }

    private suspend fun page(b: MediaBrowser, id: String, page: Int, size: Int): List<MediaBrowser.MediaItem>? =
        withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<List<MediaBrowser.MediaItem>?> { cont ->
                val opts = Bundle().apply {
                    putInt(MediaBrowser.EXTRA_PAGE, page)
                    putInt(MediaBrowser.EXTRA_PAGE_SIZE, size)
                }
                val cb = object : MediaBrowser.SubscriptionCallback() {
                    fun done(list: List<MediaBrowser.MediaItem>?) {
                        if (!cont.isActive) return
                        val self = this
                        // 回调里直接退订可能打乱它的分发，挪到下一拍
                        main.post { try { b.unsubscribe(id, self) } catch (_: Throwable) {} }
                        cont.resume(list)
                    }
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) = done(children)
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>, options: Bundle) = done(children)
                    override fun onError(parentId: String) = done(null)
                    override fun onError(parentId: String, options: Bundle) = done(null)
                }
                b.subscribe(id, opts, cb)
            }
        }

    // ---------- 存取 ----------

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            val songs = HashMap<Long, Song>()
            o.optJSONArray("songs")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i)
                    val id = s.getLong("id")
                    songs[id] = Song(id, s.optString("t"), s.optString("a"))
                }
            }
            val playlists = ArrayList<Playlist>()
            o.optJSONArray("playlists")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    val ids = p.optJSONArray("s")
                    playlists += Playlist(p.getString("id"), p.optString("n"),
                        if (ids == null) emptyList() else List(ids.length()) { ids.getLong(it) })
                }
            }
            val history = ArrayList<Played>()
            o.optJSONArray("history")?.let { a ->
                for (i in 0 until a.length()) {
                    val h = a.getJSONObject(i)
                    history += Played(h.getLong("id"), h.optLong("at"), h.optInt("n", 1))
                }
            }
            _state.value = LibState(songs, playlists, history, o.optLong("syncedAt"))
        } catch (_: Throwable) {
            // 文件坏了就当没有，下次同步重建
        }
    }

    private fun save() {
        val f = file ?: return
        val s = _state.value
        val o = JSONObject()
        o.put("v", 1)
        o.put("syncedAt", s.syncedAt)
        o.put("songs", JSONArray().apply {
            for (song in s.songs.values) put(JSONObject().put("id", song.id).put("t", song.title).put("a", song.artist))
        })
        o.put("playlists", JSONArray().apply {
            for (p in s.playlists) put(JSONObject().put("id", p.id).put("n", p.name).put("s", JSONArray(p.songIds)))
        })
        o.put("history", JSONArray().apply {
            for (h in s.history) put(JSONObject().put("id", h.id).put("at", h.at).put("n", h.count))
        })
        val text = o.toString()
        io.execute {
            try {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(f)
            } catch (_: Throwable) {}
        }
    }
}
