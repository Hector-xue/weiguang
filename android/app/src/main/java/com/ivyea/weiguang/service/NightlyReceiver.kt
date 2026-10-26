package com.ivyea.weiguang.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ivyea.weiguang.core.Nightly
import com.ivyea.weiguang.core.Prefs

// 每晚一首的闹钟到点；开机、改时间、升级后重新排闹钟（系统会清掉）
class NightlyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        if (intent.action == FIRE) Nightly.fire(ctx) else Nightly.schedule(ctx)
    }

    companion object { const val FIRE = "com.ivyea.weiguang.NIGHTLY" }
}
