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

    var orbX: Int
        get() = sp?.getInt("orbX", -1) ?: -1
        set(v) { sp?.edit()?.putInt("orbX", v)?.apply() }

    var orbY: Int
        get() = sp?.getInt("orbY", -1) ?: -1
        set(v) { sp?.edit()?.putInt("orbY", v)?.apply() }
}
