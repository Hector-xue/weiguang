package com.ivyea.weiguang.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.Gen
import com.ivyea.weiguang.core.Hum
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.Local
import com.ivyea.weiguang.core.Players
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Song
import com.ivyea.weiguang.core.Sources
import kotlinx.coroutines.launch

// 浮在页面上的底部卡片：遮罩淡入、卡片带一点弹性从下面升上来；点遮罩或返回键先退场再关
@Composable
internal fun Sheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val k = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { k.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 380f)) }
    var closing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch { k.animateTo(0f, androidx.compose.animation.core.tween(170)); onClose() }
        }
    }
    // 返回键先关浮层，别直接退出微光
    androidx.activity.compose.BackHandler(onBack = close)
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = k.value.coerceIn(0f, 1f) }.background(Color(0x33342F55)).clickable(onClick = close),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = DockH + 8.dp)
                .graphicsLayer { translationY = (1f - k.value) * 72.dp.toPx(); val sc = 0.96f + 0.04f * k.value; scaleX = sc; scaleY = sc }
                .glass(24.dp, 16.dp)
                .background(Color.White.copy(alpha = 0.6f)).clickable(enabled = false) {}.padding(20.dp),
        ) { content() }
    }
}

@Composable
internal fun Pill(text: String, on: Boolean = true, color: Color = Wg.warm, onClick: () -> Unit) {
    Text(
        text, fontSize = 13.sp, color = if (on) Color.White else Wg.ink2,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (on) Brush.linearGradient(listOf(color.copy(alpha = 0.85f), color)) else SolidColor(Color.White.copy(alpha = 0.7f)))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ---------- 哼一段 ----------

@Composable
fun HumSheet(time: State<Float>, accent: Color, onClose: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(if (Hum.ready) "idle" else "setup") }
    var progress by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var guesses by remember { mutableStateOf<List<Hum.Guess>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    val start: () -> Unit = {
        phase = "rec"; err = ""; guesses = emptyList()
        scope.launch {
            try {
                val wav = Hum.record { p, l -> progress = p; level = l }
                phase = "think"
                guesses = Hum.identify(wav)
                phase = "done"
            } catch (e: Hum.HumError) { err = e.message ?: "没认出来"; phase = "done" }
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() else err = "要录音权限才能听你哼" }
    Sheet(onClose) {
        Text("哼一段", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
        Text("哼或唱 10 秒，也可以把手机对着正在放的歌", fontSize = 12.5.sp, color = Wg.ink3, modifier = Modifier.padding(top = 3.dp))
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            ParticleOrb(Modifier.fillMaxSize(), time, { if (phase == "rec") 0.3f + level * 2.2f else if (phase == "think") 0.9f else 0.2f }, accent)
            if (phase == "rec") Text("${(10 - progress * 10).toInt().coerceAtLeast(0)}", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
        when (phase) {
            "setup" -> {
                Text(
                    "哼唱识别要用 ACRCloud（有免费试用）。在它的控制台建一个识别项目，把地址、Access Key、Secret 填到设置里。",
                    fontSize = 13.sp, color = Wg.ink2, lineHeight = 20.sp,
                )
                Row(Modifier.padding(top = 14.dp)) { Pill("去设置") { onClose(); onSettings() } }
            }
            "idle" -> Row { Pill("开始") {
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else ask.launch(Manifest.permission.RECORD_AUDIO)
            } }
            "rec" -> Text("在听…", fontSize = 13.sp, color = Wg.ink2)
            "think" -> Text("在认…", fontSize = 13.sp, color = Wg.ink2)
            else -> {
                if (err.isNotEmpty()) Text(err, fontSize = 13.sp, color = Wg.warm)
                else if (guesses.isEmpty()) Text("没认出来。哼得再久一点、旋律清楚一点试试", fontSize = 13.sp, color = Wg.ink2)
                guesses.forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            val k = Library.keyOf(g.title, g.artist)
                            val known = Library.state.value.songs.values.firstOrNull { Library.keyOf(it.title, it.artist) == k }
                            Players.play(ctx, known ?: Song(Library.idOf("", null, g.title, g.artist) ?: 0L, g.title, g.artist))
                            onClose()
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.title, fontSize = 15.sp, color = Wg.ink)
                            Text(g.artist, fontSize = 12.sp, color = Wg.ink3)
                        }
                        Text("${if (g.humming) "像" else "是"} ${g.score}%", fontSize = 12.sp, color = accent, modifier = Modifier.padding(end = 10.dp))
                        Glyph(G.Play, accent, Modifier.size(15.dp))
                    }
                }
                Row(Modifier.padding(top = 12.dp)) { Pill("再来一次", on = false, onClick = start) }
            }
        }
    }
}

