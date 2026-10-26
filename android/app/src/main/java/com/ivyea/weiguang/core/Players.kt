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

// 微光能陪着听的音乐 App。读播放、控制对所有 App 都一样（系统播放会话）；
// 点歌各家不同：网易云按歌曲 ID 直接放（真机验证过），其他 App 没有公开的点歌链接，
// 只能走安卓的标准点歌途径，一条条试、试完看它真放了没有，接住的那条记下来下次先用
data class Player(val pkg: String, val name: String, val short: String)

object Players {
    val all = listOf(
        Player(Ncm.PKG, "网易云音乐", "网易云"),
        Player("com.tencent.qqmusic", "QQ音乐", "QQ音乐"),
        Player("com.kugou.android", "酷狗音乐", "酷狗"),
        Player("com.kugou.android.lite", "酷狗概念版", "酷狗"),
        Player("cn.kuwo.player", "酷我音乐", "酷我"),
        Player("com.luna.music", "汽水音乐", "汽水"),
    )
    private val known = all.associateBy { it.pkg }

    fun isKnown(pkg: String?) = pkg != null && pkg in known

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun installed(ctx: Context): List<Player> = all.filter { version(ctx, it.pkg) != null }

    fun version(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) { null }

    fun nameOf(pkg: String?): String = known[pkg]?.short ?: "音乐 App"

    // 点歌用哪个 App：设置里指定的 > 正在放的 > 上次放过的 > 网易云 > 装了的第一个
    fun target(ctx: Context): String? {
        val inst = installed(ctx).map { it.pkg }
        if (inst.isEmpty()) return null
        Prefs.player.takeIf { it in inst }?.let { return it }
        NowPlaying.now.value?.pkg?.takeIf { it in inst }?.let { return it }
        Prefs.lastPlayer.takeIf { it in inst }?.let { return it }
        return if (Ncm.PKG in inst) Ncm.PKG else inst.first()
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

    // 点歌方式：session=对它的播放会话按歌名点；mid=按车机接口里的条目点；intent=系统「按名字播放」
    val methods = linkedMapOf("session" to "按歌名点播", "mid" to "车机接口点播", "intent" to "系统点歌")

    fun play(ctx: Context, s: Song) {
        val app = ctx.applicationContext
        val pkg = target(app) ?: run { toast(app, "还没装音乐 App"); return }
        scope.launch { playIn(app, pkg, s) }
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
        val order = (listOfNotNull(learned.takeIf { it in methods }) + methods.keys).distinct()
        for (m in order) {
            val ok = when (m) {
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
        copy(ctx, query(s))
        openApp(ctx, pkg)
        toast(ctx, "$name 没接住点歌，已打开它，歌名复制好了，粘贴到搜索就行")
    }

    fun query(s: Song) = (s.title + " " + s.artist.split("/", "、", "&", ",").first()).trim()

    private fun extras(s: Song) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, s.title)
        putString(MediaStore.EXTRA_MEDIA_ARTIST, s.artist)
        putString(SearchManager.QUERY, query(s))
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
        }
        return false
    }

    // 只是打开某个 App 的搜索：网易云有搜索链接，其他 App 复制歌名后打开
    fun search(ctx: Context, q: String) {
        val pkg = target(ctx) ?: return
        if (pkg == Ncm.PKG) { Ncm.search(ctx, q); return }
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
