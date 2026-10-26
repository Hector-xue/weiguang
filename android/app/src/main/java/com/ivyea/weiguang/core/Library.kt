package com.ivyea.weiguang.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

// id：正数=网易云歌曲 ID；负数=在别的 App 听到、按「歌名|歌手」算出来的本机 ID
// v=情绪明亮度、e=能量（0~1，AI 打的标签，没打过就是 null）；pic=专辑封面地址（null 没查过，"" 查过没有）
// src=在哪个 App 听到/同步来的（null=网易云）；nid=对上的网易云 ID（null 没查过，0 没对上）；
// mid=来源 App 车机接口里的条目 ID；m=开着微光听的时候实测的「活跃度」（越大越激烈）；
// loc=微光自己能放的位置（自建曲库「服务器id:条目id」或 AI 作曲「gen:文件名」）
data class Song(
    val id: Long, val title: String, val artist: String,
    val v: Float? = null, val e: Float? = null, val pic: String? = null,
    val src: String? = null, val nid: Long? = null, val mid: String? = null, val m: Float? = null,
    val loc: String? = null,
) {
    // 拿歌词、封面、在网易云里点播用的 ID
    fun cloudId(): Long? = if (id > 0) id else nid?.takeIf { it > 0 }
}
// 网易云的歌单 id 是它自己的 mediaId；别的 App 的是「包名|mediaId」
data class Playlist(val id: String, val name: String, val songIds: List<Long>) {
    val src: String get() = if ('|' in id) id.substringBefore('|') else Ncm.PKG
}
data class Played(val id: Long, val at: Long, val count: Int)
// 每一次播放的流水，用来学「这个点你常听什么」
data class Play(val id: Long, val at: Long)

data class LibState(
    val songs: Map<Long, Song> = emptyMap(),
    val playlists: List<Playlist> = emptyList(),
    val history: List<Played> = emptyList(),
    val plays: List<Play> = emptyList(),
    val syncedAt: Long = 0L,
    val syncing: Boolean = false,
    val syncMsg: String = "",
    val syncLog: String = "",
)

// 本机曲库索引：从各音乐 App 的车机浏览服务读歌单 + 自己记的听歌记录。只存 ID、歌名、歌手
object Library {
    private val _state = MutableStateFlow(LibState())
    val state: StateFlow<LibState> = _state

    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastPlayed: Long? = null

