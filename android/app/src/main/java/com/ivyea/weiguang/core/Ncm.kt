package com.ivyea.weiguang.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

// 和网易云打交道的入口。能用的路子都是真机验证过的（见 docs/方案.md「真机验证结论」）
object Ncm {
    const val PKG = "com.netease.cloudmusic"

    fun version(ctx: Context): String? = try {
        ctx.packageManager.getPackageInfo(PKG, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) { null }

    // 网易云的 mediaId 末尾就是歌曲 ID：other_id__212644、playlist_id_385848799_3439448129
    fun songIdOf(mediaId: String?): Long? =
        mediaId?.let { Regex("_(\\d+)$").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    // 按 ID 打开会直接开始播放（会切到网易云）
    fun playSong(ctx: Context, id: Long): Boolean = open(ctx, "orpheus://song/$id")

    // 歌单 mediaId 形如 playlist_id_385848799；打开歌单页，用户点「播放全部」后队列会把整张歌单报给微光
    fun openPlaylist(ctx: Context, mediaId: String): Boolean {
        val id = Regex("(\\d+)$").find(mediaId)?.value ?: return false
        return open(ctx, "orpheus://playlist/$id")
    }

    // 按关键词点歌网易云不支持，只能打开它的搜索页让用户点
    fun search(ctx: Context, q: String): Boolean = open(ctx, "orpheus://search?keyword=" + Uri.encode(q))

    private fun open(ctx: Context, uri: String): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            Players.scheduleReturn(ctx.applicationContext)
            true
        } catch (_: ActivityNotFoundException) { false }
    }
}
