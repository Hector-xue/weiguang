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
import com.ivyea.weiguang.core.Recap

// 首页一行：这周你是哪种听歌的人，点开看整张回顾卡
@Composable
fun RecapRow(r: Recap, accent: Color, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(20.dp, 3.dp).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("这周你是", fontSize = 12.5.sp, color = Wg.ink3)
                Text("「${r.persona}」", fontFamily = Wg.serif, fontSize = 17.sp, color = Wg.ink, modifier = Modifier.padding(start = 2.dp))
            }
            Text(r.line, fontSize = 12.5.sp, color = Wg.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Text("回顾 →", fontSize = 13.sp, color = accent, modifier = Modifier.padding(start = 10.dp))
    }
}

// 整张回顾卡：和分享出去的图一模一样
@Composable
fun RecapSheet(r: Recap, accent: Color, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val art = NowPlaying.now.value?.art
    val bmp = remember(r) { Card.week(r, art) }
    Sheet(onClose) {
        Image(
            bmp.asImageBitmap(), null,
            Modifier.fillMaxWidth().aspectRatio(1080f / 1440f).clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Fit,
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Text("所有音乐 App 合在一起算，只在手机里", fontSize = 11.5.sp, color = Wg.ink3, modifier = Modifier.weight(1f))
            Pill("分享", color = Wg.warm) { Card.share(ctx, bmp, "week") }
        }
    }
}
