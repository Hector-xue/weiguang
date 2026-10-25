package com.ivyea.weiguang.ui

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
import com.ivyea.weiguang.core.Ncm
import com.ivyea.weiguang.core.Now
import com.ivyea.weiguang.core.NowPlaying
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Spectrum
import kotlinx.coroutines.delay

@Composable
fun NowScreen(now: Now?, mood: Mood, time: State<Float>, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val bands = Spectrum.bands.collectAsState()
    var granted by remember { mutableStateOf(Spectrum.granted(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) { Prefs.spectrum = true; Spectrum.start(ctx) }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp).padding(bottom = 92.dp),
    ) {
        TopBar(now, onSettings)
        if (now == null) {
            EmptyNow(time)
            return@Column
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            SpectrumRing(Modifier.fillMaxSize(), bands, { Spectrum.live }, now.playing, mood.accent, time)
            Cover(now.art, now.playing, mood.accent)
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
        Text(
            now.title.ifEmpty { "未知歌曲" },
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            now.artist, fontSize = 14.sp, color = Wg.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        // 网易云实时报的当前这句歌词
        Crossfade(targetState = now.lyric ?: "", animationSpec = tween(450), label = "lyric", modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(30.dp)) { line ->
            Text(
                line, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = mood.accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(14.dp))
        Progress(now, mood.accent)
        Spacer(Modifier.height(10.dp))
        Controls(now, mood.accent) { Ncm.openApp(ctx) }
    }
}

@Composable
private fun TopBar(now: Now?, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.logo_mark), contentDescription = null,
            modifier = Modifier.size(28.dp).shadow(10.dp, CircleShape, spotColor = Wg.warm).clip(CircleShape),
        )
        Text("微光", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, modifier = Modifier.padding(start = 10.dp))
        val status = when {
            now == null -> "等待网易云"
            now.playing -> "网易云 · 在听"
            else -> "网易云 · 暂停"
        }
        Row(Modifier.padding(start = 10.dp).glass(999.dp, 2.dp).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (now?.playing == true) Wg.ok else Wg.ink3))
            Text(status, fontSize = 12.sp, color = Wg.ink2, modifier = Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = Wg.ink2)
        }
    }
}

@Composable
private fun Cover(art: Bitmap?, playing: Boolean, accent: Color) {
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
            else Glyph(G.Heart, if (now.hasLike) accent else Wg.ink3, Modifier.size(24.dp))
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
            Text("网易云", fontSize = 12.sp, color = Wg.ink2)
        }
    }
}

@Composable
private fun EmptyNow(time: State<Float>) {
    val ctx = LocalContext.current
    val installed = remember { Ncm.version(ctx) != null }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ParticleOrb(Modifier.fillMaxWidth().height(300.dp), time, { 0.2f })
        Text(if (installed) "网易云还没在放歌" else "还没装网易云", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
        Text(
            if (installed) "放一首，微光就在这儿陪你听" else "微光陪你听网易云的歌，先装一个吧",
            fontSize = 14.sp, color = Wg.ink2, modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier.shadow(14.dp, RoundedCornerShape(999.dp), spotColor = Wg.warm).clip(RoundedCornerShape(999.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFF6AB86), Wg.warm)))
                .clickable { if (installed) Ncm.openApp(ctx) else Ncm.openStore(ctx) }
                .padding(horizontal = 28.dp, vertical = 13.dp),
        ) {
            Text(if (installed) "打开网易云" else "去下载", color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.width(1.dp).height(60.dp))
    }
}
