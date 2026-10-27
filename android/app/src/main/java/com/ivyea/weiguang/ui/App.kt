package com.ivyea.weiguang.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
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
    // 听歌人格小组件：每多 5 次播放刷一次
    LaunchedEffect(lib.plays.size / 5) { com.ivyea.weiguang.service.RecapWidget.refresh(ctx.applicationContext) }
    LaunchedEffect(Unit) { Buddy.runQueue(ctx.applicationContext) }

    // 从通知点进来：都先回「此刻」，回顾浮层由此刻页接着打开
    val nav by Nav.target.collectAsState()
    LaunchedEffect(nav) { if (nav != null) { tab = 0; if (nav == "moment") Nav.done() } }

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
                else -> SettingsPage(onBack = { onTab(0) })
            }
        }
        // 设置页是从 ··· 进去的二级页，不显示底栏，左上角返回
        androidx.compose.animation.AnimatedVisibility(
            tab != 3, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)), exit = fadeOut(tween(160)),
        ) { Dock(tab, m.accent, Modifier, onTab) }
    }
}

// 底栏高度（不含系统导航条）；各页底部按它留白
internal val DockH = 58.dp

private val tabs = listOf(G.Spark to "此刻", G.Disc to "播放", G.Stack to "曲库")

// 底栏：没有胶囊、没有底板，只有三枚细线图标。选中的那枚身后亮着一团「微光」（跟着封面换色），
// 换页时光晕弹性地滑过去，名字在光里浮出来；背后一层纸白渐隐，让内容滑到这里慢慢淡掉。
// 设置不常用，不占底栏，从「此刻」右上角的 ··· 进
@Composable
private fun Dock(tab: Int, accent: Color, modifier: Modifier, onTab: (Int) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val slot = 76.dp
    val x by animateDpAsState(slot * tab, spring(dampingRatio = 0.62f, stiffness = 320f), label = "glow")
    Box(
        modifier.fillMaxWidth()
            // 渐隐从图标上方 26dp 就开始，到图标这一带已经几乎是纸白：封面滑到底下也不会透出来跟图标打架
            .background(Brush.verticalGradient(0f to Wg.paper.copy(alpha = 0f), 0.3f to Wg.paper.copy(alpha = 0.88f), 0.55f to Wg.paper.copy(alpha = 0.97f), 1f to Wg.paper))
            .padding(top = 26.dp).navigationBarsPadding().height(DockH),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(slot * tabs.size).fillMaxHeight()) {
            // 光晕：一团柔光 + 中心一点暖色，像光球落在图标后面
            Canvas(Modifier.offset(x = x).width(slot).fillMaxHeight()) {
                val c = Offset(size.width / 2, size.height * 0.4f)
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.10f), Color.Transparent), c, size.height * 0.62f), size.height * 0.62f, c)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0f)), c, size.height * 0.3f), size.height * 0.3f, c)
            }
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, (g, label) ->
                    val sel = i == tab
                    val k by animateFloatAsState(if (sel) 1f else 0f, tween(260), label = "sel")
                    Column(
                        Modifier.width(slot).fillMaxHeight()
                            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                                if (!sel) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onTab(i)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Glyph(
                            g, androidx.compose.ui.graphics.lerp(Wg.ink3, Wg.ink, k),
                            Modifier.size(22.dp).graphicsLayer { translationY = -3.dp.toPx() * k; scaleX = 1f + 0.08f * k; scaleY = 1f + 0.08f * k },
                        )
                        Text(
                            label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Wg.ink.copy(alpha = k),
                            modifier = Modifier.padding(top = 2.dp).graphicsLayer { translationY = 4.dp.toPx() * (1f - k) },
                        )
                    }
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