// ---------- 作一首 ----------

@Composable
fun GenSheet(time: State<Float>, accent: Color, seed: String, onClose: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by Gen.busy.collectAsState()
    val state by Gen.state.collectAsState()
    var idea by remember { mutableStateOf(seed) }
    var instrumental by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<Song?>(null) }
    Sheet(onClose) {
        Text("让微光作一首", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
        Text("说说此刻，或者想要什么样的歌", fontSize = 12.5.sp, color = Wg.ink3, modifier = Modifier.padding(top = 3.dp))
        if (!Gen.ready) {
            Text(
                "作曲要用你自己的作曲服务密钥：ElevenLabs，或者 MiniMax（MiniMax 已不对新用户开放）。",
                fontSize = 13.sp, color = Wg.ink2, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp),
            )
            Row(Modifier.padding(top = 14.dp)) { Pill("去设置") { onClose(); onSettings() } }
            return@Sheet
        }
        Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
            ParticleOrb(Modifier.fillMaxSize(), time, { if (busy) 0.9f else 0.25f }, accent)
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (idea.isEmpty()) Text("比如：下班路上，有点累但还挺开心", fontSize = 14.sp, color = Wg.ink3)
            BasicTextField(idea, { idea = it }, textStyle = TextStyle(fontSize = 14.sp, color = Wg.ink), cursorBrush = SolidColor(Wg.warm), modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("有人声", on = !instrumental, color = Wg.lav) { instrumental = false }
            Pill("纯音乐", on = instrumental, color = Wg.lav) { instrumental = true }
        }
        if (state.isNotEmpty()) Text(state, fontSize = 13.sp, color = if (busy) Wg.ink2 else Wg.warm, modifier = Modifier.padding(top = 12.dp))
        made?.let { s ->
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).clickable { Local.play(ctx, listOf(s)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 15.sp, color = Wg.ink)
                    Text("放进了曲库的「微光作曲」", fontSize = 12.sp, color = Wg.ink3)
                }
                Glyph(G.Play, accent, Modifier.size(15.dp))
            }
        }
        Row(Modifier.padding(top = 14.dp)) {
            Pill(if (busy) "在写…" else if (made != null) "再写一首" else "开始") {
                if (busy) return@Pill
                scope.launch {
                    try {
                        val s = Gen.make(idea, instrumental)
                        made = s
                        Local.play(ctx, listOf(s))
                    } catch (_: Gen.GenError) {}
                }
            }
        }
    }
}

// ---------- 设置：自建曲库 / 识曲 / 作曲 ----------

