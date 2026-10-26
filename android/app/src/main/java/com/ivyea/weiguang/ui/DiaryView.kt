package com.ivyea.weiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.Ai
import com.ivyea.weiguang.core.Day
import com.ivyea.weiguang.core.Diary
import com.ivyea.weiguang.core.Feels
import com.ivyea.weiguang.core.LibState
import com.ivyea.weiguang.core.Players
import kotlinx.coroutines.launch
import java.util.Calendar

private val weekdays = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

private fun dayLabel(start: Long): String {
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val c = Calendar.getInstance().apply { timeInMillis = start }
    return when ((today - start + 3_600_000) / 86_400_000) {
        0L -> "今天"
        1L -> "昨天"
        else -> "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 ${weekdays[c.get(Calendar.DAY_OF_WEEK) - 1]}"
    }
}

// 听歌日记：一天一张卡。一串小圆点是这天按顺序听的每首歌（颜色=它在星图上的情绪），
// 选过的心情、听得最多的几首，最后是微光写的两句话
@Composable
fun DiaryView(lib: LibState, accent: Color) {
    val diary by Diary.state.collectAsState()
    val days = remember(lib.plays.size, diary.moods.size) { Diary.days(lib.plays, diary.moods) }
    if (days.isEmpty()) {
        Text(
            "听过的歌和选过的心情，会按天记在这里",
            fontSize = 13.sp, color = Wg.ink3, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        days.forEachIndexed { i, d -> DayCard(d, lib, diary.notes[d.key], accent, auto = i in 1..3) }
        Text(
            "只记最近 60 天里听过歌的日子；开着微光时听的歌也会顺手量一下能量，让星图更准",
            fontSize = 11.5.sp, color = Wg.ink3, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DayCard(d: Day, lib: LibState, note: String?, accent: Color, auto: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var writing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val write: () -> Unit = {
        if (!writing) {
            writing = true; err = null
            scope.launch {
                try { Diary.write(d) } catch (e: Ai.AiError) { err = e.message }
                writing = false
            }
        }
    }
    // 过去几天还没写的，打开就写上（今天的还在变，等用户点）
    LaunchedEffect(d.key) { if (auto && note == null && Ai.ready && d.plays.size >= 3) write() }

    Column(Modifier.fillMaxWidth().glass(20.dp, 4.dp).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayLabel(d.start), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, modifier = Modifier.weight(1f))
            Text(if (d.plays.isEmpty()) "没听歌" else "${d.plays.size} 首", fontSize = 12.5.sp, color = Wg.ink3)
        }
        if (d.plays.isNotEmpty()) {
            // 情绪轨迹：每首一个点，按时间排
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                d.plays.takeLast(36).forEach { p ->
                    val s = lib.songs[p.id]
                    val c = if (s?.v != null && s.e != null) feelColor(s.v, s.e) else Wg.ink3.copy(alpha = 0.35f)
                    Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                }
            }
        }
        if (d.moods.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("心情", fontSize = 12.sp, color = Wg.ink3)
                d.moods.map { it.name }.distinct().take(5).forEach { name ->
                    val f = Feels.firstOrNull { it.name == name }
                    Row(
                        Modifier.padding(start = 6.dp).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (f != null) Box(Modifier.size(6.dp).clip(CircleShape).background(feelColor(f.v, f.e)))
                        Text(name, fontSize = 11.5.sp, color = Wg.ink2, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
        val top = remember(d.plays.size) { d.top().take(3) }
        if (top.isNotEmpty()) Spacer(Modifier.height(8.dp))
        top.forEach { (id, n) ->
            val s = lib.songs[id] ?: return@forEach
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Players.play(ctx, s) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(s, 34)
                Text(s.title, fontSize = 13.5.sp, color = Wg.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false))
                Text(" · ${s.artist}", fontSize = 12.sp, color = Wg.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (n > 1) Text("×$n", fontSize = 12.sp, color = accent, modifier = Modifier.padding(start = 6.dp))
            }
        }
        when {
            note != null -> Text(
                "✦ $note", fontSize = 13.5.sp, color = Color(0xFF5B57A0), lineHeight = 21.sp,
                modifier = Modifier.padding(top = 10.dp).clickable(enabled = Ai.ready && d.plays.isNotEmpty(), onClick = write),
            )
            writing -> Text("微光在写…", fontSize = 13.sp, color = Wg.ink3, modifier = Modifier.padding(top = 10.dp))
            Ai.ready && d.plays.isNotEmpty() -> Text(
                err ?: "✦ 让微光写两句", fontSize = 13.sp, color = if (err != null) Wg.warm else Wg.lav,
                modifier = Modifier.padding(top = 10.dp).clickable(onClick = write),
            )
        }
    }
}
