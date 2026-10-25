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
    val songId: Long?,
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

// 读别的 App 的播放会话（优先网易云）。要通知使用权，否则一切为空
object NowPlaying {
    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var msm: MediaSessionManager? = null
    private var ctrl: MediaController? = null
    private val icons = HashMap<String, Bitmap?>()

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list ?: emptyList()) }

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

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.packageName == Ncm.PKG }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
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
        val items = q.mapNotNull { qi ->
            val d = qi.description
            val id = Ncm.songIdOf(d.mediaId) ?: return@mapNotNull null
            Triple(id, d.title?.toString() ?: "", d.subtitle?.toString() ?: "")
        }
        Library.absorbQueue(c.queueTitle?.toString(), items)
    }

    private fun likeAction(c: MediaController) = c.playbackState?.customActions?.firstOrNull {
        it.name.toString() == "like" || it.action.endsWith(".STAR")
    }

    private fun emit() {
        val c = ctrl
        val m = c?.metadata
        val st = c?.playbackState
        if (c == null || (m == null && st == null)) { _now.value = null; return }
        val like = likeAction(c)
        val updated = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        val n = Now(
            pkg = c.packageName,
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = st?.position ?: 0L,
            updatedAt = updated,
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            songId = Ncm.songIdOf(m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)),
            lyric = m?.getString("ucar.media.metadata.LYRICS_LINE")?.trim()?.takeIf { it.isNotEmpty() },
            likeIcon = like?.let { icon(c.packageName, it.icon) },
            hasLike = like != null,
        )
        _now.value = n
        if (n.songId != null && n.title.isNotEmpty()) Library.recordPlay(n.songId, n.title, n.artist)
    }

    // 红心图标直接画网易云自己的（喜欢/没喜欢是两张图），不用猜状态
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
