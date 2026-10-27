package com.ivyea.weiguang.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ivyea.weiguang.core.Nightly
import com.ivyea.weiguang.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// 每晚一首的闹钟到点；开机、改时间、升级后重新排闹钟（系统会清掉）
class NightlyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        if (intent.action != FIRE) { Nightly.schedule(ctx); return }
        // 要读曲库、取封面，挪到后台线程；系统给接收器的时间有限，整体 9 秒内收尾
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeoutOrNull(9000) { Nightly.fire(ctx.applicationContext) } } finally { done.finish() }
        }
    }

    companion object { const val FIRE = "com.ivyea.weiguang.NIGHTLY" }
}
