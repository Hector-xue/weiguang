package com.ivyea.weiguang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.Ai
import com.ivyea.weiguang.core.Buddy
import com.ivyea.weiguang.core.LibState
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.Players
import com.ivyea.weiguang.core.Now
import com.ivyea.weiguang.core.Song
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.sin

private val cool = Color(0xFF8FA8E8)
private val hot = Color(0xFFF29A72)

fun feelColor(v: Float, e: Float): Color = lerp(lerp(cool, Color(0xFFB088E0), e), lerp(Color(0xFF9FD0C0), hot, e), v)

private fun starColor(s: Song): Color = feelColor(s.v ?: 0.5f, s.e ?: 0.5f)

// 情绪星图：横轴明亮、纵轴能量。点星星放这首；手指画一条线，线经过的歌按顺序连成歌单
@Composable
fun StarMap(lib: LibState, now: Now?, accent: Color, time: State<Float>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tagging by Buddy.tagging.collectAsState()
    val tagMsg by Buddy.tagMsg.collectAsState()
    val queue by Buddy.queue.collectAsState()
    val qIdx by Buddy.qIdx.collectAsState()
    // 纵轴用合并后的能量（AI 估的 + 开着微光听时实测的）
    val stars = remember(lib.songs) { lib.songs.values.filter { it.v != null && it.e != null }.map { it.copy(e = Library.energy(it)) } }
    val measured = remember(lib.songs) { Library.measuredCount() }
    val untagged = remember(lib.songs) { lib.songs.values.count { it.v == null && it.title.isNotEmpty() } }
    val path = remember { mutableStateListOf<Offset>() }
    var picked by remember { mutableStateOf<List<Song>>(emptyList()) }
    var tapped by remember { mutableStateOf<Song?>(null) }

    Column(Modifier.fillMaxWidth()) {
        if (stars.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("把曲库排成一片星空", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
                Text(
                    "微光按明亮和能量给每首歌定个位置；画一条线，就是一张情绪渐进的歌单",
                    fontSize = 13.sp, color = Wg.ink2, textAlign = TextAlign.Center, lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp),
                )
            }
        }

        if (untagged > 0 || tagMsg.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).glass(18.dp, 2.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        tagMsg.isNotEmpty() -> tagMsg
                        !Ai.ready -> "给歌定位置要用 AI，先在设置里填密钥"
                        else -> "还有 $untagged 首没上星图"
                    },
                    fontSize = 13.sp, color = Wg.ink2, modifier = Modifier.weight(1f),
                )
                if (Ai.ready && !tagging && untagged > 0) {
                    Text(
                        "放上去", fontSize = 13.sp, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Brush.linearGradient(listOf(Color(0xFFF6AB86), Wg.warm)))
                            .clickable { scope.launch { Buddy.tag() } }.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        if (stars.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).glass(22.dp, 4.dp)) {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(stars) {
                            detectTapGestures { o ->
                                val w = size.width.toFloat(); val h = size.height.toFloat()
                                tapped = stars.minByOrNull { hypot(it.v!! * w - o.x, (1 - it.e!!) * h - o.y) }
                                    ?.takeIf { hypot(it.v!! * w - o.x, (1 - it.e!!) * h - o.y) < 28.dp.toPx() }
                            }
                        }
                        .pointerInput(stars) {
                            detectDragGestures(
                                onDragStart = { o -> path.clear(); path.add(o); picked = emptyList(); tapped = null },
                                onDragEnd = {
                                    // 沿线每一段找离得最近、还没选过的星，按经过的先后排
                                    val w = size.width.toFloat(); val h = size.height.toFloat()
                                    val r = 30.dp.toPx()
                                    val chosen = LinkedHashSet<Song>()
                                    for (p in path) {
                                        stars.filter { it !in chosen }
                                            .minByOrNull { hypot(it.v!! * w - p.x, (1 - it.e!!) * h - p.y) }
                                            ?.takeIf { hypot(it.v!! * w - p.x, (1 - it.e!!) * h - p.y) < r }
                                            ?.let { chosen += it }
                                        if (chosen.size >= 15) break
                                    }
                                    picked = chosen.toList()
                                },
                            ) { change, _ -> path.add(change.position) }
                        },
                ) {
                    val t = time.value
                    val w = size.width; val h = size.height
                    for (i in 1..3) {
                        drawLine(Wg.line, Offset(w * i / 4, 0f), Offset(w * i / 4, h), 1f)
                        drawLine(Wg.line, Offset(0f, h * i / 4), Offset(w, h * i / 4), 1f)
                    }
                    val pickedIds = picked.map { it.id }.toSet()
                    for (s in stars) {
                        val c = starColor(s)
                        val x = s.v!! * w; val y = (1 - s.e!!) * h
                        val tw = 0.55f + 0.45f * sin(t * 1.7f + (s.id % 97).toFloat())
                        val big = s.id in pickedIds
                        drawCircle(c.copy(alpha = 0.22f * tw), (if (big) 12f else 7f) * density, Offset(x, y))
                        drawCircle(c.copy(alpha = 0.6f + 0.4f * tw), (if (big) 3.6f else 2.4f) * density, Offset(x, y))
                    }
                    if (path.size > 1) {
                        val p = Path().apply { moveTo(path[0].x, path[0].y); for (i in 1 until path.size) lineTo(path[i].x, path[i].y) }
                        drawPath(p, Brush.linearGradient(listOf(cool, hot), path.first(), path.last()), style = Stroke(2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    // 正在放的那颗
                    stars.firstOrNull { it.id == now?.songId }?.let { s ->
                        val c = Offset(s.v!! * w, (1 - s.e!!) * h)
                        drawCircle(Brush.radialGradient(listOf(Color(1f, 0.9f, 0.75f, 0.95f), Color.Transparent), c, 26f * density), 26f * density, c)
                        drawCircle(accent, 4f * density, c)
                    }
                }
                Text("高能量", fontSize = 11.sp, color = Wg.ink3, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                Text("安静", fontSize = 11.sp, color = Wg.ink3, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                Text("明亮 →", fontSize = 11.sp, color = Wg.ink3, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
            }
            Text(
                "点星星放这首；手指画一条线，生成一张歌单。共 ${stars.size} 颗" +
                    if (measured >= 8) "，$measured 首按实际听感校正过能量" else "",
                fontSize = 12.sp, color = Wg.ink3, modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }

        tapped?.let { s ->
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).glass(18.dp, 2.dp).clickable { Players.play(ctx, s) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(starColor(s)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.title, fontSize = 15.sp, color = Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, fontSize = 12.sp, color = Wg.ink3, maxLines = 1)
                }
                Glyph(G.Play, accent, Modifier.size(16.dp))
            }
        }

        if (picked.isNotEmpty()) {
            val playing = queue.isNotEmpty() && queue == picked.map { it.id }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).glass(18.dp, 2.dp).padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (playing) "连播中 ${qIdx + 1}/${queue.size}" else "这条线上的 ${picked.size} 首",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (playing) "停止" else "按顺序播放", fontSize = 13.sp, color = if (playing) Wg.ink2 else Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (playing) Color.White.copy(alpha = 0.7f) else accent)
                            .clickable { if (playing) Buddy.stopQueue() else Buddy.startQueue(ctx, picked.map { it.id }) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                picked.forEachIndexed { i, s ->
                    val cur = playing && i == qIdx
                    Row(Modifier.fillMaxWidth().clickable { Players.play(ctx, s) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontSize = 12.sp, color = Wg.ink3, modifier = Modifier.size(22.dp))
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(starColor(s)))
                        Text(
                            s.title, fontSize = 14.sp, color = if (cur) accent else Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 10.dp).weight(1f),
                        )
                    }
                }
                Text(
                    "音乐 App 每播完一首，微光会接着放线上的下一首",
                    fontSize = 11.5.sp, color = Wg.ink3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}
