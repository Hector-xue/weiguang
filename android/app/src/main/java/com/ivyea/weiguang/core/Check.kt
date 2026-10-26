package com.ivyea.weiguang.core

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Build
import android.provider.MediaStore

// 一键自检：把手机上各音乐 App 的真实情况（播放会话报了什么、支持哪些点歌方式）写成一份报告，
// 用户复制发过来就行，不用一项项来回截图
object Check {
    private val acts = listOf(
        PlaybackState.ACTION_PLAY_FROM_SEARCH to "按名字点",
        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "按ID点",
        PlaybackState.ACTION_PLAY_FROM_URI to "按链接点",
        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "跳到队列",
        PlaybackState.ACTION_SKIP_TO_NEXT to "下一首",
        PlaybackState.ACTION_SEEK_TO to "拖进度",
    )

    private fun clip(x: Any?, n: Int = 60) = x?.toString()?.replace("\n", " ")?.let { if (it.length > n) it.take(n) + "…" else it } ?: ""

    fun report(ctx: Context): String {
        val b = StringBuilder()
        val pm = ctx.packageManager
        b.appendLine("微光 ${Players.version(ctx, ctx.packageName)} · Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        b.appendLine("点歌用=${Prefs.player.ifEmpty { "自动" }} 上次=${Prefs.lastPlayer} 自建曲库=${Sources.servers.size}个")
        val now = NowPlaying.now.value
        b.appendLine("正在放: ${now?.let { "${Players.nameOf(it.pkg)} 《${it.title}》${it.artist} 在放=${it.playing} 歌词句=${clip(it.lyric, 30)}" } ?: "无（没有通知使用权或都没在放）"}")
        b.appendLine()
        for (p in Players.installed(ctx)) {
            b.appendLine("【${p.name}】${Players.version(ctx, p.pkg)}")
            val search = pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(p.pkg), 0).isNotEmpty()
            val browse = pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(p.pkg), 0).map { it.serviceInfo.name.substringAfterLast('.') }
            b.appendLine("  系统点歌意图=${if (search) "有" else "无"} 车机接口=${browse.ifEmpty { listOf("无") }.joinToString()} 学到的点歌方式=${Prefs.how(p.pkg).ifEmpty { "-" }} 歌词当歌名=${Prefs.lyricMode(p.pkg)}")
            val c = NowPlaying.controllerOf(p.pkg)
            if (c == null) { b.appendLine("  播放会话: 没有（没打开或没放过歌）"); b.appendLine(); continue }
            val st = c.playbackState
            val a = st?.actions ?: 0L
            b.appendLine("  会话: 状态=${st?.state} 支持=${acts.filter { (a and it.first) != 0L }.joinToString("/") { it.second }.ifEmpty { "无" }}")
            st?.customActions?.takeIf { it.isNotEmpty() }?.let { l -> b.appendLine("  自定义按钮: " + l.joinToString { "${clip(it.name, 12)}(${it.action.substringAfterLast('.')})" }) }
            val m = c.metadata
            if (m != null) {
                b.appendLine("  原始信息:")
                for (k in m.keySet().sorted()) {
                    val v: Any? = m.getText(k) ?: if (k.contains("ART") || k.contains("ICON")) m.getBitmap(k)?.let { "图 ${it.width}x${it.height}" } else m.getLong(k)
                    b.appendLine("    ${k.removePrefix("android.media.metadata.")}=${clip(v)}")
                }
            }
            val q = c.queue
            b.appendLine("  队列: ${q?.size ?: "无"}${c.queueTitle?.let { "（$it）" } ?: ""}")
            q?.take(3)?.forEach { qi -> b.appendLine("    ${clip(qi.description.title, 30)} | ${clip(qi.description.subtitle, 30)} | ${clip(qi.description.mediaId, 30)}") }
            b.appendLine()
        }
        val songs = Library.state.value.songs.values
        b.appendLine("曲库: ${songs.size} 首 · " + songs.groupBy { Players.nameOf(it.src ?: Ncm.PKG) }.entries.joinToString { "${it.key}${it.value.size}" })
        return b.toString()
    }

    // 试点歌：对正在放的那个 App 每种方式单独点一首别的歌，看哪种能放起来
    suspend fun tryPlay(ctx: Context): String {
        val n = NowPlaying.now.value ?: return "先在要测的 App 里放一首歌，再点「试点歌」"
        val pkg = n.pkg
        if (pkg == Ncm.PKG || pkg == Local.PKG) return "${Players.nameOf(pkg)} 不用测：按歌曲 ID 点播"
        val cur = Library.norm(n.title)
        val s = Library.state.value.songs.values.firstOrNull { it.src == pkg && it.title.isNotEmpty() && Library.norm(it.title) != cur && !it.title.contains("—") }
            ?: Song(0, if (cur == Library.norm("后来")) "晴天" else "后来", if (cur == Library.norm("后来")) "周杰伦" else "刘若英")
        val r = Players.probe(ctx, pkg, s)
        return "试点歌（${Players.nameOf(pkg)}，《${s.title}》${s.artist}）: " +
            r.joinToString(" ") { "${Players.methods[it.first]}=${if (it.second) "✅" else "❌"}" }
    }
}
