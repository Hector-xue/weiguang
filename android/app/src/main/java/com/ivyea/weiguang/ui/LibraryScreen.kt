package com.ivyea.weiguang.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.LibState
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.Ncm
import com.ivyea.weiguang.core.Now
import com.ivyea.weiguang.core.Song
import kotlinx.coroutines.launch

private fun ago(at: Long): String {
    if (at <= 0) return "还没同步"
    val m = (System.currentTimeMillis() - at) / 60000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "$m 分钟前"
        m < 60 * 24 -> "${m / 60} 小时前"
        else -> "${m / 60 / 24} 天前"
    }
}

@Composable
fun LibraryScreen(lib: LibState, now: Now?, accent: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPl by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openPl != null || q.isNotEmpty()) { if (q.isNotEmpty()) q = "" else openPl = null }

    val play: (Song) -> Unit = { s -> if (!Ncm.playSong(ctx, s.id)) Toast.makeText(ctx, "打不开网易云", Toast.LENGTH_SHORT).show() }
    val hits = remember(q, lib.songs) {
        val k = q.trim()
        if (k.isEmpty()) emptyList()
        else lib.songs.values.filter { it.title.contains(k, true) || it.artist.contains(k, true) }.sortedBy { it.title.length }.take(200)
    }
    val pl = lib.playlists.firstOrNull { it.id == openPl }
    val plSongs = remember(pl, lib.songs) { pl?.songIds?.mapNotNull { lib.songs[it] } ?: emptyList() }
    val history = remember(lib.history, lib.songs) { lib.history.mapNotNull { h -> lib.songs[h.id]?.let { it to h } } }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 120.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("曲库", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
                    Text(
                        "${lib.songs.size} 首 · ${lib.playlists.size} 个歌单 · 同步于${ago(lib.syncedAt)}",
                        fontSize = 13.sp, color = Wg.ink2, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                SyncButton(lib.syncing) { scope.launch { Library.sync(ctx) } }
            }
            if (lib.syncMsg.isNotEmpty()) {
                Text(lib.syncMsg, fontSize = 12.sp, color = if (lib.syncing) Wg.lav else Wg.warm, modifier = Modifier.padding(top = 8.dp))
            }
            SearchBox(q) { q = it }
        }

        when {
            q.isNotBlank() -> {
                items(hits, key = { "h${it.id}" }) { SongRow(it, now, accent, null) { play(it) } }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).glass(18.dp, 4.dp)
                            .clickable { Ncm.search(ctx, q.trim()) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, null, tint = accent, modifier = Modifier.size(18.dp))
                        Text(
                            if (hits.isEmpty()) "曲库里没有，去网易云搜「${q.trim()}」" else "去网易云搜「${q.trim()}」",
                            fontSize = 14.sp, color = Wg.ink, modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
            pl != null -> {
                item {
                    Row(Modifier.fillMaxWidth().clickable { openPl = null }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("‹", fontSize = 22.sp, color = Wg.ink2)
                        Text(pl.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${plSongs.size} 首", fontSize = 13.sp, color = Wg.ink3)
                    }
                }
                items(plSongs, key = { "p${it.id}" }) { SongRow(it, now, accent, null) { play(it) } }
            }
            else -> {
                item { Tabs(tab) { tab = it } }
                if (tab == 0) {
                    if (lib.playlists.isEmpty()) {
                        item {
                            Hint(
                                if (lib.syncing) "正在读取你在网易云的歌单…" else "点右上角同步，读取你在网易云的歌单和播放历史",
                            )
                        }
                    } else {
                        items(lib.playlists, key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { openPl = p.id }
                                    .padding(horizontal = 4.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                    Text(p.name.take(1), color = accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(p.name, fontSize = 15.sp, color = Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${p.songIds.size} 首", fontSize = 12.sp, color = Wg.ink3, modifier = Modifier.padding(top = 2.dp))
                                }
                                Text("›", fontSize = 20.sp, color = Wg.ink3)
                            }
                        }
                    }
                } else {
                    if (history.isEmpty()) {
                        item { Hint("在网易云里听过的歌会自动记在这里") }
                    } else {
                        items(history, key = { "r${it.first.id}" }) { (s, h) ->
                            SongRow(s, now, accent, "${h.count} 次 · ${ago(h.at)}") { play(s) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncButton(syncing: Boolean, onClick: () -> Unit) {
    val spin = rememberInfiniteTransition(label = "spin")
    val deg by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "deg")
    Box(
        Modifier.size(44.dp).glass(999.dp, 4.dp).clickable(enabled = !syncing, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = "同步", tint = Wg.ink2, modifier = Modifier.size(20.dp).rotate(if (syncing) deg else 0f))
    }
}

@Composable
private fun SearchBox(q: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).glass(999.dp, 4.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = Wg.ink3, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            if (q.isEmpty()) Text("搜歌名或歌手", fontSize = 15.sp, color = Wg.ink3)
            BasicTextField(
                value = q, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = Wg.ink),
                cursorBrush = SolidColor(Wg.warm),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (q.isNotEmpty()) Text("清除", fontSize = 13.sp, color = Wg.ink2, modifier = Modifier.clickable { onChange("") }.padding(start = 8.dp))
    }
}

@Composable
private fun Tabs(tab: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp).glass(999.dp, 2.dp).padding(4.dp)) {
        listOf("歌单", "听过").forEachIndexed { i, label ->
            val sel = i == tab
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (sel) Color.White else Color.Transparent)
                    .clickable { onTab(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 14.sp, color = if (sel) Wg.ink else Wg.ink2, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun Hint(s: String) {
    Text(s, fontSize = 14.sp, color = Wg.ink2, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 8.dp))
}

@Composable
private fun SongRow(s: Song, now: Now?, accent: Color, extra: String?, onClick: () -> Unit) {
    val playing = now?.songId == s.id
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .background(if (playing) accent.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.title, fontSize = 15.sp, color = if (playing) accent else Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal)
            val sub = listOfNotNull(s.artist.takeIf { it.isNotEmpty() }, extra).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Wg.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (playing) Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        else Glyph(G.Play, Wg.ink3, Modifier.size(16.dp))
    }
    Spacer(Modifier.height(2.dp))
}
