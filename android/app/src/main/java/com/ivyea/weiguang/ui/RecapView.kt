package com.ivyea.weiguang.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.Card
import com.ivyea.weiguang.core.NowPlaying
import com.ivyea.weiguang.core.Nightly
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Recap
import com.ivyea.weiguang.core.Recaps

// 首页一行：这周你是哪种听歌的人，点开看整张回顾卡
@Composable
fun RecapRow(r: Recap, accent: Color, label: String = "这周你是", onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(20.dp, 3.dp).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.5.sp, color = Wg.ink3)
                Text("「${r.persona}」", fontFamily = Wg.serif, fontSize = 17.sp, color = Wg.ink, modifier = Modifier.padding(start = 2.dp))
            }
            Text(r.line, fontSize = 12.5.sp, color = Wg.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Text("回顾 →", fontSize = 13.sp, color = accent, modifier = Modifier.padding(start = 10.dp))
    }
}

// 整张回顾卡：和分享出去的图一模一样；上面切本周 / 本月
@Composable
fun RecapSheet(week: Recap, accent: Color, month0: Boolean = false, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val art = NowPlaying.now.value?.art
    var monthly by remember { mutableStateOf(month0) }
    val month = remember { Recaps.month() }
    val r = if (monthly) month else week
    val bmp = remember(r) { Card.week(r, art) }
    Sheet(onClose) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(false to "本周", true to month.title.removeSuffix("听歌")).forEach { (m, label) ->
                val sel = m == monthly
                Text(
                    label, fontSize = 13.sp, color = if (sel) Wg.ink else Wg.ink3,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) Color.White.copy(alpha = 0.8f) else Color.Transparent)
                        .clickable { monthly = m }.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        Image(
            bmp.asImageBitmap(), null,
            Modifier.fillMaxWidth().aspectRatio(1080f / 1440f).clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Fit,
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Text("所有音乐 App 合在一起算，只在手机里", fontSize = 11.5.sp, color = Wg.ink3, modifier = Modifier.weight(1f))
            Pill("分享", color = Wg.warm) { Card.share(ctx, bmp, if (monthly) "month" else "week") }
        }
    }
}

// 首页问一次：每晚给你一首今天的歌？点「好」才申请通知权限并打开；「不用」就再也不问（设置里随时能开）
@Composable
fun NightlyOffer(accent: Color, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var gone by remember { mutableStateOf(false) }
    val enable = {
        Prefs.nightly = true; Prefs.nightlyAsked = true; Nightly.schedule(ctx); onChange(true); gone = true
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) enable() else { Prefs.nightlyAsked = true; gone = true } }
    if (gone) return
    Row(
        Modifier.fillMaxWidth().glass(20.dp, 3.dp).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("每晚 ${Nightly.label()}，给你一首今天的歌", fontSize = 13.5.sp, color = Wg.ink)
            Text("周日是本周回顾，月初是上个月的", fontSize = 11.5.sp, color = Wg.ink3, modifier = Modifier.padding(top = 3.dp))
        }
        Text("不用", fontSize = 13.sp, color = Wg.ink3, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { Prefs.nightlyAsked = true; gone = true }.padding(horizontal = 10.dp, vertical = 6.dp))
        Pill("好", color = accent) {
            if (Nightly.granted(ctx)) enable() else ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
