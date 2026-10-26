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
import com.ivyea.weiguang.core.LibState
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.Now
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

    Shell(tab, listenerOn, now, lib, Mood(a, b, c, accent), time) { tab = it }
}

// 整个界面（背景 + 当前页 + 底栏）；截图测试直接渲染它
@Composable
internal fun Shell(tab: Int, listenerOn: Boolean, now: Now?, lib: LibState, m: Mood, time: State<Float>, onTab: (Int) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Backdrop(m, time, Modifier.fillMaxSize())
        Dust(Modifier.fillMaxSize(), time, count = 28)
        if (!listenerOn) {
            Onboarding(time)
            return@Box
        }
        Crossfade(targetState = tab, animationSpec = tween(260), label = "tab") { t ->
            when (t) {
                0 -> MomentScreen(now, m.accent, time, onOpenPlayer = { onTab(1) }, onOpenSettings = { onTab(3) })
                1 -> NowScreen(now, m, time)
                2 -> LibraryScreen(lib, now, m.accent, time)
                else -> SettingsPage()
            }
        }
        Dock(tab, Modifier.align(Alignment.BottomCenter), onTab)
    }
}

// 底栏高度（不含系统导航条）；各页底部按它留白
internal val DockH = 54.dp

// 底栏：不再是一颗胶囊，只是贴底的三个字，背后一层从透明到纸白的渐隐，内容滑到这里慢慢淡掉。
// 设置不常用，不占底栏，从「此刻」右上角的 ··· 进
@Composable
private fun Dock(tab: Int, modifier: Modifier, onTab: (Int) -> Unit) {
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Wg.paper.copy(alpha = 0f), 0.45f to Wg.paper.copy(alpha = 0.82f), 1f to Wg.paper.copy(alpha = 0.95f)))
            .navigationBarsPadding().height(DockH),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            listOf("此刻", "播放", "曲库").forEachIndexed { i, label ->
                val sel = i == tab
                Column(
                    Modifier.clip(RoundedCornerShape(999.dp)).clickable { onTab(i) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, fontSize = 14.5.sp, color = if (sel) Wg.ink else Wg.ink3, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    Box(
                        Modifier.padding(top = 4.dp).size(4.dp).clip(CircleShape)
                            .background(if (sel) Wg.warm else Color.Transparent),
                    )
                }
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
        Text("微光", fontFamily = Wg.serif, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
        Text("懂你此刻的听歌伙伴", fontSize = 16.sp, color = Wg.ink2, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(32.dp))
        Step(
            "1", "读取正在播放",
            "开「通知使用权」，微光才知道在放什么。只读播放信息。",
            done = false, action = "去开启",
        ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Spacer(Modifier.height(12.dp))
        Step(
            "2", if (apps.isNotEmpty()) "音乐 App 已就绪" else "不装音乐 App 也行",
            if (apps.isNotEmpty()) apps.map { it.short }.distinct().joinToString("、").let { "$it，都能陪着听" }
            else "微光自带免费曲库和电台；装了网易云能同步你的歌单",
            done = apps.isNotEmpty(), action = if (apps.isNotEmpty()) "" else "去下载",
        ) { Players.openStore(ctx) }
        Text(
            "粒子频谱、悬浮光球在设置里",
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
