package com.ivyea.weiguang.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.LibState
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.Ncm
import com.ivyea.weiguang.core.Players
import com.ivyea.weiguang.core.Buddy
import com.ivyea.weiguang.core.Now
import com.ivyea.weiguang.core.Playlist
import com.ivyea.weiguang.core.Song
import com.ivyea.weiguang.core.rememberArt
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch

private fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "$m 分钟前"
        m < 60 * 24 -> "${m / 60} 小时前"
        else -> "${m / 60 / 24} 天前"
    }
}

// 封面还没加载出来（或这张歌单没有）时，按名字挑一组柔和渐变垫底
private val tiles = listOf(
    Color(0xFFC8C2F2) to Color(0xFFF6C9B8),
    Color(0xFFFFE2A8) to Color(0xFFF6AB86),
    Color(0xFFA8D4F5) to Color(0xFFC8C2F2),
    Color(0xFFF6C9B8) to Color(0xFFE9A6C4),
    Color(0xFFBDE3D3) to Color(0xFFA8D4F5),
    Color(0xFFE9C9F2) to Color(0xFFFFD6B8),
)

private fun tileOf(name: String) = tiles[(name.hashCode() and 0x7fffffff) % tiles.size]

@Composable
fun LibraryScreen(lib: LibState, now: Now?, accent: Color, time: State<Float>) {
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPl by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openPl != null || q.isNotEmpty()) { if (q.isNotEmpty()) q = "" else openPl = null }

    val sync: () -> Unit = { Library.syncAsync(ctx) }
    val play: (Song) -> Unit = { s -> Players.play(ctx, s) }
    val hits = remember(q, lib.songs) {
        val k = q.trim()
        if (k.isEmpty()) emptyList()
        else lib.songs.values.filter { it.title.contains(k, true) || it.artist.contains(k, true) }.sortedBy { it.title.length }.take(200)
    }
    val pl = lib.playlists.firstOrNull { it.id == openPl }
    val plSongs = remember(pl, lib.songs) { pl?.songIds?.mapNotNull { lib.songs[it] } ?: emptyList() }
    val history = remember(lib.history, lib.songs) { lib.history.mapNotNull { h -> lib.songs[h.id]?.let { it to h } } }
    val empty = lib.playlists.isEmpty() && lib.history.isEmpty()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 120.dp),
    ) {
        if (pl != null) {
            item {
                PlaylistHeader(pl, plSongs.size, accent, { openPl = null }, { Buddy.startQueue(ctx, plSongs.take(100).map { it.id }) }) {
                    if (pl.src == Ncm.PKG) Ncm.openPlaylist(ctx, pl.id) else Players.openApp(ctx, pl.src)
                }
            }
            items(plSongs, key = { "p${it.id}" }) { SongRow(it, now, accent, null) { play(it) } }
            return@LazyColumn
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("曲库", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, modifier = Modifier.weight(1f))
                if (!empty) SyncChip(lib.syncing, sync)
            }
            if (!empty) {
                Text(
                    "${lib.songs.size} 首 · ${lib.playlists.size} 个歌单" + if (lib.syncedAt > 0) " · ${ago(lib.syncedAt)}同步" else "",
                    fontSize = 12.5.sp, color = Wg.ink3, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (empty && tab != 4) {
            item { EmptyLibrary(lib, time, sync) }
            item {
                Text(
                    "先听微光电台和免费音乐", fontSize = 14.sp, color = accent, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp).clickable { tab = 4 }.padding(vertical = 10.dp),
                )
            }
            return@LazyColumn
        }
        if (empty) {
            item { RadioView(now, accent) }
            return@LazyColumn
        }

        item {
            if (lib.syncing || lib.syncMsg.isNotEmpty()) Notice(lib.syncing, lib.syncMsg, sync)
            SearchBox(q) { q = it }
        }

        if (q.isNotBlank()) {
            items(hits, key = { "h${it.id}" }) { SongRow(it, now, accent, null) { play(it) } }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp))
                        .clickable { Players.search(ctx, q.trim()) }.padding(horizontal = 10.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, null, tint = accent, modifier = Modifier.size(18.dp))
                    Text(
                        Players.nameOf(Players.target(ctx)).let { app -> if (hits.isEmpty()) "曲库里没有，去${app}搜「${q.trim()}」" else "去${app}搜「${q.trim()}」" },
                        fontSize = 14.sp, color = accent, modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            return@LazyColumn
        }

        item { Tabs(tab, accent) { tab = it } }
        if (tab == 4) {
            item { RadioView(now, accent) }
        } else if (tab == 3) {
            item { DiaryView(lib, accent) }
        } else if (tab == 2) {
            item { StarMap(lib, now, accent, time) }
        } else if (tab == 0) {
            if (lib.playlists.isEmpty()) item { Quiet("还没有歌单，点右上角同步") }
            else items(lib.playlists.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (p in row) PlaylistCard(p, Modifier.weight(1f)) { openPl = p.id }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            if (history.isEmpty()) item { Quiet("在音乐 App 里听过的歌，会自动记在这里") }
            else items(history, key = { "r${it.first.id}" }) { (s, h) ->
                SongRow(s, now, accent, "${h.count} 次 · ${ago(h.at)}") { play(s) }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(lib: LibState, time: State<Float>, onSync: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ParticleOrb(Modifier.fillMaxWidth().height(220.dp), time, { if (lib.syncing) 0.9f else 0.2f })
        Text(
            if (lib.syncing) "正在读取你的歌单" else "把音乐 App 的歌单带过来",
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink,
        )
        Text(
            if (lib.syncing) lib.syncMsg else "网易云能读到歌单；别的 App 能读多少看它开放多少。听过的歌都会记下来，只存在这台手机里",
            fontSize = 13.sp, color = Wg.ink2, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
        )
        if (!lib.syncing) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.shadow(14.dp, RoundedCornerShape(999.dp), spotColor = Wg.warm).clip(RoundedCornerShape(999.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFF6AB86), Wg.warm)))
                    .clickable(onClick = onSync).padding(horizontal = 30.dp, vertical = 13.dp),
            ) {
                Text("同步歌单", color = Color.White, fontSize = 15.sp)
            }
            if (lib.syncMsg.isNotEmpty()) {
                Text(
                    lib.syncMsg, fontSize = 12.5.sp, color = Wg.warm, textAlign = TextAlign.Center, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 18.dp, start = 12.dp, end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Notice(syncing: Boolean, msg: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (syncing) Wg.lav else Wg.warm))
        Text(
            if (syncing) msg.ifEmpty { "同步中…" } else msg,
            fontSize = 12.5.sp, color = Wg.ink2, lineHeight = 18.sp,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (!syncing) Text("重试", fontSize = 13.sp, color = Wg.warm, modifier = Modifier.clickable(onClick = onRetry).padding(start = 10.dp))
    }
}

@Composable
private fun SyncChip(syncing: Boolean, onClick: () -> Unit) {
    val spin = rememberInfiniteTransition(label = "spin")
    val deg by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "deg")
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).clickable(enabled = !syncing, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Refresh, null, tint = Wg.ink2, modifier = Modifier.size(16.dp).rotate(if (syncing) deg else 0f))
        Text(if (syncing) "同步中" else "同步", fontSize = 13.sp, color = Wg.ink2, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
internal fun SearchBox(q: String, hint: String = "搜歌名或歌手", onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).glass(999.dp, 2.dp).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = Wg.ink3, modifier = Modifier.size(17.dp))
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            if (q.isEmpty()) Text(hint, fontSize = 14.sp, color = Wg.ink3)
            BasicTextField(
                value = q, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Wg.ink),
                cursorBrush = SolidColor(Wg.warm),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (q.isNotEmpty()) Text("清除", fontSize = 13.sp, color = Wg.ink2, modifier = Modifier.clickable { onChange("") }.padding(start = 8.dp))
    }
}

