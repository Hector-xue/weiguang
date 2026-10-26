package com.ivyea.weiguang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.Check
import kotlinx.coroutines.launch

// 一键自检：生成报告 → 复制发给开发者
@Composable
fun CheckSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun btn(label: String, on: Boolean, f: () -> Unit) = @Composable {
        Text(
            label, fontSize = 13.sp, color = if (on) Color.White else Wg.ink2,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) Wg.warm else Color.White.copy(alpha = 0.6f))
                .clickable(enabled = !busy) { f() }.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
    Column(Modifier.fillMaxWidth().glass(22.dp, 6.dp).padding(vertical = 14.dp)) {
        Text("自检", fontSize = 15.sp, color = Wg.ink, modifier = Modifier.padding(horizontal = 18.dp))
        Text(
            "点歌不灵时：先在那个 App 里放一首歌，回来点「试点歌」，再「复制」发给开发者",
            fontSize = 12.sp, color = Wg.ink2, lineHeight = 18.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 3.dp),
        )
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            btn("生成报告", true) { text = Check.report(ctx) }()
            btn(if (busy) "试点歌中…" else "试点歌", false) {
                busy = true
                scope.launch {
                    val r = try { Check.tryPlay(ctx) } catch (e: Throwable) { "试点歌出错: $e" }
                    text = Check.report(ctx) + "\n" + r
                    busy = false
                }
            }()
            if (text.isNotEmpty()) btn("复制", false) {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("微光自检", text))
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            }()
        }
        if (text.isNotEmpty()) SelectionContainer {
            Text(text, fontSize = 11.sp, color = Wg.ink2, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 18.dp))
        }
    }
}
