package com.ivyea.weiguang.core

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import com.ivyea.weiguang.MainActivity
import com.ivyea.weiguang.service.NowListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 微光能陪着听的音乐 App（洛雪有公开的点歌链接，最好用）。读播放、控制对所有 App 都一样（系统播放会话）；
// 点歌各家不同：网易云按歌曲 ID 直接放（真机验证过），其他 App 没有公开的点歌链接，
// 只能走安卓的标准点歌途径，一条条试、试完看它真放了没有，接住的那条记下来下次先用
data class Player(val pkg: String, val name: String, val short: String)

object Players {
    const val LX = "cn.toside.music.mobile"

    val all = listOf(
        Player(Ncm.PKG, "网易云音乐", "网易云"),
        Player("com.tencent.qqmusic", "QQ音乐", "QQ音乐"),
        Player("com.kugou.android", "酷狗音乐", "酷狗"),
        Player("com.kugou.android.lite", "酷狗概念版", "酷狗"),
        Player("cn.kuwo.player", "酷我音乐", "酷我"),
        Player("com.luna.music", "汽水音乐", "汽水"),
        Player(LX, "洛雪音乐", "洛雪"),
        Player("fun.upup.musicfree", "MusicFree", "MusicFree"),
    )
    // 微光自己（放自建曲库和 AI 作曲）也算一个，但不在「装了哪些」里
    private val self = Player(Local.PKG, "微光", "微光")
    private val known = (all + self).associateBy { it.pkg }

    fun isKnown(pkg: String?) = pkg != null && pkg in known

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun installed(ctx: Context): List<Player> = all.filter { version(ctx, it.pkg) != null }

    fun version(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) { null }

    fun nameOf(pkg: String?): String = known[pkg]?.short ?: "音乐 App"

    // 点歌能选的：装了的 App + 微光自己（自建曲库、电台，不开别的 App）
    fun choices(ctx: Context): List<Player> = installed(ctx) + self

    // 点歌用哪个 App：设置里指定的 > 正在放的 > 上次放过的（都要接得住点歌）> 网易云 > 装了的第一个
    fun target(ctx: Context): String? {
        val inst = choices(ctx).map { it.pkg }
        if (inst.isEmpty()) return null
        Prefs.player.takeIf { it in inst }?.let { return it }
        // 试过接不住点歌的 App（比如汽水）不当默认：正在放它也换别家点
        NowPlaying.now.value?.pkg?.takeIf { it in inst && Prefs.how(it) != "none" }?.let { return it }
        Prefs.lastPlayer.takeIf { it in inst && Prefs.how(it) != "none" }?.let { return it }
        return if (Ncm.PKG in inst) Ncm.PKG else inst.first { it != Local.PKG || inst.size == 1 }
    }