// 文字分栏 + 选中项下面一道短线，比整条胶囊轻
@Composable
private fun Tabs(tab: Int, accent: Color, onTab: (Int) -> Unit) {
    // 显示顺序和编号分开（编号是存下来的，电台后加但放第二个）
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 22.dp, bottom = 14.dp)) {
        listOf(0 to "歌单", 4 to "电台", 1 to "听过", 2 to "星图", 3 to "日记").forEach { (i, label) ->
            val sel = i == tab
            Column(
                Modifier.padding(end = 24.dp).clickable { onTab(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, fontSize = if (sel) 16.sp else 15.sp, color = if (sel) Wg.ink else Wg.ink3, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                Box(Modifier.padding(top = 6.dp).width(16.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (sel) accent else Color.Transparent))
            }
        }
    }
}

// 歌单封面 = 第一首歌的专辑图，上面压一层浅色渐变放首字
@Composable
private fun PlaylistArt(p: Playlist, modifier: Modifier, radius: Int, big: Boolean) {
    val (c1, c2) = tileOf(p.name)
    // 读一下 state，封面地址补上来时会重组
    Library.state.collectAsState().value
    val url = Library.coverOf(p)
    val img = rememberArt(url, 360)
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(Brush.linearGradient(listOf(c1, c2))), contentAlignment = Alignment.BottomStart) {
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0x55302A50)))))
        } else {
            Text(p.name.take(1), fontSize = if (big) 26.sp else 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.92f), modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun PlaylistCard(p: Playlist, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.glass(20.dp, 4.dp).clickable(onClick = onClick).padding(10.dp)) {
        PlaylistArt(p, Modifier.fillMaxWidth().aspectRatio(1f), 14, true)
        Text(p.name, fontSize = 14.sp, color = Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
        Text("${p.songIds.size} 首", fontSize = 12.sp, color = Wg.ink3, modifier = Modifier.padding(top = 2.dp, start = 2.dp, bottom = 2.dp))
    }
}

@Composable
private fun PlaylistHeader(p: Playlist, count: Int, accent: Color, onBack: () -> Unit, onQueue: () -> Unit, onOpen: () -> Unit) {
    val ncm = p.src == Ncm.PKG
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text("‹ 曲库", fontSize = 14.sp, color = Wg.ink2, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistArt(p, Modifier.size(96.dp).shadow(16.dp, RoundedCornerShape(20.dp), spotColor = accent), 20, false)
            Column(Modifier.padding(start = 14.dp)) {
                Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$count 首 · ${if (p.src == com.ivyea.weiguang.core.Local.PKG) "微光自己放" else Players.nameOf(p.src)}", fontSize = 13.sp, color = Wg.ink3, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "按顺序播放", fontSize = 13.sp, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(accent)
                    .clickable(onClick = onQueue).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (p.src != com.ivyea.weiguang.core.Local.PKG) Text(
                if (ncm) "在网易云打开" else "打开${Players.nameOf(p.src)}", fontSize = 13.sp, color = Wg.ink2,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.7f))
                    .clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (ncm && count in 1..12) {
            Text(
                "网易云只给微光看前几首。在网易云里点「播放全部」一次，微光就能从播放队列补全整个歌单。",
                fontSize = 12.sp, color = Wg.ink3, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
internal fun Quiet(s: String) {
    Text(s, fontSize = 13.sp, color = Wg.ink3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp))
}

@Composable
internal fun SongRow(s: Song, now: Now?, accent: Color, extra: String?, onClick: () -> Unit) {
    val playing = now?.songId == s.id
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .background(if (playing) accent.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(s, 42)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                s.title, fontSize = 15.sp, color = if (playing) accent else Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
            )
            val sub = listOfNotNull(s.artist.takeIf { it.isNotEmpty() }, s.src?.let { Players.nameOf(it) }, extra).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Wg.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (playing) Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        else Glyph(G.Play, Wg.ink3, Modifier.size(15.dp))
    }
}
