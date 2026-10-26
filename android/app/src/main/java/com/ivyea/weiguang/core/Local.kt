package com.ivyea.weiguang.core

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.ivyea.weiguang.service.WgPlayer

// 指挥微光自己的播放器。歌要有 loc（自建曲库里的条目或 AI 作曲的文件）
object Local {
    const val PKG = "com.ivyea.weiguang"

    private var future: ListenableFuture<MediaController>? = null

    private fun with(ctx: Context, f: (MediaController) -> Unit) {
        val app = ctx.applicationContext
        val fut = future ?: MediaController.Builder(app, SessionToken(app, ComponentName(app, WgPlayer::class.java))).buildAsync().also { future = it }
        fut.addListener({
            try { f(fut.get()) } catch (_: Throwable) {
                future = null
                Toast.makeText(app, "微光的播放器没起来，再试一次", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(app))
    }

    fun item(s: Song): MediaItem? {
        val uri = s.loc?.let { Sources.uriOf(it) } ?: return null
        return MediaItem.Builder()
            .setMediaId(if (s.loc?.startsWith("url:") == true) "radio:${s.id}" else "wg:${s.id}")
            .setUri(uri)
            .apply { if (uri.toString().contains(".m3u8")) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist)
                    .setArtworkUri(s.pic?.takeIf { it.startsWith("http") }?.let { Uri.parse(it) }).build(),
            )
            .build()
    }

    // 放一串（自建曲库的歌单直接整串交给播放器，无缝接着放）
    fun play(ctx: Context, songs: List<Song>, start: Int = 0): Boolean {
        val items = songs.mapNotNull { item(it) }
        if (items.isEmpty()) return false
        with(ctx) { c ->
            c.setMediaItems(items, start.coerceIn(0, items.size - 1), 0)
            c.prepare()
            c.play()
        }
        return true
    }
}
