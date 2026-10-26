package com.ivyea.weiguang.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.Feels
import com.ivyea.weiguang.core.Free
import com.ivyea.weiguang.core.Now
import com.ivyea.weiguang.core.Radio
import com.ivyea.weiguang.core.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 曲库「电台」：微光自己的音源，不开别的 App。按心情一键放 / 免费曲库按曲风 / 网络电台（中文台、搜索、收藏）
@Composable
fun RadioView(now: Now?, accent: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var part by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    Column(Modifier.fillMaxWidth()) {
        Head("按心情")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Feels.forEach { f ->
                Chip(if (busy == f.name) "找歌中…" else f.name, false) {
                    if (busy != null) return@Chip
                    busy = f.name
                    scope.launch {
                        Free.playMood(ctx, f.name)?.let { toast(it) }
                        busy = null
                    }
                }
            }
        }

        Row(Modifier.padding(top = 26.dp, bottom = 4.dp)) {
            listOf("免费歌曲", "网络电台").forEachIndexed { i, label ->
                Text(
                    label, fontSize = if (part == i) 16.sp else 15.sp, color = if (part == i) Wg.ink else Wg.ink3,
                    fontWeight = if (part == i) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(end = 22.dp).clickable { part = i },
                )
            }
        }
        if (part == 0) FreePart(now, accent) else StationPart(now, accent)
    }
}

@Composable
private fun FreePart(now: Now?, accent: Color) {
    val ctx = LocalContext.current
    var g by rememberSaveable { mutableStateOf("Lo-Fi") }
    var list by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(g) { list = null; list = Free.genre(g) }
    Text("Audius 上音乐人免费公开的整首歌，以独立音乐为主", fontSize = 12.sp, color = Wg.ink3, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Free.genres.forEach { (k, name) -> Chip(name, k == g) { g = k } }
    }
    val l = list
    when {
        l == null -> Quiet("加载中…")
        l.isEmpty() -> Quiet("连不上免费曲库，过会儿再试")
        else -> Column(Modifier.padding(top = 10.dp)) {
            l.forEachIndexed { i, s -> SongRow(s, now, accent, null) { if (!Free.play(ctx, l, i)) Toast.makeText(ctx, "微光的播放器没起来", Toast.LENGTH_SHORT).show() } }
        }
    }
}

@Composable
private fun StationPart(now: Now?, accent: Color) {
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Radio.Station>?>(null) }
    val favs = remember(tick) { Radio.favs() }
    LaunchedEffect(q) {
        list = null
        if (q.isNotBlank()) delay(500)
        list = if (q.isBlank()) Radio.chinese() else Radio.search(q.trim())
    }
    SearchBox(q, "搜电台名，比如 音乐、古典、AsiaFM") { q = it }
    if (q.isBlank() && favs.isNotEmpty()) {
        Head("收藏")
        favs.forEachIndexed { i, st -> StationRow(st, now, accent, true, { Radio.toggleFav(st); tick++ }) { Radio.play(ctx, favs, i) } }
    }
    Head(if (q.isBlank()) "中文音乐台" else "搜索结果")
    val l = list
    when {
        l == null -> Quiet("加载中…")
        l.isEmpty() -> Quiet(if (q.isBlank()) "连不上电台目录，过会儿再试" else "没搜到")
        else -> l.forEachIndexed { i, st ->
            StationRow(st, now, accent, favs.any { it.url == st.url }, { Radio.toggleFav(st); tick++ }) { Radio.play(ctx, l, i) }
        }
    }
}

@Composable
private fun StationRow(st: Radio.Station, now: Now?, accent: Color, fav: Boolean, onFav: () -> Unit, onClick: () -> Unit) {
    val playing = now?.title == st.name && now.artist.startsWith("电台")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .background(if (playing) accent.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (playing) accent else Wg.lav.copy(alpha = 0.5f)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(st.name, fontSize = 15.sp, color = if (playing) accent else Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (st.tag.isNotEmpty()) Text(st.tag, fontSize = 12.sp, color = Wg.ink3, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Text(if (fav) "★" else "☆", fontSize = 18.sp, color = if (fav) Wg.warm else Wg.ink3, modifier = Modifier.clickable(onClick = onFav).padding(horizontal = 8.dp))
    }
}

@Composable
private fun Head(s: String) {
    Text(s, fontSize = 13.sp, color = Wg.ink2, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, color = if (on) Color.White else Wg.ink2,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) Wg.warm else Color.White.copy(alpha = 0.6f))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 7.dp),
    )
}
