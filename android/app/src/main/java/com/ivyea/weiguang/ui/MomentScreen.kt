package com.ivyea.weiguang.ui

import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.R
import com.ivyea.weiguang.core.Ai
import com.ivyea.weiguang.core.Buddy
import com.ivyea.weiguang.core.Feel
import com.ivyea.weiguang.core.Feels
import com.ivyea.weiguang.core.Song
import com.ivyea.weiguang.core.rememberArt
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.Msg
import com.ivyea.weiguang.core.Players
import com.ivyea.weiguang.core.Now
import com.ivyea.weiguang.core.NowPlaying
import com.ivyea.weiguang.core.Spectrum
import kotlinx.coroutines.launch

private val suggestions = listOf("来点安静的", "想听点开心的", "有点累", "随便放一首")

// 此刻：微光在想什么 + 光球 + 正在放的歌 + 和微光聊天点歌
@Composable
fun MomentScreen(now: Now?, accent: Color, time: State<Float>, onOpenPlayer: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs by Buddy.msgs.collectAsState()
    val busy by Buddy.busy.collectAsState()
    val thought by Buddy.thought.collectAsState()
    val radio by Buddy.radio.collectAsState()
    val bands = Spectrum.bands.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val send: (String) -> Unit = { t -> if (t.isNotBlank()) { input = ""; scope.launch { Buddy.ask(t) } } }

    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size + (if (busy) 1 else 0)) }

    var sheet by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 22.dp).padding(bottom = 92.dp)) {
        // 顶栏：时段 + 在听哪个 App；开着电台时一个小标签；聊起来以后能清空
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(daypart(), fontSize = 13.sp, color = Wg.ink2)
            Box(Modifier.padding(start = 10.dp).size(5.dp).clip(CircleShape).background(if (now?.playing == true) Wg.ok else Wg.ink3))
            Text(
                when { busy -> "在想…"; now?.playing == true -> "${Players.nameOf(now.pkg)} · 在听"; now != null -> "${Players.nameOf(now.pkg)} · 暂停"; else -> "在这儿" },
                fontSize = 13.sp, color = Wg.ink3, modifier = Modifier.padding(start = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            radio?.let { f ->
                Row(
                    Modifier.padding(end = 4.dp).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.7f))
                        .clickable { Buddy.stopQueue() }.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(feelColor(f.v, f.e)))
                    Text("${f.name}电台 · 停", fontSize = 12.sp, color = Wg.ink2, modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (msgs.isNotEmpty()) Text("清空", fontSize = 13.sp, color = Wg.ink3, modifier = Modifier.clickable { Buddy.clearChat() }.padding(6.dp))
        }

        // 光球 + 微光的心里话（宋体大字）；聊起来以后收成一行，把位置让给对话
        val compact = msgs.isNotEmpty()
        val energy = {
            val b = bands.value
            val live = if (Spectrum.live && now?.playing == true) b.take(12).average().toFloat() else 0f
            if (busy) 0.9f else 0.25f + live
        }
        if (compact) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ParticleOrb(Modifier.size(60.dp), time, energy, accent)
                Text(
                    thought ?: "", fontFamily = Wg.serif, fontSize = 14.5.sp, lineHeight = 21.sp, color = Wg.ink2,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
            }
        } else {
            Box(Modifier.fillMaxWidth()) {
                ParticleOrb(Modifier.size(180.dp).align(Alignment.TopEnd).offset(x = 46.dp, y = (-18).dp), time, energy, accent)
                Column(Modifier.padding(top = 70.dp, end = 30.dp, bottom = 22.dp)) {
                    Eyebrow("微光在想", color = accent)
                    Text(
                        thought ?: "今天想听点什么？", fontFamily = Wg.serif, fontSize = 23.sp, lineHeight = 35.sp,
                        fontWeight = FontWeight.Medium, color = Wg.ink, modifier = Modifier.padding(top = 10.dp),
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    if (!Ai.ready) {
                        Text(
                            "填上 AI 密钥，微光会更懂你 →", fontSize = 12.sp, color = Wg.lav,
                            modifier = Modifier.padding(top = 10.dp).clickable(onClick = onOpenSettings),
                        )
                    }
                }
            }
        }

        if (now != null) MiniPlayer(now, accent, onOpenPlayer)

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
            state = list,
            verticalArrangement = Arrangement.spacedBy(if (msgs.isEmpty()) 26.dp else 10.dp),
        ) {
            if (msgs.isEmpty()) {
                item { FeelCard { f -> scope.launch { Buddy.feel(f) } } }
                item { MakeRow(onHum = { sheet = "hum" }, onGen = { sheet = "gen" }) }
                item { Habit(accent) }
            }
            items(msgs) { m -> Bubble(m, accent) }
            if (busy) item { Typing() }
        }

        if (!busy && msgs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "✦ 作一首", fontSize = 12.5.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Wg.lav)
                        .clickable { sheet = "gen" }.padding(horizontal = 12.dp, vertical = 7.dp),
                )
                suggestions.forEach { s ->
                    Text(
                        s, fontSize = 12.5.sp, color = Color(0xFF6A66A8),
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.55f))
                            .clickable { send(s) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().glass(999.dp, 4.dp).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (input.isEmpty()) Text("说点什么，比如「下雨了」", fontSize = 14.sp, color = Wg.ink3)
                BasicTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Wg.ink),
                    cursorBrush = SolidColor(Wg.warm),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (input.isEmpty()) {
                Text(
                    "哼", fontSize = 14.sp, color = Wg.lav, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 6.dp).size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f))
                        .clickable { sheet = "hum" }.padding(top = 7.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFF6AB86), Wg.warm)))
                    .clickable(enabled = !busy) { send(input) },
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    when (sheet) {
        "hum" -> HumSheet(time, accent, { sheet = "" }, onOpenSettings)
        "gen" -> GenSheet(time, accent, Buddy.radio.value?.say ?: msgs.lastOrNull { it.me }?.text ?: "", { sheet = "" }, onOpenSettings)
    }
    }
}