@Composable
fun ServersSection() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Sources.servers) }
    var adding by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("subsonic") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().glass(22.dp, 6.dp).padding(vertical = 14.dp)) {
        Text("自己的曲库", fontSize = 15.sp, color = Wg.ink, modifier = Modifier.padding(horizontal = 18.dp))
        Text(
            "Navidrome、Jellyfin、WebDAV 网盘里的歌由微光自己放，不切 App。账号只存在这台手机里",
            fontSize = 12.sp, color = Wg.ink2, lineHeight = 18.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 3.dp),
        )
        list.forEach { s ->
            Line()
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontSize = 14.5.sp, color = Wg.ink)
                    Text("${Sources.kinds[s.kind]} · ${s.url}", fontSize = 12.sp, color = Wg.ink3, maxLines = 1)
                }
                Text("移除", fontSize = 13.sp, color = Wg.warm, modifier = Modifier.clickable { Sources.remove(s.id); list = Sources.servers }.padding(6.dp))
            }
        }
        if (!adding) {
            Text(
                "＋ 添加", fontSize = 13.sp, color = Wg.lav,
                modifier = Modifier.padding(start = 18.dp, top = 12.dp).clickable { adding = true; msg = "" },
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sources.kinds.forEach { (k, name) -> Pill(name, on = kind == k, color = Wg.lav) { kind = k } }
        }
        Field("地址", url, if (kind == "webdav") "https://…/dav/音乐" else "http://192.168.1.2:4533") { url = it }
        Field("用户名", user, "") { user = it }
        Field("密码", pass, "", secret = true) { pass = it }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (testing) "连接中…" else "连接并保存", color = Wg.lav) {
                if (testing || url.isBlank()) return@Pill
                testing = true
                scope.launch {
                    val e = Sources.add(kind, url, user, pass)
                    testing = false
                    if (e == null) {
                        list = Sources.servers; adding = false; url = ""; user = ""; pass = ""
                        msg = "已连上，歌单在后台同步，去曲库看"
                        Library.syncAsync(ctx)
                    } else msg = e
                }
            }
            Text("取消", fontSize = 13.sp, color = Wg.ink3, modifier = Modifier.padding(start = 14.dp).clickable { adding = false })
        }
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.5.sp, color = Wg.warm, modifier = Modifier.padding(start = 18.dp, top = 8.dp))
    }
}

@Composable
fun HumSection() {
    var host by remember { mutableStateOf(Prefs.acrHost) }
    var key by remember { mutableStateOf(Prefs.acrKey) }
    var secret by remember { mutableStateOf(Prefs.acrSecret) }
    Column(Modifier.fillMaxWidth().glass(22.dp, 6.dp).padding(vertical = 14.dp)) {
        Text("哼唱识别", fontSize = 15.sp, color = Wg.ink, modifier = Modifier.padding(horizontal = 18.dp))
        Text(
            "用 ACRCloud（有免费试用）。在控制台建识别项目，开上哼唱库，把下面三项抄过来",
            fontSize = 12.sp, color = Wg.ink2, lineHeight = 18.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 3.dp, bottom = 8.dp),
        )
        Field("地址", host, "identify-cn-north-1.acrcloud.cn") { host = it; Prefs.acrHost = it }
        Field("Key", key, "Access Key") { key = it; Prefs.acrKey = it }
        Field("Secret", secret, "Access Secret", secret = true) { secret = it; Prefs.acrSecret = it }
    }
}

@Composable
fun GenSection() {
    var kind by remember { mutableStateOf(Prefs.genKind) }
    var base by remember { mutableStateOf(Prefs.genBase) }
    var key by remember { mutableStateOf(Prefs.genKey) }
    var model by remember { mutableStateOf(Prefs.genModel) }
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().glass(22.dp, 6.dp).padding(vertical = 14.dp)) {
        Text("AI 作曲", fontSize = 15.sp, color = Wg.ink, modifier = Modifier.padding(horizontal = 18.dp))
        Text(
            "按你此刻的心情写一首歌，存在手机里。要你自己的作曲服务密钥，按它的价格计费",
            fontSize = 12.sp, color = Wg.ink2, lineHeight = 18.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 3.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Gen.presets.forEach { p ->
                Pill(p.name, on = kind == p.kind, color = Wg.lav) {
                    kind = p.kind; base = p.base; model = p.model
                    Prefs.genKind = p.kind; Prefs.genBase = p.base; Prefs.genModel = p.model
                }
            }
        }
        if (kind == "minimax") Text("MiniMax 作曲 2026-08-20 起不对新用户开放，老用户还能用", fontSize = 11.5.sp, color = Wg.ink3, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
        Field("密钥", key, "", secret = true) { key = it; Prefs.genKey = it }
        Text(if (more) "收起" else "地址和模型", fontSize = 12.sp, color = Wg.lav, modifier = Modifier.padding(start = 18.dp, top = 8.dp).clickable { more = !more })
        if (more) {
            Field("地址", base, "https://…") { base = it; Prefs.genBase = it }
            Field("模型", model, "") { model = it; Prefs.genModel = it }
        }
    }
}
