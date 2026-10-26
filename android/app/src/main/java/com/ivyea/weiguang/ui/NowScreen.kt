package com.ivyea.weiguang.ui

import com.ivyea.weiguang.core.Library
import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.R
import com.ivyea.weiguang.core.Buddy
import com.ivyea.weiguang.core.Cloud
import com.ivyea.weiguang.core.LrcLine
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import android.os.SystemClock
import com.ivyea.weiguang.core.Players
import com.ivyea.weiguang.core.Now
import com.ivyea.weiguang.core.NowPlaying
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Spectrum
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NowScreen(now: Now?, mood: Mood, time: State<Float>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheetLine by remember { mutableStateOf<String?>(null) }
    var sheetText by remember { mutableStateOf<String?>(null) }
    var sheetAt by remember { mutableStateOf<Long?>(null) }
    val onExplain: (String, Long?) -> Unit = { line, at ->
        sheetLine = line; sheetText = null; sheetAt = at
        scope.launch { sheetText = Buddy.explain(line) }
    }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val lines = rememberLyrics(now?.nid)
    val pos = rememberPosition(now)
    val idx by remember(lines) { derivedStateOf { lines?.let { idxAt(it, pos.value) } ?: -1 } }
    val bands = Spectrum.bands.collectAsState()
    var granted by remember { mutableStateOf(Spectrum.granted(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) { Prefs.spectrum = true; Spectrum.start(ctx) }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp).padding(bottom = 92.dp),
    ) {
        TopBar(now)
        if (now == null) {
            EmptyNow(time)
            return@Column
        }
        val cur = (if (idx >= 0) lines?.getOrNull(idx)?.text else null) ?: now.lyric?.takeIf { lines.isNullOrEmpty() }
        val next = if (lines != null && idx + 1 in lines.indices) lines[idx + 1].text else null
        val canLyrics = !lines.isNullOrEmpty()
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Crossfade(targetState = showLyrics && canLyrics, animationSpec = tween(500), label = "view", modifier = Modifier.fillMaxSize()) { full ->
                if (full) {
                    Box(Modifier.fillMaxSize()) {
                        LyricsView(lines!!, idx, mood.accent, Modifier.fillMaxSize()) { l -> onExplain(l.text, l.t) }
                        Text(
                            "封面", fontSize = 12.sp, color = Wg.ink2,
                            modifier = Modifier.align(Alignment.TopEnd).glass(999.dp, 2.dp).clickable { showLyrics = false }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SpectrumRing(Modifier.fillMaxSize(), bands, { Spectrum.live }, now.playing, mood.accent, time)
                        Cover(now.art, now.playing, mood.accent) { if (canLyrics) showLyrics = true }
                        if (!granted && Prefs.spectrum) {
                            Text(
                                "让粒子跟着音乐跳 →",
                                fontSize = 12.sp, color = Wg.lav,
                                modifier = Modifier.align(Alignment.BottomCenter).glass(999.dp, 4.dp)
                                    .clickable { ask.launch(Manifest.permission.RECORD_AUDIO) }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
        Text(
            now.title.ifEmpty { "未知歌曲" },
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            now.artist, fontSize = 14.sp, color = Wg.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        // 封面视图下给两行：当前这句 + 下一句；点当前句让微光解读，点下一句展开整首
        if (!(showLyrics && canLyrics)) {
            Crossfade(targetState = cur ?: "", animationSpec = tween(450), label = "lyric", modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(28.dp)) { line ->
                Text(
                    line, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = mood.accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = Buddy.isLyric(line)) { onExplain(line, lines?.getOrNull(idx)?.t) },
                )
            }
            Text(
                next ?: "", fontSize = 13.sp, color = Wg.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().height(20.dp).clickable(enabled = canLyrics) { showLyrics = true },
            )
        }
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
            if (Buddy.isLyric(cur)) {
                Text("✦ 解读这句", fontSize = 12.sp, color = Wg.lav, modifier = Modifier.clickable { onExplain(cur!!, lines?.getOrNull(idx)?.t) }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Progress(now, mood.accent)
        Spacer(Modifier.height(10.dp))
        Controls(now, mood.accent) { Players.openApp(ctx, now.pkg) }
    }
    sheetLine?.let { line ->
        Box(
            Modifier.fillMaxSize().background(Color(0x33342F55)).clickable { sheetLine = null },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 104.dp).glass(24.dp, 16.dp)
                    .background(Color.White.copy(alpha = 0.6f)).clickable(enabled = false) {}.padding(20.dp),
            ) {
                Text("「$line」", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = mood.accent, lineHeight = 24.sp)
                Text(
                    sheetText ?: "微光在想…", fontSize = 14.sp, color = if (sheetText == null) Wg.ink3 else Wg.ink, lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
                sheetAt?.let { at ->
                    Text(
                        "从这句开始放", fontSize = 13.sp, color = mood.accent, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(999.dp)).background(mood.accent.copy(alpha = 0.12f))
                            .clickable { NowPlaying.seekTo(at); sheetLine = null }.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun TopBar(now: Now?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.logo_mark), contentDescription = null,
            modifier = Modifier.size(28.dp).shadow(10.dp, CircleShape, spotColor = Wg.warm).clip(CircleShape),
        )
        Text("微光", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, modifier = Modifier.padding(start = 10.dp))
        val status = when {
            now == null -> "等待音乐 App"
            now.playing -> "${Players.nameOf(now.pkg)} · 在听"
            else -> "${Players.nameOf(now.pkg)} · 暂停"
        }
        Row(Modifier.padding(start = 10.dp).glass(999.dp, 2.dp).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (now?.playing == true) Wg.ok else Wg.ink3))
            Text(status, fontSize = 12.sp, color = Wg.ink2, modifier = Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Cover(art: Bitmap?, playing: Boolean, accent: Color, onClick: () -> Unit) {
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) withFrameNanos {
            angle.floatValue = (angle.floatValue + (it - last) / 1e9f * 6f) % 360f
            last = it
        }
    }
    val img = remember(art) { art?.asImageBitmap() }
    Box(
        Modifier.size(208.dp)
            .shadow(28.dp, CircleShape, ambientColor = accent, spotColor = accent)
            .graphicsLayer { rotationZ = angle.floatValue }
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(Brush.sweepGradient(listOf(Color(0xFFC8C2F2), Color(0xFFF6C9B8), Color(0xFFFFE2A8), Color(0xFFA8D4F5), Color(0xFFC8C2F2))))
            .border(5.dp, Color.White.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFFFF8F0)).border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape))
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Progress(now: Now, accent: Color) {
    var pos by remember { mutableLongStateOf(now.positionNow()) }
    LaunchedEffect(now) {
        while (true) { pos = now.positionNow(); delay(200) }
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = now.durationMs.coerceAtLeast(1L)
    val frac = drag ?: (pos.toFloat() / dur).coerceIn(0f, 1f)

    Box(
        Modifier.fillMaxWidth().height(30.dp)
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { NowPlaying.seekTo((it * dur).toLong()) }; drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(dur) {
                detectTapGestures { o -> NowPlaying.seekTo(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val y = size.height / 2
            val x = size.width * frac
            drawLine(accent.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(Brush.horizontalGradient(listOf(Wg.warm2, accent)), Offset(0f, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(accent.copy(alpha = 0.25f), (if (drag != null) 11 else 8).dp.toPx(), Offset(x, y))
            drawCircle(accent, 4.5.dp.toPx(), Offset(x, y))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(fmt((frac * dur).toLong()), fontSize = 11.sp, color = Wg.ink3)
        Spacer(Modifier.weight(1f))
        Text(fmt(now.durationMs), fontSize = 11.sp, color = Wg.ink3)
    }
}

@Composable
private fun RoundBtn(size: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Controls(now: Now, accent: Color, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        RoundBtn(48, { NowPlaying.like() }) {
            val icon = remember(now.likeIcon) { now.likeIcon?.asImageBitmap() }
            if (icon != null) Image(icon, contentDescription = "喜欢", colorFilter = ColorFilter.tint(accent), modifier = Modifier.size(26.dp))
            else {
                Library.state.collectAsState().value
                val fav = Library.isFav(now.songId)
                Glyph(if (fav) G.HeartFill else G.Heart, if (fav || now.hasLike) accent else Wg.ink3, Modifier.size(24.dp))
            }
        }
        RoundBtn(52, { NowPlaying.prev() }) { Glyph(G.Prev, Wg.ink, Modifier.size(24.dp)) }
        Box(
            Modifier.size(68.dp).shadow(18.dp, CircleShape, ambientColor = accent, spotColor = accent)
                .clip(CircleShape).background(Color.White).clickable { NowPlaying.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(if (now.playing) G.Pause else G.Play, accent, Modifier.size(28.dp))
        }
        RoundBtn(52, { NowPlaying.next() }) { Glyph(G.Next, Wg.ink, Modifier.size(24.dp)) }
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onOpen).padding(horizontal = 6.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(Players.nameOf(now.pkg), fontSize = 12.sp, color = Wg.ink2)
        }
    }
}

@Composable
private fun EmptyNow(time: State<Float>) {
    val ctx = LocalContext.current
    val installed = remember { Players.target(ctx) != null }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ParticleOrb(Modifier.fillMaxWidth().height(300.dp), time, { 0.2f })
        Text(if (installed) "音乐 App 还没在放歌" else "还没装音乐 App", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
        Text(
            if (installed) "在网易云、QQ音乐、酷狗、酷我或汽水里放一首，微光就在这儿陪你听" else "网易云、QQ音乐、酷狗、酷我、汽水都行，先装一个吧",
            fontSize = 14.sp, color = Wg.ink2, textAlign = TextAlign.Center, lineHeight = 21.sp,
            modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp),
        )
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier.shadow(14.dp, RoundedCornerShape(999.dp), spotColor = Wg.warm).clip(RoundedCornerShape(999.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFF6AB86), Wg.warm)))
                .clickable { if (installed) Players.openApp(ctx) else Players.openStore(ctx) }
                .padding(horizontal = 28.dp, vertical = 13.dp),
        ) {
            Text(if (installed) "打开${Players.nameOf(Players.target(ctx))}" else "去下载", color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.width(1.dp).height(60.dp))
    }
}

// 整首歌词走网易云网页接口（别的 App 的歌先对上网易云 ID）；拿不到（断网）隔几秒再试两次，还不行就只显示会话里报的当前这句
@Composable
private fun rememberLyrics(id: Long?): List<LrcLine>? = produceState<List<LrcLine>?>(null, id) {
    value = null
    if (id == null) return@produceState
    repeat(3) {
        Cloud.lyric(id)?.let { value = it; return@produceState }
        delay(4000)
    }
}.value

@Composable
private fun rememberPosition(now: Now?): State<Long> = produceState(now?.positionNow() ?: 0L, now) {
    if (now == null) return@produceState
    while (true) { value = now.positionNow(); delay(120) }
}

// 最后一句开始时间 <= 当前进度的那句；稍微提前 250ms，跟耳朵对得上
private fun idxAt(lines: List<LrcLine>, pos: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    val p = pos + 250
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (lines[mid].t <= p) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

// 滚动歌词：当前句居中放大；手指划过以后 3 秒内不抢着滚回去
@Composable
private fun LyricsView(lines: List<LrcLine>, idx: Int, accent: Color, modifier: Modifier, onTap: (LrcLine) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = idx.coerceAtLeast(0))
    var userAt by remember { mutableLongStateOf(0L) }
    var auto by remember { mutableStateOf(false) }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress && !auto) userAt = SystemClock.uptimeMillis() }
    LaunchedEffect(idx) {
        if (SystemClock.uptimeMillis() - userAt < 3000) return@LaunchedEffect
        auto = true
        try { list.animateScrollToItem(idx.coerceAtLeast(0)) } finally { auto = false }
    }
    BoxWithConstraints(modifier) {
        val h = maxHeight
        LazyColumn(
            Modifier.fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
            state = list,
            contentPadding = PaddingValues(top = h * 0.4f, bottom = h * 0.55f),
        ) {
            itemsIndexed(lines) { i, l ->
                val on = i == idx
                val near = kotlin.math.abs(i - idx) <= 2
                val scale by animateFloatAsState(if (on) 1f else 0.86f, tween(380), label = "s")
                val alpha by animateFloatAsState(if (on) 1f else if (near) 0.5f else 0.28f, tween(380), label = "a")
                Column(
                    Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                        .clip(RoundedCornerShape(14.dp)).clickable { onTap(l) }.padding(horizontal = 8.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        l.text, fontSize = 19.sp, lineHeight = 27.sp, textAlign = TextAlign.Center,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) accent else Wg.ink,
                    )
                    l.tr?.let {
                        Text(it, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, color = if (on) accent.copy(alpha = 0.8f) else Wg.ink2, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