// 正在放的歌：封面 + 宋体歌名 + 当前这句歌词，底下一道细细的进度光
@Composable
private fun MiniPlayer(now: Now, accent: Color, onOpen: () -> Unit) {
    val img = remember(now.art) { now.art?.asImageBitmap() }
    val frac by produceState(0f, now) {
        while (true) { value = if (now.durationMs > 0) (now.positionNow().toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f; delay(500) }
    }
    Column(Modifier.fillMaxWidth().glass(22.dp, 8.dp).clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).shadow(10.dp, RoundedCornerShape(14.dp), spotColor = accent).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFC8C2F2), Color(0xFFFFE2A8)))),
            ) {
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(now.title, fontFamily = Wg.serif, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val lyric = Buddy.isLyric(now.lyric)
                Text(
                    if (lyric) now.lyric!! else now.artist,
                    fontSize = 12.5.sp, color = if (lyric) accent else Wg.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Box(
                Modifier.size(42.dp).shadow(8.dp, CircleShape, spotColor = accent).clip(CircleShape).background(Color.White).clickable { NowPlaying.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(if (now.playing) G.Pause else G.Play, accent, Modifier.size(16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 1.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(accent.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth(frac).height(2.dp).background(Brush.horizontalGradient(listOf(Wg.warm2, accent))))
        }
    }
}

@Composable
private fun Bubble(m: Msg, accent: Color) {
    val ctx = LocalContext.current
    val songs = Library.state.collectAsState().value.songs
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.me) Alignment.End else Alignment.Start) {
        Text(
            m.text, fontSize = 14.sp, lineHeight = 21.sp, color = if (m.me) Color.White else Wg.ink,
            modifier = Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(topStart = if (m.me) 18.dp else 6.dp, topEnd = if (m.me) 6.dp else 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(if (m.me) Brush.linearGradient(listOf(Color(0xFFF6AB86), Wg.warm)) else SolidColor(Color.White.copy(alpha = 0.85f)))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (m.songs.isNotEmpty()) {
            Column(Modifier.padding(top = 6.dp).widthIn(max = 300.dp).glass(16.dp, 2.dp).padding(vertical = 4.dp)) {
                m.songs.forEach { id ->
                    val s = songs[id] ?: return@forEach
                    Row(
                        Modifier.fillMaxWidth().clickable { Players.play(ctx, s) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(s, 30)
                        Text(s.title, fontSize = 13.5.sp, color = Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false))
                        if (s.artist.isNotEmpty()) Text(" · ${s.artist}", fontSize = 12.sp, color = Wg.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val q by Buddy.queue.collectAsState()
                val radio by Buddy.radio.collectAsState()
                if (m.songs.size >= 3) {
                    val on = q == m.songs && radio == null
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) Buddy.stopQueue() else Buddy.startQueue(ctx, m.songs) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(if (on) G.Pause else G.Play, accent, Modifier.size(13.dp))
                        Text(if (on) "正在按顺序播放 · 点一下停止" else "按顺序播放这 ${m.songs.size} 首", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                Feels.firstOrNull { it.name == m.feel }?.let { f ->
                    val on = radio == f && q.take(m.songs.size) == m.songs
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) Buddy.stopQueue() else Buddy.startRadio(ctx, f, m.songs) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(feelColor(f.v, f.e)))
                        Text(
                            if (on) "${f.name}电台放着呢 · 点一下停止" else "一直放下去 · ${f.name}电台",
                            fontSize = 13.sp, color = accent, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Typing() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.clip(RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)).background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (i in 0 until 3) {
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(6.dp).clip(CircleShape).background(Wg.ink2.copy(alpha = a)))
        }
    }
}

@Composable
fun Thumb(s: Song, sizeDp: Int) {
    val img = rememberArt(s.pic, if (sizeDp > 48) 300 else 120)
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp / 4).dp))
            .background(Brush.linearGradient(listOf(feelColor(s.v ?: 0.5f, s.e ?: 0.5f).copy(alpha = 0.55f), Color(0xFFFFE2A8)))),
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

// 此刻的心情：八颗颜色不同的光，点一颗，微光按情绪从曲库里排一段路；低落/疲惫/烦躁会先接住、再慢慢带出来
@Composable
private fun FeelCard(onFeel: (Feel) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Eyebrow("此刻的心情")
        Spacer(Modifier.height(14.dp))
        Feels.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                row.forEach { f ->
                    val c = feelColor(f.v, f.e)
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { onFeel(f) }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(46.dp).background(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), c.copy(alpha = 0.18f), Color.Transparent))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(20.dp).shadow(6.dp, CircleShape, spotColor = c).clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(Color.White, c), radius = 30f)),
                            )
                        }
                        Text(f.name, fontSize = 13.sp, color = Wg.ink, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

// 这个点你常听：从听歌流水里学出来的，不用 AI
@Composable
private fun Habit(accent: Color) {
    val ctx = LocalContext.current
    val lib by Library.state.collectAsState()
    val songs = remember(lib.plays.size, lib.songs.size) { Buddy.habit(6) }
    if (songs.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Eyebrow("这个点你常听", color = accent)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            songs.forEach { s ->
                Column(Modifier.width(108.dp).clip(RoundedCornerShape(18.dp)).clickable { Players.play(ctx, s) }) {
                    Box(Modifier.shadow(8.dp, RoundedCornerShape(18.dp), spotColor = accent)) { Thumb(s, 108) }
                    Text(s.title, fontFamily = Wg.serif, fontSize = 14.sp, color = Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
                    Text(s.artist, fontSize = 11.5.sp, color = Wg.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 1.dp))
                }
            }
        }
    }
}

// 哼一段认歌 / 让微光作一首：一条玻璃，中间一道发丝线
@Composable
private fun MakeRow(onHum: () -> Unit, onGen: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(22.dp, 4.dp).height(IntrinsicSize.Min)) {
        listOf(Triple("哼一段", "想不起歌名，哼给我听", onHum), Triple("作一首", "按此刻的心情写首歌", onGen)).forEachIndexed { i, (t, sub, f) ->
            if (i == 1) Box(Modifier.padding(vertical = 14.dp).width(0.6.dp).fillMaxHeight().background(Wg.hair))
            Row(Modifier.weight(1f).clickable(onClick = f).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                val c = if (i == 0) Wg.lav else Wg.warm
                Box(Modifier.size(34.dp).clip(CircleShape).background(c.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Text(if (i == 0) "♪" else "✦", fontSize = 15.sp, color = c)
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(t, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
                    Text(sub, fontSize = 11.sp, color = Wg.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

// 时段：清晨 / 上午 / 午后 / 傍晚 / 夜里 / 深夜，前面带星期
private fun daypart(): String {
    val c = java.util.Calendar.getInstance()
    val h = c.get(java.util.Calendar.HOUR_OF_DAY)
    val d = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    val p = when (h) { in 5..7 -> "清晨"; in 8..10 -> "上午"; in 11..12 -> "中午"; in 13..16 -> "午后"; in 17..18 -> "傍晚"; in 19..22 -> "夜里"; else -> "深夜" }
    return "$d$p"
}