    // 截图测试用：直接摆一份曲库
    internal fun preview(st: LibState) { _state.value = st; index(st.songs) }

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "library.json")
        load()
    }

    // ---------- 听歌记录 ----------

    // ---------- 同一首歌认出来 ----------

    // 歌名歌手归一：小写、去空白和标点，用来认「是不是同一首」
    fun norm(x: String): String = x.lowercase().filter { it.isLetterOrDigit() }

    fun keyOf(title: String, artist: String) = norm(title) + "|" + norm(artist.split("/", "、", "&", ",").first())

    private val keys = HashMap<String, Long>()

    private fun index(songs: Map<Long, Song>) {
        keys.clear()
        for (s in songs.values) if (s.title.isNotEmpty()) remember(s)
    }

    // 同名同歌手有网易云 ID 的优先
    private fun remember(s: Song) {
        val k = keyOf(s.title, s.artist)
        val old = keys[k]
        if (old == null || (old < 0 && s.id > 0)) keys[k] = s.id
    }

    // 没见过的歌给一个固定的负数 ID（同一首歌每次算出来都一样）
    fun localId(key: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in key.toByteArray()) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return -((h ushr 1) or 1L)
    }

    // 播放会话里的这首歌在曲库里是哪个 ID
    fun idOf(pkg: String, mediaId: String?, title: String, artist: String): Long? {
        if (pkg == Ncm.PKG) return Ncm.songIdOf(mediaId)
        if (pkg == Local.PKG && mediaId?.startsWith("wg:") == true) mediaId.removePrefix("wg:").toLongOrNull()?.let { return it }
        if (pkg == Local.PKG && mediaId?.startsWith("radio:") == true) return null
        if (title.isBlank()) return null
        val k = keyOf(title, artist)
        return keys[k] ?: localId(k)
    }

    // ---------- 听歌记录 ----------

    fun recordPlay(id: Long, title: String, artist: String, src: String?) {
        if (file == null || lastPlayed == id) return
        lastPlayed = id
        val s = _state.value
        val songs = s.songs.toMutableMap()
        val old = s.songs[id]
        val song = (old ?: Song(id, title, artist, src = src.takeIf { id < 0 }))
            .copy(title = title, artist = artist.ifEmpty { old?.artist ?: "" })
        songs[id] = song
        remember(song)
        val oldH = s.history.firstOrNull { it.id == id }
        val nowMs = System.currentTimeMillis()
        val history = listOf(Played(id, nowMs, (oldH?.count ?: 0) + 1)) +
            s.history.filter { it.id != id }.take(999)
        _state.value = s.copy(songs = songs, history = history, plays = (s.plays + Play(id, nowMs)).takeLast(3000))
        save()
    }

    fun setMatch(id: Long, hit: Hit) {
        val s = _state.value
        val song = s.songs[id] ?: return
        val songs = s.songs.toMutableMap()
        songs[id] = song.copy(nid = hit.id, pic = if (song.pic.isNullOrEmpty()) hit.pic else song.pic)
        _state.value = s.copy(songs = songs)
        save()
    }

    // 点歌前临时对一下网易云 ID（别的 App 听到的歌要在网易云里放时）
    suspend fun matchNow(s: Song): Long? {
        s.cloudId()?.let { return it }
        if (s.nid == 0L) return null
        val hit = Cloud.match(s.title, s.artist) ?: return null
        setMatch(s.id, hit)
        return hit.id.takeIf { it > 0 }
    }

    // ---------- 实测能量 ----------

    private var mSorted = FloatArray(0)

    private fun rankAll(songs: Map<Long, Song>) {
        mSorted = songs.values.mapNotNull { it.m }.sorted().toFloatArray()
    }

    fun measuredCount() = mSorted.size

    fun setMeasured(id: Long, m: Float) {
        val s = _state.value
        val song = s.songs[id] ?: return
        val songs = s.songs.toMutableMap()
        songs[id] = song.copy(m = song.m?.let { (it + m) / 2f } ?: m)
        _state.value = s.copy(songs = songs)
        rankAll(songs)
        save()
    }

    // 星图上用的能量：AI 估的和实测的各占一半；实测过的歌不到 8 首时排名不可靠，只用 AI 的
    fun energy(s: Song): Float? {
        val e = s.e ?: return null
        val m = s.m ?: return e
        if (mSorted.size < 8) return e
        var lo = 0
        while (lo < mSorted.size && mSorted[lo] < m) lo++
        val rank = lo.toFloat() / (mSorted.size - 1).coerceAtLeast(1)
        return e * 0.5f + rank * 0.5f
    }

    // 歌单封面 = 第一首有封面的歌
    fun coverOf(p: Playlist): String? {
        val songs = _state.value.songs
        return p.songIds.asSequence().take(30).mapNotNull { songs[it]?.pic?.takeIf { u -> u.isNotEmpty() } }.firstOrNull()
    }

    // 给没查过封面的歌补上封面地址：先每个歌单的前几首，再听过的，再其余；
    // 别的 App 听到的歌先去网易云对上 ID（顺带拿到封面）
    private var filling = false
    suspend fun fillCovers() {
        if (file == null || filling) return
        filling = true
        try {
            val s0 = _state.value
            val order = LinkedHashSet<Long>()
            s0.playlists.forEach { p -> p.songIds.take(3).forEach { order += it } }
            s0.history.forEach { order += it.id }
            s0.playlists.forEach { p -> order += p.songIds }
            val todo = order.filter { it > 0 && s0.songs[it]?.pic == null }.take(2000)
            for (batch in todo.chunked(100)) {
                val got = Cloud.covers(batch) ?: break
                val s = _state.value
                val songs = s.songs.toMutableMap()
                for (id in batch) songs[id]?.let { songs[id] = it.copy(pic = got[id] ?: "") }
                _state.value = s.copy(songs = songs)
            }
            if (todo.isNotEmpty()) save()
            val local = order.filter { it < 0 && s0.songs[it]?.nid == null }.take(150)
            for (id in local) {
                val song = _state.value.songs[id] ?: continue
                val hit = Cloud.match(song.title, song.artist) ?: break
                setMatch(id, hit)
                kotlinx.coroutines.delay(200)
            }
        } finally { filling = false }
    }

    // 播放队列：把里面的歌补进曲库；队列名和某个歌单同名且更全时，顺便补全这个歌单
    // （网易云车机接口每个歌单只给前 12 首，在网易云里播一次歌单就能补全）
    fun absorbQueue(title: String?, items: List<Triple<Long, String, String>>, src: String? = null) {
        if (file == null || items.isEmpty()) return
        val s = _state.value
        val songs = s.songs.toMutableMap()
        var changed = false
        for ((id, t, a) in items) {
            val old = songs[id]
            if (old == null || (old.title.isEmpty() && t.isNotEmpty())) {
                val song = (old ?: Song(id, t, a, src = src.takeIf { id < 0 })).copy(title = t.ifEmpty { old?.title ?: "" }, artist = a.ifEmpty { old?.artist ?: "" })
                songs[id] = song
                remember(song)
                changed = true
            }
        }
        val ids = items.map { it.first }
        val playlists = s.playlists.map { p ->
            if (!title.isNullOrBlank() && p.src == (src ?: Ncm.PKG) && p.name == title.trim() && ids.size > p.songIds.size) { changed = true; p.copy(songIds = ids) } else p
        }
        if (!changed) return
        _state.value = s.copy(songs = songs, playlists = playlists)
        save()
    }

    fun setMoods(m: Map<Long, Pair<Float, Float>>) {
        if (m.isEmpty()) return
        val s = _state.value
        val songs = s.songs.toMutableMap()
        for ((id, ve) in m) songs[id]?.let { songs[id] = it.copy(v = ve.first.coerceIn(0f, 1f), e = ve.second.coerceIn(0f, 1f)) }
        _state.value = s.copy(songs = songs)
        save()
    }

    // ---------- 同步歌单 ----------

    // 每个装了的音乐 App 都试一遍；连不上的那家保留上次的歌单，明细写进同步记录
    // 同步挂在曲库自己的作用域上：切页面、离开设置都不会把它打断
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun syncAsync(ctx: Context) { bg.launch { sync(ctx.applicationContext) } }

    suspend fun sync(ctx: Context) {
        if (_state.value.syncing) return
        val apps = Players.installed(ctx)
        val servers = Sources.servers
        if (apps.isEmpty() && servers.isEmpty()) { set { it.copy(syncMsg = "还没装音乐 App") }; return }
        set { it.copy(syncing = true, syncMsg = "开始同步…") }
        val songs = _state.value.songs.toMutableMap()
        val playlists = ArrayList<Playlist>()
        val log = StringBuilder()
        val errs = ArrayList<String>()
        var ok = 0
        try {
            for (p in apps) {
                set { it.copy(syncMsg = "连接${p.short}…") }
                val b = connect(ctx, p.pkg)
                if (b == null) {
                    errs += "${p.short}：$connectError"
                    log.append("【${p.short}】").append(connectError).append('\n')
                    playlists += _state.value.playlists.filter { it.src == p.pkg }
                    continue
                }
                try {
                    val got = if (p.pkg == Ncm.PKG) syncNcm(b, songs, log) else syncOther(b, p, songs, log)
                    if (got == null) {
                        playlists += _state.value.playlists.filter { it.src == p.pkg }
                        errs += "${p.short}：没读到歌单"
                    } else { playlists += got; ok++ }
                } catch (_: Throwable) {
                    errs += "${p.short}：读取中断"
                    playlists += _state.value.playlists.filter { it.src == p.pkg }
                } finally {
                    try { b.disconnect() } catch (_: Throwable) {}
                }
            }
            // 自己的曲库服务器
            for (sv in servers) {
                set { it.copy(syncMsg = "读取 ${sv.name}…") }
                val prefix = "${Local.PKG}|${sv.id}:"
                try {
                    val data = Sources.fetch(sv)
                    for ((plid, name, items) in data.lists) {
                        val ids = items.map { itemSong(songs, it) }.distinct()
                        if (ids.isNotEmpty()) playlists += Playlist(prefix + plid, name, ids)
                    }
                    log.append("【${sv.name}】").append(data.lists.size).append(" 个列表，")
                        .append(data.lists.lastOrNull()?.third?.size ?: 0).append(" 首\n")
                    ok++
                } catch (e: Throwable) {
                    errs += "${sv.name}：${e.message ?: "读取失败"}"
                    log.append("【${sv.name}】").append(e.message ?: "读取失败").append('\n')
                    playlists += _state.value.playlists.filter { it.id.startsWith(prefix) }
                }
            }
            playlists += _state.value.playlists.filter { it.id == GEN || it.id == FAV }
        } finally {
            val old = _state.value
            set {
                it.copy(
                    // 一家都没成功（或被打断）就保留原来的歌单，别清空
                    songs = songs, playlists = if (ok == 0) old.playlists else playlists, syncing = false,
                    syncedAt = if (ok > 0) System.currentTimeMillis() else old.syncedAt,
                    // 有一家成功就不打扰；全失败才把原因摆出来
                    syncMsg = if (ok == 0) errs.joinToString("\n") else "",
                    syncLog = log.toString(),
                )
            }
            index(songs)
            save()
        }
        fillCovers()
    }

    private fun songOf(songs: MutableMap<Long, Song>, item: MediaBrowser.MediaItem, src: String): Long? {
        val title = item.description.title?.toString()?.trim().orEmpty()
        val artist = Meta.subtitle(title, item.description.subtitle?.toString()?.trim().orEmpty())
        if (src == Ncm.PKG) {
            val id = Ncm.songIdOf(item.mediaId) ?: return null
            val t = title.ifEmpty { songs[id]?.title ?: "" }
            val a = artist.ifEmpty { songs[id]?.artist ?: "" }
            songs[id] = (songs[id] ?: Song(id, t, a)).copy(title = t, artist = a)
            return id
        }
        if (title.isEmpty()) return null
        val k = keyOf(title, artist)
        val id = keys[k] ?: localId(k)
        val old = songs[id]
        val song = old?.let { if (it.id < 0 && it.src == src) it.copy(mid = item.mediaId) else it }
            ?: Song(id, title, artist, src = src, mid = item.mediaId)
        songs[id] = song
        remember(song)
        return id
    }

    private suspend fun syncNcm(b: MediaBrowser, songs: MutableMap<Long, Song>, log: StringBuilder): List<Playlist>? {
        val root = all(b, b.root)
        val mine = root.firstOrNull { it.mediaId == "my_music" }
            ?: root.firstOrNull { it.description.title?.contains("我的") == true }
        if (mine == null) { log.append("【网易云】没给出「我的音乐」\n"); return null }
        val lists = all(b, mine.mediaId!!).filter { it.isBrowsable && it.mediaId != null }
        val out = ArrayList<Playlist>()
        lists.forEachIndexed { i, pl ->
            val name = pl.description.title?.toString() ?: "歌单"
            set { it.copy(syncMsg = "网易云 ${i + 1}/${lists.size} · $name") }
            val ids = ArrayList<Long>()
            for (item in all(b, pl.mediaId!!)) if (item.isPlayable) songOf(songs, item, Ncm.PKG)?.let { ids += it }
            // 车机接口每个歌单只给前 12 首：按歌单 ID 去网页版公开接口要整张
            val full = Regex("(\\d+)$").find(pl.mediaId!!)?.value?.let { Cloud.playlist(it) }
            if (full != null && full.size > ids.size) {
                ids.clear()
                for (x in full) {
                    val old = songs[x.id]
                    songs[x.id] = old?.copy(title = x.title.ifEmpty { old.title }, artist = x.artist.ifEmpty { old.artist }, pic = old.pic?.takeIf { it.isNotEmpty() } ?: x.pic) ?: x
                    ids += x.id
                }
            }
            // 还是不全时，保留以前从播放队列补全的那份
            val old = _state.value.playlists.firstOrNull { it.id == pl.mediaId }
            val keep = if (old != null && old.songIds.size > ids.size) old.songIds else ids
            out += Playlist(pl.mediaId!!, name, keep)
            log.append(name).append("：").append(ids.size).append(" 首").append(if (full != null && full.size >= ids.size) "（整张）" else "（车机接口，私密歌单只给前 12 首）").append(if (keep !== ids) "，保留补全的 ${keep.size} 首" else "").append('\n')
        }
        return out
    }

    // 自建曲库的歌：曲库里已有同名同歌手的（比如网易云同步来的）就记上微光能放的位置，没有就新建
    private fun itemSong(songs: MutableMap<Long, Song>, it: Item): Long {
        val k = keyOf(it.title, it.artist)
        val id = keys[k] ?: localId(k)
        val old = songs[id]
        val song = old?.copy(loc = it.loc, pic = old.pic?.takeIf { p -> p.isNotEmpty() } ?: it.cover)
            ?: Song(id, it.title, it.artist, pic = it.cover, src = Local.PKG, loc = it.loc)
        songs[id] = song
        remember(song)
        return id
    }

    // AI 作曲的歌放进「微光作曲」歌单
    const val GEN = "${Local.PKG}|gen"
    const val FAV = "${Local.PKG}|fav"

    // 微光自己的「我喜欢」：App 没有红心按钮（或是微光自己在放）时，红心记在这里
    fun isFav(id: Long?) = id != null && _state.value.playlists.firstOrNull { it.id == FAV }?.songIds?.contains(id) == true

    fun toggleFav(id: Long): Boolean {
        val s = _state.value
        val old = s.playlists.firstOrNull { it.id == FAV }?.songIds ?: emptyList()
        val on = id !in old
        val pl = Playlist(FAV, "我喜欢", if (on) listOf(id) + old else old - id)
        val rest = s.playlists.filter { it.id != FAV }
        _state.value = s.copy(playlists = if (pl.songIds.isEmpty()) rest else listOf(pl) + rest)
        save()
        return on
    }

    // 微光自己的音源（免费曲库）放之前先记进曲库，播放器报上来的 wg:<id> 才对得上
    fun addOwn(list: List<Song>) {
        val s = _state.value
        val fresh = list.filter { it.id !in s.songs }
        if (fresh.isEmpty()) return
        val songs = s.songs.toMutableMap()
        for (x in fresh) { songs[x.id] = x; remember(x) }
        _state.value = s.copy(songs = songs)
        save()
    }

    fun addGenerated(title: String, artist: String, file: String): Song {
        val k = keyOf(title, artist + file)
        val song = Song(localId(k), title, artist, src = Local.PKG, loc = "gen:$file", nid = 0)
        val s = _state.value
        val songs = s.songs.toMutableMap()
        songs[song.id] = song
        val old = s.playlists.firstOrNull { it.id == GEN }
        val pl = Playlist(GEN, "微光作曲", listOf(song.id) + (old?.songIds ?: emptyList()))
        _state.value = s.copy(songs = songs, playlists = listOf(pl) + s.playlists.filter { it.id != GEN })
        save()
        return song
    }

    private val mineWords = Regex("我的|收藏|喜欢|歌单|最近|下载|本地|红心|自建")

    // 别家的车机目录长什么样不知道：先找「我的/收藏/歌单」这类节点，往下看两层，有歌的节点就当一张歌单
    private suspend fun syncOther(b: MediaBrowser, p: Player, songs: MutableMap<Long, Song>, log: StringBuilder): List<Playlist>? {
        val root = all(b, b.root)
        if (root.isEmpty()) { log.append("【${p.short}】车机目录是空的\n"); return null }
        val out = ArrayList<Playlist>()
        fun add(id: String, name: String, items: List<MediaBrowser.MediaItem>) {
            val ids = items.filter { it.isPlayable }.mapNotNull { songOf(songs, it, p.pkg) }.distinct()
            if (ids.isEmpty() || out.any { it.id == "${p.pkg}|$id" }) return
            out += Playlist("${p.pkg}|$id", name, ids)
            log.append("【${p.short}】").append(name).append("：").append(ids.size).append(" 首\n")
        }
        add("root", "${p.short} · 首页", root)
        val tops = root.filter { it.isBrowsable && it.mediaId != null }
        val nodes = tops.filter { mineWords.containsMatchIn(it.description.title ?: "") }.ifEmpty { tops }.take(8)
        for ((i, n) in nodes.withIndex()) {
            val name = n.description.title?.toString() ?: "歌单"
            set { it.copy(syncMsg = "${p.short} ${i + 1}/${nodes.size} · $name") }
            val kids = all(b, n.mediaId!!)
            add(n.mediaId!!, name, kids)
            for (k in kids.filter { it.isBrowsable && it.mediaId != null }.take(20)) {
                if (out.size >= 40) break
                add(k.mediaId!!, k.description.title?.toString() ?: name, all(b, k.mediaId!!).take(500))
            }
        }
        if (out.isEmpty()) log.append("【${p.short}】目录里没找到歌\n")
        return out.takeIf { it.isNotEmpty() }
    }

    private inline fun set(f: (LibState) -> LibState) { _state.value = f(_state.value) }

    var connectError = ""
        private set

    // 连某个 App 的车机浏览服务（网易云要先给微光开通知使用权才肯连）
    suspend fun connect(ctx: Context, pkg: String): MediaBrowser? {
        val name = Players.nameOf(pkg)
        if (Players.version(ctx, pkg) == null) { connectError = "还没装$name"; return null }
        val svc = ctx.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo
        if (svc == null) { connectError = "这个版本的${name}没有开放车机接口，只能读正在播放"; return null }
        connectError = "${name}拒绝了连接。确认已给微光开「通知使用权」；小米手机还要在应用设置里允许微光「关联启动」"
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
        if (b == null && !refused) connectError = "连接${name}超时，稍后再试"
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

    // 车机服务常常先回一个空列表、联网拿到后再推一次更新，所以拿到第一批后多等一会儿收后续更新
    private suspend fun page(b: MediaBrowser, id: String, page: Int, size: Int): List<MediaBrowser.MediaItem>? =
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<List<MediaBrowser.MediaItem>?> { cont ->
                val opts = Bundle().apply {
                    putInt(MediaBrowser.EXTRA_PAGE, page)
                    putInt(MediaBrowser.EXTRA_PAGE_SIZE, size)
                }
                var best: List<MediaBrowser.MediaItem>? = null
                var finishing = false
                lateinit var cb: MediaBrowser.SubscriptionCallback
                fun finish() {
                    if (!cont.isActive) return
                    // 回调里直接退订可能打乱它的分发，挪到下一拍
                    main.post { try { b.unsubscribe(id, cb) } catch (_: Throwable) {} }
                    cont.resume(best)
                }
                fun got(list: List<MediaBrowser.MediaItem>) {
                    if (list.size >= (best?.size ?: -1)) best = list
                    if (finishing) return
                    finishing = true
                    main.postDelayed({ finish() }, if (list.isEmpty()) 3500L else 1500L)
                }
                cb = object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) = got(children)
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>, options: Bundle) = got(children)
                    override fun onError(parentId: String) = finish()
                    override fun onError(parentId: String, options: Bundle) = finish()
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
                    val v = if (s.has("v")) s.optDouble("v").toFloat() else null
                    val e = if (s.has("e")) s.optDouble("e").toFloat() else null
                    songs[id] = Song(
                        id, s.optString("t"), s.optString("a"), v, e, if (s.has("p")) s.optString("p") else null,
                        src = if (s.has("s")) s.optString("s") else null,
                        nid = if (s.has("n")) s.optLong("n") else null,
                        mid = if (s.has("d")) s.optString("d") else null,
                        m = if (s.has("m")) s.optDouble("m").toFloat() else null,
                        loc = if (s.has("l")) s.optString("l") else null,
                    )
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
            val plays = ArrayList<Play>()
            o.optJSONArray("plays")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONArray(i)
                    plays += Play(p.getLong(0), p.getLong(1))
                }
            }
            // 旧版的脏记录：汽水「歌词当歌名」丢掉，歌手栏里的「 - 专辑」去掉
            val junk = songs.values.filter { Meta.junk(it) }.map { it.id }.toSet()
            junk.forEach { songs.remove(it) }
            for ((id, s) in songs.entries.toList()) songs[id] = Meta.fixArtist(s)
            if (junk.isNotEmpty()) {
                history.removeAll { it.id in junk }
                plays.removeAll { it.id in junk }
                for (i in playlists.indices) playlists[i] = playlists[i].copy(songIds = playlists[i].songIds.filter { it !in junk })
            }
            _state.value = LibState(songs, playlists, history, plays, o.optLong("syncedAt"))
            index(songs)
            rankAll(songs)
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
            for (song in s.songs.values) put(JSONObject().put("id", song.id).put("t", song.title).put("a", song.artist).apply {
                if (song.v != null && song.e != null) { put("v", song.v.toDouble()); put("e", song.e.toDouble()) }
                if (song.pic != null) put("p", song.pic)
                if (song.src != null) put("s", song.src)
                if (song.nid != null) put("n", song.nid)
                if (song.mid != null) put("d", song.mid)
                if (song.m != null) put("m", song.m.toDouble())
                if (song.loc != null) put("l", song.loc)
            })
        })
        o.put("playlists", JSONArray().apply {
            for (p in s.playlists) put(JSONObject().put("id", p.id).put("n", p.name).put("s", JSONArray(p.songIds)))
        })
        o.put("history", JSONArray().apply {
            for (h in s.history) put(JSONObject().put("id", h.id).put("at", h.at).put("n", h.count))
        })
        o.put("plays", JSONArray().apply { for (p in s.plays) put(JSONArray().put(p.id).put(p.at)) })
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
