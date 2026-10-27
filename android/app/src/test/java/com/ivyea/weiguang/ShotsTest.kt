package com.ivyea.weiguang

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ivyea.weiguang.core.Buddy
import com.ivyea.weiguang.core.LibState
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.Msg
import com.ivyea.weiguang.core.Now
import com.ivyea.weiguang.core.NowPlaying
import com.ivyea.weiguang.core.Play
import com.ivyea.weiguang.core.Played
import com.ivyea.weiguang.core.Playlist
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Song
import com.ivyea.weiguang.ui.Mood
import com.ivyea.weiguang.ui.Shell
import com.ivyea.weiguang.ui.WeiguangTheme
import com.ivyea.weiguang.ui.Wg
import androidx.compose.ui.graphics.Color
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// 截图：用假数据渲染每个页面，CI 上出 PNG（gradle :app:recordRoborazziDebug），改界面时对着图调
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h870dp-xxhdpi")
class ShotsTest {
    @get:Rule val rule = createComposeRule()

    private fun art(c1: Int, c2: Int): Bitmap {
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, c1, c2, Shader.TileMode.CLAMP) }
        Canvas(b).drawRect(0f, 0f, 300f, 300f, p)
        return b
    }

    private val titles = listOf(
        "两不知" to "南风叙", "晴天" to "周杰伦", "后来" to "刘若英", "爱似水仙" to "金海心", "海阔天空" to "Beyond",
        "我要找到你" to "子晨", "直到遇见了你" to "陈柯宇", "降临" to "华晨宇", "曾经的你" to "许巍", "富士山下" to "陈奕迅",
        "夜空中最亮的星" to "逃跑计划", "起风了" to "买辣椒也用券", "稻香" to "周杰伦", "光年之外" to "邓紫棋",
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.init(ctx)
        val songs = titles.mapIndexed { i, (t, a) ->
            Song(i + 1L, t, a, v = (i * 37 % 100) / 100f, e = (i * 53 % 100) / 100f, pic = "", src = "com.netease.cloudmusic")
        }.associateBy { it.id }
        val now = System.currentTimeMillis()
        val pls = listOf("我喜欢的音乐", "流行", "许巍", "深夜食堂", "粤语经典", "80年代", "古诗词").mapIndexed { i, n ->
            Playlist("ncm_pl_${1000 + i}", n, songs.keys.shuffled(java.util.Random(i.toLong())).take(8 + i))
        }
        Library.preview(LibState(
            songs = songs, playlists = pls,
            history = songs.keys.take(10).mapIndexed { i, id -> Played(id, now - i * 3_600_000L, 10 - i) },
            plays = songs.keys.flatMap { id -> List(3) { k -> Play(id, now - k * 86_400_000L) } },
            syncedAt = now - 3_600_000L,
        ))
        NowPlaying.preview(Now(
            "com.luna.music", "两不知", "南风叙", "两不知", art(0xFF3A3550.toInt(), 0xFFE9A27A.toInt()),
            211_000, 135_000, SystemClock.elapsedRealtime(), 1f, true, 1L, null, "谁在生死两头望", null, false,
        ))
        Buddy.preview(emptyList(), "傍晚的风有点凉，这首《两不知》刚好，慢一点听。")
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { WeiguangTheme { content() } }
        rule.mainClock.advanceTimeBy(1500)
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    private val mood = Mood(Color(0xFFEDE6EE), Color(0xFFF7E3D6), Color(0xFFE6E4F4), Color(0xFFC0714F))
    private val time = mutableFloatStateOf(3.2f)

    @Composable private fun screen(tab: Int, listener: Boolean = true) =
        Shell(tab, listener, NowPlaying.now.value, Library.state.value, mood, time) {}

    @Test fun s1_moment() = shot("1_moment") { screen(0) }
    @Test fun s2_now() = shot("2_now") { screen(1) }
    @Test fun s3_library() = shot("3_library") { screen(2) }
    @Test fun s4_settings() = shot("4_settings") { screen(3) }
    @Test fun s5_onboarding() = shot("5_onboarding") { screen(0, listener = false) }
    @Test fun s6_chat() {
        Buddy.preview(listOf(
            Msg(true, "下雨了"),
            Msg(false, "雨天适合慢一点的歌，给你挑了几首：", listOf(2L, 3L, 9L), "平静"),
        ), null)
        shot("6_chat") { screen(0) }
    }
    @Test fun s7_nowEmpty() {
        NowPlaying.preview(null)
        shot("7_moment_idle") { screen(0) }
    }

    // 分享卡片直接存图
    private fun save(b: Bitmap, name: String) {
        java.io.File("build/shots").mkdirs()
        java.io.FileOutputStream("build/shots/$name.png").use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun s8_cards() {
        val art = art(0xFF3A3550.toInt(), 0xFFE9A27A.toInt())
        save(com.ivyea.weiguang.core.Card.week(com.ivyea.weiguang.core.Recaps.week(), art), "8_week_card")
        save(com.ivyea.weiguang.core.Card.week(com.ivyea.weiguang.core.Recaps.month(), art), "10_month_card")
        save(com.ivyea.weiguang.core.Card.lyric("谁在生死两头望", "两不知 (电视剧《XX》插曲)", "南风叙", art), "9_lyric_card")
    }

    // 每晚一首这一晚会说什么：今天听过歌就是「今天的歌」，写进文件对着看
    @Test fun s9_nightly() {
        val n = com.ivyea.weiguang.core.Nightly.compose()
        org.junit.Assert.assertNotNull(n)
        java.io.File("build/shots").mkdirs()
        java.io.File("build/shots/11_nightly.txt").writeText("${n!!.title}\n${n.text}\n${n.open} ${n.play}")
    }
    // 两个小组件：真把 RemoteViews 铺出来画成图（也拿来当桌面的预览图）
    private fun widget(v: android.widget.RemoteViews, wDp: Int, hDp: Int, name: String) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = ctx.resources.displayMetrics.density
        val parent = android.widget.FrameLayout(ctx)
        val view = v.apply(ctx, parent)
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(b))
        save(b, name)
    }
    @Test fun s11_widgets() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        widget(com.ivyea.weiguang.service.RecapWidget.views(ctx), 170, 170, "13_widget_recap")
    }
    @Test fun s10_recapMonth() = shot("12_recap_month") {
        com.ivyea.weiguang.ui.RecapSheet(com.ivyea.weiguang.core.Recaps.week(), mood.accent, month0 = true) {}
    }
}
