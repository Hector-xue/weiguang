package com.ivyea.weiguang.core

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ivyea.weiguang.service.NowListener
import com.ivyea.weiguang.service.NowWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Now(
    val pkg: String,
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val updatedAt: Long,
    val speed: Float,
    val playing: Boolean,
    // 曲库里的 ID（网易云=歌曲 ID；别的 App=本机算的负数 ID）；nid=对上的网易云 ID，拿整首歌词用
    val songId: Long?,
    val nid: Long?,
    val lyric: String?,
    val likeIcon: Bitmap?,
    val hasLike: Boolean,
) {
    // 会话只在状态变化时报进度，中间按时间推算
    fun positionNow(): Long {
        if (!playing) return positionMs
        val p = positionMs + ((SystemClock.elapsedRealtime() - updatedAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

// 读音乐 App 的播放会话（网易云、QQ音乐、酷狗、酷我、汽水…）。要通知使用权，否则一切为空
object NowPlaying {
    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var msm: MediaSessionManager? = null
    private var ctrl: MediaController? = null
    private var all: List<MediaController> = emptyList()
    private val icons = HashMap<String, Bitmap?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val resolving = HashSet<Long>()

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list ?: emptyList()) }

    // 别的会话开始播放时也要重新挑（比如从网易云换到 QQ音乐）
    private val watchers = HashMap<MediaSession.Token, MediaController.Callback>()

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
        override fun onPlaybackStateChanged(state: PlaybackState?) = emit()
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = absorb()
        override fun onSessionDestroyed() {
            ctrl?.unregisterCallback(this)
            ctrl = null
            refresh()
        }
    }

    fun attach(ctx: Context) {
        val c = ctx.applicationContext
        app = c
        if (msm != null) { refresh(); return }
        val m = c.getSystemService(MediaSessionManager::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessions, comp(c), main)
            msm = m
            refresh()
        } catch (_: SecurityException) {
            // 还没给通知使用权
        }
    }

    fun refresh() {
        val m = msm ?: return
        val c = app ?: return
        try { pick(m.getActiveSessions(comp(c))) } catch (_: SecurityException) {}
    }

    private fun comp(c: Context) = ComponentName(c, NowListener::class.java)

    fun controllerOf(pkg: String): MediaController? = all.firstOrNull { it.packageName == pkg }

    private fun watch(list: List<MediaController>) {
        val live = list.map { it.sessionToken }.toSet()
        watchers.keys.filter { it !in live }.forEach { watchers.remove(it) }
        for (c in list) {
            if (c.sessionToken in watchers) continue
            val w = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == PlaybackState.STATE_PLAYING && c.sessionToken != ctrl?.sessionToken) pick(all)
                }
            }
            try { c.registerCallback(w, main); watchers[c.sessionToken] = w } catch (_: Throwable) {}
        }
    }

    // 正在放的音乐 App > 刚才跟着的那个 > 上次放过的 > 任何音乐 App > 任何在放的
    private fun pick(list: List<MediaController>) {
        all = list
        watch(list)
        fun playing(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val music = list.filter { Players.isKnown(it.packageName) }
        val best = music.firstOrNull { playing(it) }
            ?: music.firstOrNull { it.sessionToken == ctrl?.sessionToken }
            ?: music.firstOrNull { it.packageName == Prefs.lastPlayer }
            ?: music.firstOrNull()
            ?: list.firstOrNull { playing(it) }
        if (best?.sessionToken != ctrl?.sessionToken) {
            ctrl?.unregisterCallback(cb)
            ctrl = best
            best?.registerCallback(cb, main)
            absorb()
        }
        emit()
    }

    private fun absorb() {
        val c = ctrl ?: return
        val q = c.queue ?: return
        val pkg = c.packageName
        if (!Players.isKnown(pkg)) return
        val items = q.mapNotNull { qi ->
            val d = qi.description
            val t = d.title?.toString()?.trim() ?: ""
            val a = Meta.subtitle(t, d.subtitle?.toString()?.trim() ?: "")
            val id = Library.idOf(pkg, d.mediaId, t, a) ?: return@mapNotNull null
            Triple(id, t, a)
        }
        Library.absorbQueue(c.queueTitle?.toString(), items, pkg.takeIf { it != Ncm.PKG })
    }

    // 网易云的红心叫 like / *.STAR；别家按名字猜（不碰「收藏到歌单」）
    private val likeWords = Regex("like|love|favou?rite|heart|喜欢|红心", RegexOption.IGNORE_CASE)

    private fun likeAction(c: MediaController): PlaybackState.CustomAction? {
        val acts = c.playbackState?.customActions ?: return null
        return acts.firstOrNull { it.name.toString() == "like" || it.action.endsWith(".STAR") }
            ?: acts.firstOrNull { (likeWords.containsMatchIn(it.name) || likeWords.containsMatchIn(it.action)) && !it.name.contains("收藏") }
    }

    private fun emit() {
        val c = ctrl
        val m = c?.metadata
        val st = c?.playbackState
        if (c == null || (m == null && st == null)) { _now.value = null; return }
        val like = likeAction(c)
        val dur = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val clean = Meta.live(c.packageName, m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "", m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "", dur)
        val title = clean.title
        val artist = clean.artist
        val music = Players.isKnown(c.packageName)
        val songId = if (music) Library.idOf(c.packageName, m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), title, artist) else null
        val nid = when {
            songId == null -> null
            songId > 0 -> songId
            else -> Library.state.value.songs[songId]?.nid?.takeIf { it > 0 }
        }
        val updated = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        val n = Now(
            pkg = c.packageName,
            title = title,
            artist = artist,
            album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            durationMs = dur,
            positionMs = st?.position ?: 0L,
            updatedAt = updated,
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            songId = songId,
            nid = nid,
            lyric = m?.getString("ucar.media.metadata.LYRICS_LINE")?.trim()?.takeIf { it.isNotEmpty() } ?: clean.lyric,
            likeIcon = like?.let { icon(c.packageName, it.icon) },
            hasLike = like != null,
        )
        val prev = _now.value
        _now.value = n
        if (n.songId != null && n.title.isNotEmpty()) {
            Library.recordPlay(n.songId, n.title, n.artist, n.pkg)
            if (n.playing) Prefs.lastPlayer = n.pkg
            if (n.nid == null) resolve(n.songId, n.title, n.artist, n.durationMs)
        }
        app?.let { if (prev?.title != n.title || prev.playing != n.playing || prev.art !== n.art || prev.pkg != n.pkg) NowWidget.refresh(it) }
    }

    // 别的 App 在放的歌：去网易云对上 ID，拿到后重新发一遍（播放页就能显示整首歌词）
    private fun resolve(id: Long, title: String, artist: String, dur: Long) {
        if (id > 0 || !resolving.add(id)) return
        if (Library.state.value.songs[id]?.nid == 0L) return
        scope.launch {
            val hit = Cloud.match(title, artist, dur)
            if (hit == null) { resolving.remove(id); return@launch }
            Library.setMatch(id, hit)
            if (hit.id > 0) emit()
        }
    }

    // 红心图标直接画那个 App 自己的（喜欢/没喜欢是两张图），不用猜状态
    private fun icon(pkg: String, id: Int): Bitmap? = icons.getOrPut("$pkg:$id") {
        try {
            val d = app!!.packageManager.getResourcesForApplication(pkg).getDrawable(id, null)
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96)
            d.draw(Canvas(b))
            b
        } catch (_: Throwable) { null }
    }

    fun toggle() {
        val c = ctrl ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { ctrl?.transportControls?.skipToNext() }
    fun prev() { ctrl?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { ctrl?.transportControls?.seekTo(ms) }

    fun like() {
        val c = ctrl ?: return
        val a = likeAction(c) ?: return
        c.transportControls.sendCustomAction(a.action, Bundle())
    }
}