    fun openApp(ctx: Context, pkg: String? = target(ctx)) {
        val i = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) } ?: return
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
    }

    fun openStore(ctx: Context, pkg: String = Ncm.PKG) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {}
    }

    // ---------- 点歌 ----------

    // 点歌方式：lx=洛雪的点歌链接；session=对它的播放会话按歌名点；mid=按车机接口里的条目点；intent=系统「按名字播放」
    val methods = linkedMapOf("lx" to "洛雪点歌链接", "session" to "按歌名点播", "mid" to "车机接口点播", "intent" to "系统点歌")

    // 自建曲库里有的歌（或 AI 作曲的）优先微光自己放：不切 App、不受版权和会员限制
    fun play(ctx: Context, s: Song) {
        val app = ctx.applicationContext
        val pkg = target(app) ?: Local.PKG
        if (s.loc != null && (Prefs.player.isEmpty() || pkg == Local.PKG || s.src == Local.PKG)) {
            if (Local.play(app, listOf(s))) return
        }
        scope.launch {
            if (pkg == Local.PKG) {
                val it = Sources.find(s.title, s.artist)
                if (it != null && Local.play(app, listOf(s.copy(loc = it.loc, pic = s.pic ?: it.cover)))) return@launch
                // 选了「微光」就不开别的 App：曲库里没有就放微光电台
                val other = installed(app).firstOrNull()?.pkg?.takeIf { Prefs.player != Local.PKG }
                if (other == null) {
                    toast(app, if (Sources.servers.isEmpty()) "微光自己没有这首，先放微光电台" else "自建曲库里没有这首，先放微光电台")
                    Free.playMood(app)?.let { toast(app, it) }
                    return@launch
                }
                toast(app, "自建曲库里没有这首，换${nameOf(other)}放")
                playIn(app, other, s)
            } else playIn(app, pkg, s)
        }
    }

    private suspend fun playIn(ctx: Context, pkg: String, s: Song) {
        if (pkg == Ncm.PKG) {
            val id = s.cloudId() ?: Library.matchNow(s)
            if (id != null) { if (!Ncm.playSong(ctx, id)) toast(ctx, "打不开网易云"); return }
            Ncm.search(ctx, query(s))
            toast(ctx, "网易云里没对上这首，已打开搜索")
            return
        }
        val name = nameOf(pkg)
        val learned = Prefs.how(pkg)
        val first = if (pkg == LX) listOf("lx") else emptyList()
        val order = (listOfNotNull(learned.takeIf { it in methods }) + first + methods.keys.filter { it != "lx" }).distinct()
        for (m in order) {
            val ok = when (m) {
                "lx" -> pkg == LX && byLx(ctx, s)
                "session" -> bySession(pkg, s)
                "mid" -> byMediaId(ctx, pkg, s)
                else -> byIntent(ctx, pkg, s)
            }
            if (ok) {
                Prefs.setHow(pkg, m)
                return
            }
        }
        Prefs.setHow(pkg, "none")
        // 不是用户在设置里指定的，又装了网易云：直接换网易云放，以后也默认用它
        if (Prefs.player != pkg && installed(ctx).any { it.pkg == Ncm.PKG }) {
            toast(ctx, "$name 不接受别的 App 点歌，换网易云放")
            playIn(ctx, Ncm.PKG, s)
            return
        }
        copy(ctx, query(s))
        openApp(ctx, pkg)
        toast(ctx, "$name 没接住点歌，已打开它，歌名复制好了，粘贴到搜索就行")
    }

    // 自检用：对一个 App 每种点歌方式单独试一遍（不记、不兜底），中间停一会儿
    suspend fun probe(ctx: Context, pkg: String, s: Song): List<Pair<String, Boolean>> =
        methods.keys.filter { it != "lx" || pkg == LX }.map { m ->
            val ok = when (m) {
                "lx" -> byLx(ctx, s)
                "session" -> bySession(pkg, s)
                "mid" -> byMediaId(ctx, pkg, s)
                else -> byIntent(ctx, pkg, s)
            }
            delay(800)
            m to ok
        }

    fun query(s: Song) = (s.title + " " + s.artist.split("/", "、", "&", ",").first()).trim()

    private fun extras(s: Song) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, s.title)
        putString(MediaStore.EXTRA_MEDIA_ARTIST, s.artist)
        putString(SearchManager.QUERY, query(s))
    }

    // 洛雪公开了点歌链接：lxmusic://music/searchPlay?data={name,singer}（见它的 Scheme URL 文档）
    private suspend fun byLx(ctx: Context, s: Song): Boolean {
        val data = org.json.JSONObject().put("name", s.title).put("singer", s.artist.split("/", "、", "&", ",").first().trim())
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("lxmusic://music/searchPlay?data=" + Uri.encode(data.toString())))
            .setPackage(LX).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            val ok = playing(LX, s, 9000)
            if (ok) scheduleReturn(ctx, wait = false)
            ok
        } catch (_: Throwable) { false }
    }

    private suspend fun bySession(pkg: String, s: Song): Boolean {
        val c = NowPlaying.controllerOf(pkg) ?: return false
        return try {
            c.transportControls.playFromSearch(query(s), extras(s))
            playing(pkg, s, 5000)
        } catch (_: Throwable) { false }
    }

    private suspend fun byMediaId(ctx: Context, pkg: String, s: Song): Boolean {
        val mid = s.mid ?: return false
        if (s.src != pkg) return false
        val b = Library.connect(ctx, pkg) ?: return false
        return try {
            MediaController(ctx, b.sessionToken).transportControls.playFromMediaId(mid, null)
            playing(pkg, s, 5000)
        } catch (_: Throwable) { false } finally {
            try { b.disconnect() } catch (_: Throwable) {}
        }
    }

    private suspend fun byIntent(ctx: Context, pkg: String, s: Song): Boolean {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
            .putExtras(extras(s)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ctx.packageManager.queryIntentActivities(i, 0).isEmpty()) return false
        return try {
            ctx.startActivity(i)
            val ok = playing(pkg, s, 7000)
            if (ok) scheduleReturn(ctx, wait = false)
            ok
        } catch (_: Throwable) { false }
    }

    // 等它真的放起来：同一个 App、歌名对得上
    private suspend fun playing(pkg: String, s: Song, timeout: Long): Boolean {
        val want = Library.norm(s.title)
        var waited = 0L
        while (waited < timeout) {
            delay(250); waited += 250
            val n = NowPlaying.now.value ?: continue
            if (n.pkg != pkg) continue
            val got = Library.norm(n.title)
            if (got.isNotEmpty() && (got == want || got.startsWith(want) || want.startsWith(got))) return true
            if (want.isNotEmpty() && Library.norm(n.artist).contains(want)) return true
        }
        return false
    }

    // 只是打开某个 App 的搜索：网易云有搜索链接，其他 App 复制歌名后打开
    fun search(ctx: Context, q: String) {
        val pkg = target(ctx)?.takeIf { it != Local.PKG } ?: installed(ctx).firstOrNull()?.pkg ?: return
        if (pkg == Ncm.PKG) { Ncm.search(ctx, q); return }
        if (pkg == LX) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lxmusic://music/search?data=" + Uri.encode(org.json.JSONObject().put("keywords", q).toString())))
                    .setPackage(LX).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {}
        }
        copy(ctx, q)
        openApp(ctx, pkg)
        toast(ctx, "已复制「$q」，在${nameOf(pkg)}里粘贴搜索")
    }

    private fun copy(ctx: Context, text: String) {
        try {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("歌名", text))
        } catch (_: Throwable) {}
    }

    private fun toast(ctx: Context, msg: String) = main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }

    // ---------- 打开就放 ----------

    // 没在放才动：选了微光→电台；否则接着放正在用的 App（会话还在就直接 play），
    // 会话不在就发系统「播放」键（系统交给最近放过歌的 App，不打开它的界面），都没响应再放微光电台
    fun autoStart(ctx: Context) {
        if (!Prefs.autoplay) return
        val app = ctx.applicationContext
        scope.launch {
            delay(1200)
            if (NowPlaying.now.value?.playing == true) return@launch
            val pkg = target(app)
            if (pkg != Local.PKG && pkg != null) {
                val c = NowPlaying.controllerOf(pkg)
                try {
                    if (c != null) c.transportControls.play() else mediaKey(app)
                } catch (_: Throwable) {}
                var waited = 0
                while (waited < 3000) {
                    delay(250); waited += 250
                    if (NowPlaying.now.value?.playing == true) return@launch
                }
            }
            Free.playMood(app)?.let { toast(app, it) }
        }
    }

    private fun mediaKey(ctx: Context) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val t = android.os.SystemClock.uptimeMillis()
        for (a in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, a, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0))
    }

    // ---------- 自动跳回 ----------

    // 系统不许后台 App 自己回到前台；有悬浮窗权限且光球正显示时才放行，所以只在这时尝试
    fun canAutoReturn(ctx: Context) = Settings.canDrawOverlays(ctx) && NowListener.orbShowing

    fun scheduleReturn(app: Context, wait: Boolean = true) {
        if (!canAutoReturn(app)) return
        main.postDelayed({
            try {
                app.startActivity(Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (_: Throwable) {}
        }, if (wait) 2500 else 600)
    }
}
