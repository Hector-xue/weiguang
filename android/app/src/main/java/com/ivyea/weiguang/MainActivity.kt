package com.ivyea.weiguang

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
        com.ivyea.weiguang.core.Sources.init(filesDir)
        if (com.ivyea.weiguang.core.Sources.servers.isEmpty()) com.ivyea.weiguang.core.Sources.load()
        com.ivyea.weiguang.core.Art.init(this)
        setContent { WeiguangTheme { App() } }
        if (savedInstanceState == null) {
            NowPlaying.attach(this)
            com.ivyea.weiguang.core.Players.autoStart(this)
        }
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
