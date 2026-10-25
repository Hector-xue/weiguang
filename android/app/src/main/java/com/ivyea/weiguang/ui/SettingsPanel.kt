package com.ivyea.weiguang.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.ivyea.weiguang.core.Ncm
import com.ivyea.weiguang.core.Prefs
import com.ivyea.weiguang.core.Spectrum
import com.ivyea.weiguang.service.NowListener

@Composable
fun SettingsPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var spectrum by remember { mutableStateOf(Prefs.spectrum) }
    var orb by remember { mutableStateOf(Prefs.orb) }
    val granted = remember(tick, spectrum) { Spectrum.granted(ctx) }
    val canOverlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    val listenerOn = remember(tick) { listenerEnabled(ctx) }
    val ncm = remember(tick) { Ncm.version(ctx) }
    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Throwable) { "?" } }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { Prefs.spectrum = true; spectrum = true; Spectrum.start(ctx) }
    }
    // 从「显示在其他应用上层」设置页回来时，权限到手就把光球挂上
    LaunchedEffect(canOverlay, orb) { NowListener.instance?.syncOrb() }

    Column(
        Modifier.fillMaxSize().background(Wg.bg.copy(alpha = 0.97f)).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设置", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Wg.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Wg.ink2)
            }
        }
        Spacer(Modifier.height(18.dp))

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
        Column(Modifier.fillMaxWidth().glass(22.dp, 6.dp)) {
            InfoRow("通知使用权", if (listenerOn) "已开启" else "去开启", listenerOn) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Line()
            InfoRow("网易云", ncm?.let { "v$it" } ?: "没装", ncm != null) { if (ncm == null) Ncm.openStore(ctx) else Ncm.openApp(ctx) }
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
            "微光只读取网易云的播放信息和你的歌单，不上传任何数据。",
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
