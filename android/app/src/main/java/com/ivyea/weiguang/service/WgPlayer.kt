package com.ivyea.weiguang.service

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ivyea.weiguang.MainActivity
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Sources

// 微光自己的播放器：放自建曲库（Navidrome/Jellyfin/WebDAV）和 AI 作曲的歌。
// 系统播放会话、通知栏、耳机线控都由 Media3 管；微光读它和读别的 App 一样
class WgPlayer : MediaSessionService() {
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Sources.init(filesDir)
        if (Sources.servers.isEmpty()) Sources.load()
        // WebDAV 的地址要带账号，按地址前缀补上 Authorization
        val http = DefaultHttpDataSource.Factory().setUserAgent("Weiguang").setAllowCrossProtocolRedirects(true)
        val authed = ResolvingDataSource.Factory(http) { spec ->
            Sources.authFor(spec.uri)?.let { spec.withAdditionalHeaders(mapOf("Authorization" to it)) } ?: spec
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, authed)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // 划掉微光时，没在放就顺手停掉
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
