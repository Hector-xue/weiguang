package com.ivyea.weiguang.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ivyea.weiguang.service.NowListener

// 每次回到前台 +1：从系统设置页回来后重新检查权限
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

// 必须比到具体组件：验证工具的包名 com.ivyea.weiguang.probe 含本包名，按字符串包含会误判成"已开"
fun listenerEnabled(ctx: Context): Boolean {
    val me = ComponentName(ctx, NowListener::class.java)
    val raw = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
    return raw.split(':').any { ComponentName.unflattenFromString(it) == me }
}
