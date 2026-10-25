package com.ivyea.weiguang.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivyea.weiguang.core.Ai
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.Ncm
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Spectrum
import com.ivyea.weiguang.service.NowListener

@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var spectrum by remember { mutableStateOf(Prefs.spectrum) }
    var orb by remember { mutableStateOf(Prefs.orb) }
    val granted = remember(tick, spectrum) { Spectrum.granted(ctx) }
    val canOverlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    val listenerOn = remember(tick) { listenerEnabled(ctx) }
    val ncm = remember(tick) { Ncm.version(ctx) }
    val lib by Library.state.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Throwable) { "?" } }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { Prefs.spectrum = true; spectrum = true; Spectrum.start(ctx) }
    }
    // 从「显示在其他应用上层」设置页回来时，权限到手就把光球挂上
    LaunchedEffect(canOverlay, orb) { NowListener.instance?.syncOrb() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 120.dp),
    ) {
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink)
        Spacer(Modifier.height(20.dp))

        Column(Modifier.fillMaxWidth().glass(22.dp, 6.dp)) {
            SwitchRow(
                "粒子跟着音乐跳",
                if (granted) "读取播放声音的强弱，不录音、不上传" else "需要录音权限（只读声音强弱）",
                spectrum && granted,
            ) { on ->
                if (!on) { Prefs.spectrum = false; spectrum = false; Spectrum.stop() }
                else if (granted) { Prefs.spectrum = true; spectrum = true; Spectrum.start(ctx) }
                else ask.launch(Manifest.permission.RECORD_AUDIO)
            }
            Line()
            SwitchRow(
                "悬浮光球",
                if (canOverlay) "浮在其他 App 上，点一下回到微光；点歌后会自动跳回" else "需要「显示在其他应用上层」权限；开了以后点歌能自动跳回",
                orb && canOverlay,
            ) { on ->
                Prefs.orb = on; orb = on
                if (on && !canOverlay) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                NowListener.instance?.syncOrb()
            }
        }

        Spacer(Modifier.height(16.dp))
        AiSection()

        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().glass(22.dp, 6.dp)) {
            InfoRow("通知使用权", if (listenerOn) "已开启" else "去开启", listenerOn) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Line()
            InfoRow("网易云", ncm?.let { "v$it" } ?: "没装", ncm != null) { if (ncm == null) Ncm.openStore(ctx) else Ncm.openApp(ctx) }
            if (lib.syncLog.isNotEmpty()) {
                Line()
                InfoRow("上次同步明细", if (showLog) "收起" else "展开", true) { showLog = !showLog }
                if (showLog) Text(lib.syncLog.trim(), fontSize = 12.sp, color = Wg.ink2, lineHeight = 19.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().glass(22.dp, 6.dp)) {
            InfoRow("版本", version ?: "?", true) {}
            Line()
            InfoRow("开源仓库", "GitHub", true) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Hector-xue/weiguang")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Line()
            InfoRow("官网", "wg.ivyea.com", true) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wg.ivyea.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        Text(
            "微光只读取网易云的播放信息和你的歌单，存在这台手机里。歌词和封面按歌曲 ID 向网易云公开接口查询，不带账号。用 AI 时，只把歌名、歌手和你说的话发给你填的 AI 服务。",
            fontSize = 12.sp, color = Wg.ink3, modifier = Modifier.padding(top = 18.dp, start = 4.dp),
        )
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(Wg.line))
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp, color = Wg.ink)
            Text(sub, fontSize = 12.sp, color = Wg.ink2, modifier = Modifier.padding(top = 3.dp))
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Wg.warm, checkedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFE6E2EE), uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White),
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String, ok: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, color = Wg.ink, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = if (ok) Wg.ink2 else Wg.warm)
    }
}

// AI 伙伴：选个服务商，填密钥就行；地址和模型自动带出，想改再改
@Composable
private fun AiSection() {
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(Prefs.aiBase) }
    var key by remember { mutableStateOf(Prefs.aiKey) }
    var model by remember { mutableStateOf(Prefs.aiModel) }
    var result by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().glass(22.dp, 6.dp).padding(vertical = 14.dp)) {
        Text("AI 伙伴", fontSize = 15.sp, color = Wg.ink, modifier = Modifier.padding(horizontal = 18.dp))
        Text("对话点歌、此刻的心里话、解读歌词、星图都用它", fontSize = 12.sp, color = Wg.ink2, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 3.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Ai.presets.forEach { p ->
                val sel = base == p.base
                Text(
                    p.name, fontSize = 13.sp, color = if (sel) Color.White else Wg.ink2,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) Wg.warm else Color.White.copy(alpha = 0.6f))
                        .clickable { base = p.base; model = p.model; Prefs.aiBase = p.base; Prefs.aiModel = p.model; result = "" }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        Field("密钥", key, "sk-…", secret = true) { key = it; Prefs.aiKey = it; result = "" }
        Text(
            if (more) "收起" else "地址和模型", fontSize = 12.sp, color = Wg.lav,
            modifier = Modifier.padding(start = 18.dp, top = 8.dp).clickable { more = !more },
        )
        if (more) {
            Field("地址", base, "https://…/v1") { base = it; Prefs.aiBase = it; result = "" }
            Field("模型", model, "模型名") { model = it; Prefs.aiModel = it; result = "" }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (testing) "测试中…" else "测试连接", fontSize = 13.sp, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Wg.lav)
                    .clickable(enabled = !testing) {
                        testing = true
                        scope.launch {
                            result = try { Ai.chat("你是测试助手。", "只回复两个字：你好", maxTokens = 10); "✓ 连接成功" } catch (e: Ai.AiError) { e.message ?: "失败" }
                            testing = false
                        }
                    }.padding(horizontal = 14.dp, vertical = 7.dp),
            )
            Text(result, fontSize = 12.5.sp, color = if (result.startsWith("✓")) Wg.ok else Wg.warm, modifier = Modifier.padding(start = 12.dp))
        }
        Text("密钥只存在这台手机里。", fontSize = 11.5.sp, color = Wg.ink3, modifier = Modifier.padding(start = 18.dp, top = 10.dp))
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Wg.ink2, modifier = Modifier.padding(end = 12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = Wg.ink3)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = Wg.ink),
                cursorBrush = SolidColor(Wg.warm),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
