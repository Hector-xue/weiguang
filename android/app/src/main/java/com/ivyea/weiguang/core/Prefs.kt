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
    var weather: Boolean
        get() = sp?.getBoolean("weather", false) ?: false
        set(v) { sp?.edit()?.putBoolean("weather", v)?.apply() }
}
