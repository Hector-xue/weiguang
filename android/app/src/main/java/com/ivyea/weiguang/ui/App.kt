package com.ivyea.weiguang.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.Buddy
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.NowPlaying
import com.ivyea.weiguang.core.Players
import com.ivyea.weiguang.core.Weather
import com.ivyea.weiguang.service.NowWidget

private const val AUTO_SYNC_MS = 12L * 3600 * 1000

@Composable
fun App() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val listenerOn = remember(tick) { listenerEnabled(ctx) }
    val now by NowPlaying.now.collectAsState()
    val lib by Library.state.collectAsState()
    val time = rememberFrameTime()

    var mood by remember { mutableStateOf(DefaultMood) }
    LaunchedEffect(now?.art) {
        val art = now?.art
        mood = if (art != null) moodOf(art) else DefaultMood
    }
    val a by animateColorAsState(mood.a, tween(900), label = "a")
    val b by animateColorAsState(mood.b, tween(900), label = "b")
    val c by animateColorAsState(mood.c, tween(900), label = "c")
    val accent by animateColorAsState(mood.accent, tween(900), label = "accent")

    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 开了通知使用权就接上播放会话；曲库超过 12 小时没同步就自动同步一次
    LaunchedEffect(listenerOn) {
        if (!listenerOn) return@LaunchedEffect
        NowPlaying.attach(ctx)
        if ((Players.installed(ctx).isNotEmpty() || com.ivyea.weiguang.core.Sources.servers.isNotEmpty()) && System.currentTimeMillis() - Library.state.value.syncedAt > AUTO_SYNC_MS) Library.syncAsync(ctx)
        else Library.fillCovers()
    }

    // 换歌、换时段时更新「微光在想」（先看一眼天气）；连播常驻；小组件跟着心里话更新
    LaunchedEffect(listenerOn, now?.songId, tick) {
        if (!listenerOn) return@LaunchedEffect
        Weather.refresh(ctx)
        Buddy.think()
    }
    val thought by Buddy.thought.collectAsState()
    LaunchedEffect(thought) { NowWidget.refresh(ctx.applicationContext) }
    LaunchedEffect(Unit) { Buddy.runQueue(ctx.applicationContext) }

    BackHandler(enabled = tab != 0) { tab = 0 }

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(a, b, c), start = Offset.Zero, end = Offset.Infinite))) {
        Dust(Modifier.fillMaxSize(), time)
        if (!listenerOn) {
            Onboarding(time)
            return@Box
        }
        val m = Mood(a, b, c, accent)
        Crossfade(targetState = tab, animationSpec = tween(260), label = "tab") { t ->
            when (t) {
                0 -> MomentScreen(now, accent, time, onOpenPlayer = { tab = 1 }, onOpenSettings = { tab = 3 })
                1 -> NowScreen(now, m, time)
                2 -> LibraryScreen(lib, now, accent, time)
                else -> SettingsPage()
            }
        }
        Dock(tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
    }
}

@Composable
private fun Dock(tab: Int, modifier: Modifier, onTab: (Int) -> Unit) {
    Row(modifier.navigationBarsPadding().padding(bottom = 14.dp).glass(999.dp, 14.dp).padding(5.dp)) {
        listOf("此刻", "播放", "曲库", "设置").forEachIndexed { i, label ->
            val sel = i == tab
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) Color.White else Color.Transparent)
                    .clickable { onTab(i) }.padding(horizontal = 19.dp, vertical = 11.dp),
            ) {
                Text(label, fontSize = 14.sp, color = if (sel) Wg.ink else Wg.ink2, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// 首次打开：只有通知使用权是必需的；录音、悬浮窗都放到设置里随时开
@Composable
private fun Onboarding(time: State<Float>) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val apps = remember(tick) { Players.installed(ctx) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ParticleOrb(Modifier.fillMaxWidth().height(300.dp), time, { 0.5f })
        Text("微光", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
        Text("懂你此刻的听歌伙伴", fontSize = 16.sp, color = Wg.ink2, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(32.dp))
        Step(
            "1", "读取正在播放",
            "打开「通知使用权」，微光才知道音乐 App 在放什么。只读播放信息，不看你的通知内容。",
            done = false, action = "去开启",
        ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Spacer(Modifier.height(12.dp))
        Step(
            "2", if (apps.isNotEmpty()) "音乐 App 已就绪" else "还没装音乐 App",
            if (apps.isNotEmpty()) apps.map { it.short }.distinct().joinToString("、").let { "$it。网易云能读歌单、按歌点播，其他 App 读播放和控制" }
            else "网易云、QQ音乐、酷狗、酷我、汽水都行；网易云配合得最好",
            done = apps.isNotEmpty(), action = if (apps.isNotEmpty()) "" else "去下载",
        ) { Players.openStore(ctx) }
        Text(
            "粒子频谱和悬浮光球在设置里，随时可以开。",
            fontSize = 12.sp, color = Wg.ink3, modifier = Modifier.padding(top = 20.dp, bottom = 32.dp),
        )
    }
}

@Composable
private fun Step(n: String, title: String, desc: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(22.dp, 6.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (done) Wg.ok.copy(alpha = 0.18f) else Wg.warm.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (done) "✓" else n, color = if (done) Wg.ok else Wg.warm, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
            Text(desc, fontSize = 12.5.sp, color = Wg.ink2, modifier = Modifier.padding(top = 4.dp), lineHeight = 18.sp)
        }
        if (action.isNotEmpty()) {
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(Brush.linearGradient(listOf(Color(0xFFF6AB86), Wg.warm)))
                    .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(action, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
