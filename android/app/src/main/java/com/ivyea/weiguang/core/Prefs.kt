package com.ivyea.weiguang.core

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("wg", Context.MODE_PRIVATE)
    }

    // 粒子跟着真实声音跳（要录音权限）；关掉就按节奏模拟
    var spectrum: Boolean
        get() = sp?.getBoolean("spectrum", true) ?: true
        set(v) { sp?.edit()?.putBoolean("spectrum", v)?.apply() }

    // 悬浮光球；默认关，用户在设置里开
    var orb: Boolean
        get() = sp?.getBoolean("orb", false) ?: false
        set(v) { sp?.edit()?.putBoolean("orb", v)?.apply() }

    // AI：兼容 OpenAI 格式的接口，密钥只存本机
    var aiBase: String
        get() = sp?.getString("aiBase", "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1"
        set(v) { sp?.edit()?.putString("aiBase", v.trim())?.apply() }

    var aiKey: String
        get() = sp?.getString("aiKey", "") ?: ""
        set(v) { sp?.edit()?.putString("aiKey", v.trim())?.apply() }

    var aiModel: String
        get() = sp?.getString("aiModel", "deepseek-chat") ?: "deepseek-chat"
        set(v) { sp?.edit()?.putString("aiModel", v.trim())?.apply() }

    var orbX: Int
        get() = sp?.getInt("orbX", -1) ?: -1
        set(v) { sp?.edit()?.putInt("orbX", v)?.apply() }

    var orbY: Int
        get() = sp?.getInt("orbY", -1) ?: -1
        set(v) { sp?.edit()?.putInt("orbY", v)?.apply() }

    // 点歌用哪个 App；空=自动（正在放的 / 上次放的）
    var player: String
        get() = sp?.getString("player", "") ?: ""
        set(v) { sp?.edit()?.putString("player", v)?.apply() }

    var lastPlayer: String
        get() = sp?.getString("lastPlayer", "") ?: ""
        set(v) { if (v != lastPlayer) sp?.edit()?.putString("lastPlayer", v)?.apply() }

    // 每个 App 上次接住点歌的方式（session / mid / intent / none）
    fun how(pkg: String): String = sp?.getString("how:$pkg", "") ?: ""
    fun setHow(pkg: String, m: String) { sp?.edit()?.putString("how:$pkg", m)?.apply() }

    // AI 伙伴的性格，见 Buddy.personas
    var persona: Int
        get() = sp?.getInt("persona", 0) ?: 0
        set(v) { sp?.edit()?.putInt("persona", v)?.apply() }

    // 参考天气（要大致位置权限）；默认关
    // 打开微光就开始放（没在放的话）：接着放正在用的 App，没有 App 就放微光自己的电台
    var radioFav: String
        get() = sp?.getString("radioFav", "[]") ?: "[]"
        set(v) { sp?.edit()?.putString("radioFav", v)?.apply() }

    // 只听中文歌：对话里说过「听不懂英文歌」之类就记下，挑歌、电台都只挑中文
    var zhOnly: Boolean
        get() = sp?.getBoolean("zhOnly", false) ?: false
        set(v) { sp?.edit()?.putBoolean("zhOnly", v)?.apply() }

    // 每晚一首：默认关，首页问过一次或在设置里开；时间按分钟存（默认 22:00）；nightlyLast=上次发的那天，一天只发一条
    var nightly: Boolean
        get() = sp?.getBoolean("nightly", false) ?: false
        set(v) { sp?.edit()?.putBoolean("nightly", v)?.apply() }
    var nightlyAt: Int
        get() = sp?.getInt("nightlyAt", 22 * 60) ?: (22 * 60)
        set(v) { sp?.edit()?.putInt("nightlyAt", v)?.apply() }
    var nightlyLast: Int
        get() = sp?.getInt("nightlyLast", 0) ?: 0
        set(v) { sp?.edit()?.putInt("nightlyLast", v)?.apply() }
    var nightlyAsked: Boolean
        get() = sp?.getBoolean("nightlyAsked", false) ?: false
        set(v) { sp?.edit()?.putBoolean("nightlyAsked", v)?.apply() }

    var autoplay: Boolean
        get() = sp?.getBoolean("autoplay", true) ?: true
        set(v) { sp?.edit()?.putBoolean("autoplay", v)?.apply() }

    // 这个 App 把歌词塞进歌名（见 Meta）
    fun lyricMode(pkg: String) = sp?.getBoolean("lyric:$pkg", false) ?: false
    fun setLyricMode(pkg: String) { if (!lyricMode(pkg)) sp?.edit()?.putBoolean("lyric:$pkg", true)?.apply() }

    var weather: Boolean
        get() = sp?.getBoolean("weather", false) ?: false
        set(v) { sp?.edit()?.putBoolean("weather", v)?.apply() }

    // 自己的曲库服务器（JSON，见 Sources）
    var servers: String
        get() = sp?.getString("servers", "[]") ?: "[]"
        set(v) { sp?.edit()?.putString("servers", v)?.apply() }

    // 哼唱识别：ACRCloud 项目的地址和密钥
    var acrHost: String
        get() = sp?.getString("acrHost", "") ?: ""
        set(v) { sp?.edit()?.putString("acrHost", v.trim())?.apply() }
    var acrKey: String
        get() = sp?.getString("acrKey", "") ?: ""
        set(v) { sp?.edit()?.putString("acrKey", v.trim())?.apply() }
    var acrSecret: String
        get() = sp?.getString("acrSecret", "") ?: ""
        set(v) { sp?.edit()?.putString("acrSecret", v.trim())?.apply() }

    // AI 作曲：服务商（elevenlabs / minimax）、地址、密钥、模型
    var genKind: String
        get() = sp?.getString("genKind", "elevenlabs") ?: "elevenlabs"
        set(v) { sp?.edit()?.putString("genKind", v)?.apply() }
    var genBase: String
        get() = sp?.getString("genBase", "https://api.elevenlabs.io") ?: "https://api.elevenlabs.io"
        set(v) { sp?.edit()?.putString("genBase", v.trim())?.apply() }
    var genKey: String
        get() = sp?.getString("genKey", "") ?: ""
        set(v) { sp?.edit()?.putString("genKey", v.trim())?.apply() }
    var genModel: String
        get() = sp?.getString("genModel", "music_v1") ?: "music_v1"
        set(v) { sp?.edit()?.putString("genModel", v.trim())?.apply() }
}
