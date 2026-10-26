package com.ivyea.weiguang

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.NowPlaying
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Spectrum
import com.ivyea.weiguang.service.NowListener
import com.ivyea.weiguang.ui.App
import com.ivyea.weiguang.ui.WeiguangTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        Prefs.init(this)
        Library.init(this)
        com.ivyea.weiguang.core.Diary.init(this)
        com.ivyea.weiguang.core.Buddy.init(this)
        com.ivyea.weiguang.core.Sources.init(filesDir)
        if (com.ivyea.weiguang.core.Sources.servers.isEmpty()) com.ivyea.weiguang.core.Sources.load()
        com.ivyea.weiguang.core.Art.init(this)
        setContent { WeiguangTheme { App() } }
        com.ivyea.weiguang.core.Nightly.schedule(this)
        if (savedInstanceState == null) {
            handle(intent)
            NowPlaying.attach(this)
            // 从通知点「放这首」进来就放那首，不再自动接着放
            if (com.ivyea.weiguang.ui.listenerEnabled(this) && !opened) com.ivyea.weiguang.core.Players.autoStart(this)
        }
    }

    private var opened = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // 通知点进来：wg_open=recap_week / recap_month / moment / play:<歌曲ID>
    private fun handle(i: Intent?) {
        val what = i?.getStringExtra(com.ivyea.weiguang.core.Nightly.EXTRA) ?: return
        i.removeExtra(com.ivyea.weiguang.core.Nightly.EXTRA)
        getSystemService(android.app.NotificationManager::class.java)?.cancel(com.ivyea.weiguang.core.Nightly.ID)
        if (what.startsWith("play:")) {
            val s = what.removePrefix("play:").toLongOrNull()?.let { Library.state.value.songs[it] } ?: return
            opened = true
            com.ivyea.weiguang.core.Players.play(this, s)
        } else com.ivyea.weiguang.ui.Nav.go(what)
    }

    override fun onResume() {
        super.onResume()
        NowPlaying.attach(this)
        // 频谱只在界面看得见时开，省电
        if (Prefs.spectrum) Spectrum.start(this)
        NowListener.instance?.syncOrb()
    }

    override fun onPause() {
        Spectrum.stop()
        super.onPause()
    }
}
