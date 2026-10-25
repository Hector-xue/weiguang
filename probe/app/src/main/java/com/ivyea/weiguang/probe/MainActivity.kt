package com.ivyea.weiguang.probe

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.browse.MediaBrowser
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.media.projection.MediaProjectionManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.service.media.MediaBrowserService
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : Activity() {
    companion object {
        const val NCM = "com.netease.cloudmusic"
        const val REQ_AUDIO = 7
        const val REQ_CAPTURE = 9
        // 《起风了》（买辣椒也用券）在网易云的歌曲 ID，用来测"按歌曲 ID 打开"
        const val SONG_ID = "1330348068"
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var permText: TextView
    private lateinit var nowText: TextView
    private lateinit var logText: TextView
    private lateinit var queryEdit: EditText
    private lateinit var uriEdit: EditText
    private lateinit var vizView: SpectrumView
    private lateinit var vizText: TextView
    private lateinit var capView: SpectrumView
    private lateinit var capText: TextView

    private val log = StringBuilder()
    private val results = LinkedHashMap<String, String>()
    private var viz: Visualizer? = null
    private var vizLevel = 0f
    private var vizPeak = 0f
    private var vizFrames = 0
    private var pendingAfterAudio: (() -> Unit)? = null

    // ---------------- 界面 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 14f, color: Int = 0xFF34324D.toInt(), bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.25f)
        }

    private fun section(title: String, hint: String) {
        root.addView(text(title, 18f, bold = true).apply { setPadding(0, dp(26), 0, dp(4)) })
        if (hint.isNotEmpty()) root.addView(text(hint, 13f, 0xFF75738F.toInt()).apply { setPadding(0, 0, 0, dp(8)) })
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(r)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFFF6F2F8.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(48))
            setBackgroundColor(0xFFF6F2F8.toInt())
        }
        val scroll = ScrollView(this).apply { addView(root); setBackgroundColor(0xFFF6F2F8.toInt()) }
        setContentView(scroll)

        root.addView(text("微光验证", 26f, bold = true))
        root.addView(text("版本 ${versionName()} · ${Build.MANUFACTURER} ${Build.MODEL} · 安卓 ${Build.VERSION.RELEASE}", 12f, 0xFF75738F.toInt()))
        root.addView(text("网易云：${appVersion(NCM) ?: "没装"}", 12f, 0xFF75738F.toInt()))
        root.addView(text("用法：先在网易云里放一首歌，再从上往下点。每一步的结果会自动判断，最后点「复制报告」发给我。", 13f, 0xFF75738F.toInt()).apply { setPadding(0, dp(10), 0, 0) })

        section("1 · 权限", "读别的 App 正在放什么，要「通知使用权」；截频谱要「录音权限」。")
        permText = text("")
        root.addView(permText)
        row(btn("开通知使用权") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            btn("给录音权限") { askAudio {} })

        section("2 · 正在播放", "自动刷新。能看到网易云的歌名、进度，就说明「知道你在听什么」可行。")
        nowText = text("", 13f)
        root.addView(nowText)

        section("3 · 控制", "每按一下会自动核对网易云有没有照做。")
        row(btn("上一首") { control("上一首") { it.transportControls.skipToPrevious() } },
            btn("暂停/播放") { control("暂停播放") { c ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
            } },
            btn("下一首") { control("下一首") { it.transportControls.skipToNext() } })
        row(btn("快进 15 秒") { control("拖进度") { c ->
            val pos = c.playbackState?.position ?: 0L
            c.transportControls.seekTo(pos + 15000)
        } })

        section("4 · 点歌", "从上往下一个个点，每项等它出结果（约 6 秒）再点下一个。D 会跳进网易云，看一眼再切回来。")
        queryEdit = EditText(this).apply { setText("起风了"); inputType = InputType.TYPE_CLASS_TEXT; textSize = 15f }
        root.addView(queryEdit)
        row(btn("A 会话搜索") { testA() }, btn("B 系统搜索") { testB() }, btn("C 浏览服务") { testC() })
        root.addView(text("D · 链接打开（下面的链接可以改）", 13f, 0xFF75738F.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        uriEdit = EditText(this).apply { setText("orpheus://song/$SONG_ID"); inputType = InputType.TYPE_TEXT_VARIATION_URI; textSize = 13f }
        root.addView(uriEdit)
        row(btn("D2 歌曲链接") { testD("D2", "orpheus://song/$SONG_ID", "起风了") },
            btn("D3 网页歌曲") { testD("D3", "https://music.163.com/song?id=$SONG_ID", "起风了") })
        row(btn("D6 歌曲链接+自动跳回") { testD6() })
        row(btn("D4 手机网页") { testD("D4", "https://y.music.163.com/m/song?id=$SONG_ID", "起风了") },
            btn("D5 打开上面填的链接") { testD("D5", uriEdit.text.toString().trim(), query()) })
        root.addView(text("E · 按 ID 播放，不跳进网易云", 13f, 0xFF75738F.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        row(btn("E1 车机会话") { testE("E1 车机会话按ID", true, "other_id__$SONG_ID") },
            btn("E2 通知会话") { testE("E2 通知会话按ID", false, "other_id__$SONG_ID") },
            btn("E3 纯数字ID") { testE("E3 纯数字ID", true, SONG_ID) })
        root.addView(text("F · 读你的歌单　G · 车机搜索　H · 点喜欢（会真的点，再按一次取消）", 13f, 0xFF75738F.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        row(btn("F 读我的音乐") { testF() }, btn("F2 放歌单第3首") { testF2() })
        row(btn("G 车机搜索") { testG() }, btn("H 喜欢") { testH() })

        section("5 · 频谱", "网易云在放歌时点开，看柱子动不动，等出结果再停。方式 2 弹窗里选「整个屏幕」→「开始」。")
        row(btn("方式1 系统可视化") { startViz() }, btn("停止") { stopViz(true) })
        vizView = SpectrumView(this)
        root.addView(vizView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90)))
        vizText = text("未开始", 13f)
        root.addView(vizText)
        row(btn("方式2 录制播放声音") { startCapture() }, btn("停止") { stopCapture() })
        capView = SpectrumView(this)
        root.addView(capView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90)))
        capText = text("未开始", 13f)
        root.addView(capText)

        section("6 · 报告", "")
        row(btn("复制报告") { copyReport() }, btn("清空记录") { log.setLength(0); results.clear(); logText.text = "" })
        logText = text("", 12f, 0xFF55536F.toInt()).apply { setTextIsSelectable(true) }
        root.addView(logText)

        // 上次崩溃的原因记下来，写进报告
        val crash = java.io.File(filesDir, "crash.txt")
        if (crash.exists()) {
            val t = crash.readText()
            results["上次崩溃"] = t.lineSequence().take(2).joinToString(" | ")
            log("上次崩溃：\n" + t.take(2500))
            crash.delete()
        }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try { crash.writeText(android.util.Log.getStackTraceString(e)) } catch (_: Throwable) {}
            prev?.uncaughtException(th, e)
        }

        ui.post(tick)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopViz(false)
        ucar?.disconnect()
        compat?.disconnect()
        super.onDestroy()
    }

    // ---------------- 工具 ----------------

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }

    private fun appVersion(pkg: String): String? = try {
        packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun query() = queryEdit.text.toString().trim().ifEmpty { "起风了" }

    private fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.append(t).append("  ").append(s).append('\n')
        logText.text = log
    }

    private fun result(key: String, s: String) {
        busy = false
        results[key] = s
        log("【$key】$s")
    }

    private fun listenerOn(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun audioOn() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askAudio(then: () -> Unit) {
        if (audioOn()) { then(); return }
        pendingAfterAudio = then
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (audioOn()) pendingAfterAudio?.invoke() else log("录音权限被拒绝")
            pendingAfterAudio = null
        }
    }

    private fun controllers(): List<MediaController> = try {
        getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(this, NowListener::class.java))
    } catch (_: SecurityException) { emptyList() }

    private fun ncm(): MediaController? = controllers().firstOrNull { it.packageName == NCM }

    private fun title(c: MediaController?): String =
        c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""

    private fun stateName(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING -> "播放中"
        PlaybackState.STATE_PAUSED -> "暂停"
        PlaybackState.STATE_STOPPED -> "停止"
        PlaybackState.STATE_BUFFERING -> "缓冲"
        PlaybackState.STATE_NONE -> "无"
        null -> "未知"
        else -> "状态$s"
    }

    private fun actionNames(a: Long): String {
        val names = listOf(
            PlaybackState.ACTION_PLAY_FROM_SEARCH to "按搜索播放",
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "按ID播放",
            PlaybackState.ACTION_PLAY_FROM_URI to "按链接播放",
            PlaybackState.ACTION_PREPARE_FROM_SEARCH to "按搜索准备",
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "跳到队列项",
            PlaybackState.ACTION_SEEK_TO to "拖进度",
            PlaybackState.ACTION_SKIP_TO_NEXT to "下一首",
            PlaybackState.ACTION_SKIP_TO_PREVIOUS to "上一首",
            PlaybackState.ACTION_PLAY_PAUSE to "播放暂停",
            PlaybackState.ACTION_SET_RATING to "评分",
        )
        return names.filter { a and it.first != 0L }.joinToString("、") { it.second }.ifEmpty { "无" }
    }

    private fun fmt(ms: Long): String = if (ms < 0) "?" else "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    private fun describe(c: MediaController, full: Boolean): String {
        val m = c.metadata
        val st = c.playbackState
        val sb = StringBuilder()
        sb.append("▸ ").append(c.packageName).append('\n')
        sb.append("  歌名：").append(m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "-").append('\n')
        sb.append("  歌手：").append(m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "-").append('\n')
        sb.append("  专辑：").append(m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "-").append('\n')
        val hasArt = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null || m?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null
        val dur = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        sb.append("  封面：").append(if (hasArt) "有" else "无")
            .append(" · 状态：").append(stateName(st?.state))
            .append(" · 进度：").append(fmt(st?.position ?: -1L)).append(" / ").append(fmt(dur)).append('\n')
        sb.append("  支持：").append(actionNames(st?.actions ?: 0L)).append('\n')
        val custom = st?.customActions?.joinToString("、") { "${it.name}=${it.action}" }
        if (!custom.isNullOrEmpty()) sb.append("  自定义按钮：").append(custom).append('\n')
        sb.append("  播放队列：").append(c.queue?.size?.let { "$it 首" } ?: "不公开").append('\n')
        if (full && m != null) {
            // 把所有字段原样列出来，看有没有藏歌词之类的东西
            sb.append("  全部字段：\n")
            for (k in m.keySet().sorted()) {
                val v: Any? = m.getString(k) ?: m.getText(k)
                    ?: m.getLong(k).takeIf { it != 0L }
                    ?: m.getBitmap(k)?.let { "[图片 ${it.width}×${it.height}]" }
                sb.append("    ").append(k).append(" = ").append(v.toString().take(80)).append('\n')
            }
        }
        return sb.toString()
    }

    // ---------------- 自动刷新 ----------------

    private val tick = object : Runnable {
        override fun run() {
            permText.text = "通知使用权：${if (listenerOn()) "✅ 已开" else "❌ 没开"}　录音权限：${if (audioOn()) "✅ 已给" else "❌ 没给"}"
            val cs = controllers()
            nowText.text = when {
                !listenerOn() -> "先开通知使用权"
                cs.isEmpty() -> "没有 App 在报告播放状态。去网易云放一首歌。"
                else -> cs.joinToString("\n") { describe(it, false) }
            }
            if (viz != null) vizText.text = "强度 %.3f · 最高 %.3f · 已收到 %d 帧".format(vizLevel, vizPeak, vizFrames)
            capView.set(Probe.captureBands)
            capText.text = "${Probe.captureStatus} · 强度 %.3f · 最高 %.3f".format(Probe.captureLevel, Probe.capturePeak)
            ui.postDelayed(this, 250)
        }
    }

    // ---------------- 3 · 控制 ----------------

    private fun control(name: String, act: (MediaController) -> Unit) {
        val c = ncm() ?: return result("控制·$name", "❌ 没找到网易云的播放会话（通知使用权没开，或网易云没在放）")
        val beforeTitle = title(c)
        val beforeState = c.playbackState?.state
        val beforePos = c.playbackState?.position ?: 0L
        try { act(c) } catch (e: Throwable) { return result("控制·$name", "❌ 调用出错 ${e.message}") }
        ui.postDelayed({
            val n = ncm()
            val t = title(n); val s = n?.playbackState?.state; val p = n?.playbackState?.position ?: 0L
            val ok = when (name) {
                "上一首", "下一首" -> t != beforeTitle
                "暂停播放" -> s != beforeState
                "拖进度" -> p - beforePos > 10000
                else -> false
            }
            result("控制·$name", if (ok) "✅ 照做了（$beforeTitle → $t，${stateName(s)}，${fmt(p)}）" else "❌ 没反应（$t，${stateName(s)}，${fmt(p)}）")
        }, 1800)
    }

    // ---------------- 4 · 点歌 ----------------

    // 一次只测一项：上一项没出结果就点下一项，会把别人的结果算到自己头上（第一版 D2/D3 就串了）
    private var busy = false
    private fun guard(): Boolean {
        if (busy) { Toast.makeText(this, "上一项还在判断，等几秒", Toast.LENGTH_SHORT).show(); return false }
        busy = true
        return true
    }

    // 如果网易云本来就在放目标歌，先切走，否则"放上了"无从判断
    private fun freshStart(want: String, go: () -> Unit) {
        val c = ncm()
        if (c != null && title(c).contains(want, ignoreCase = true)) {
            log("当前已经在放「$want」，先切到下一首，免得误判")
            c.transportControls.skipToNext()
            ui.postDelayed(go, 2500)
        } else go()
    }

    private fun verifyLater(key: String, before: String, want: String) {
        ui.postDelayed({
            val now = title(ncm())
            val r = when {
                now.isEmpty() -> "❓ 读不到网易云当前歌名"
                now.contains(want, ignoreCase = true) && now != before -> "✅ 放上了《$now》"
                now.contains(want, ignoreCase = true) -> "❓ 本来就在放《$now》，判断不了"
                now != before -> "⚠️ 歌变了，但不是要的：《$before》→《$now》"
                else -> "❌ 歌没变，还是《$now》"
            }
            result(key, r)
        }, 6000)
    }

    private fun testA() {
        if (!guard()) return
        val c = ncm() ?: return result("A 会话搜索", "❌ 没找到网易云的播放会话")
        val q = query()
        val supported = (c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L
        log("A 网易云${if (supported) "声明了" else "没声明"}「按搜索播放」，照样试一下：$q")
        val before = title(c)
        try { c.transportControls.playFromSearch(q, Bundle()) } catch (e: Throwable) { return result("A 会话搜索", "❌ 调用出错 ${e.message}") }
        verifyLater("A 会话搜索", before, q)
    }

    private fun testB() {
        if (!guard()) return
        val q = query()
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(NCM)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handlers = packageManager.queryIntentActivities(i, 0)
        log("B 网易云里能接「搜索并播放」的界面：${handlers.size} 个 ${handlers.joinToString { it.activityInfo.name }}")
        if (handlers.isEmpty()) return result("B 系统搜索", "❌ 网易云不接这个系统指令")
        val before = title(ncm())
        try { startActivity(i) } catch (e: Throwable) { return result("B 系统搜索", "❌ 打开出错 ${e.message}") }
        verifyLater("B 系统搜索", before, q)
    }

    // 网易云的车机浏览服务（UCarService），连一次复用
    private var ucar: MediaBrowser? = null

    private fun withUcar(key: String, then: (MediaBrowser) -> Unit) {
        ucar?.let { if (it.isConnected) { then(it); return } }
        val svc = packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(NCM), 0)
        if (svc.isEmpty()) return result(key, "❌ 网易云没有对外的媒体浏览服务")
        val info = svc[0].serviceInfo
        lateinit var b: MediaBrowser
        b = MediaBrowser(this, ComponentName(info.packageName, info.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() { log("已连上 ${info.name}，根目录 ${b.root}"); then(b) }
            override fun onConnectionFailed() { result(key, "❌ 浏览服务拒绝连接") }
            override fun onConnectionSuspended() { log("浏览服务连接被挂起") }
        }, null)
        ucar = b
        try { b.connect() } catch (e: Throwable) { result(key, "❌ 连接出错 ${e.message}") }
    }

    private fun children(b: MediaBrowser, id: String, cb: (List<MediaBrowser.MediaItem>?) -> Unit) {
        var done = false
        b.subscribe(id, object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                if (done) return
                done = true; b.unsubscribe(parentId); cb(children)
            }
            override fun onError(parentId: String) {
                if (done) return
                done = true; cb(null)
            }
        })
    }

    private fun item(it: MediaBrowser.MediaItem) =
        "${it.description.title}[${it.mediaId}]${if (it.isPlayable) "(可播)" else ""}${if (it.isBrowsable) "(可展开)" else ""}"

    private fun testC() {
        if (!guard()) return
        val q = query()
        withUcar("C 浏览服务") { b ->
            val before = title(ncm())
            try {
                MediaController(this, b.sessionToken).transportControls.playFromSearch(q, Bundle())
                log("C 已通过浏览服务的会话发出「按搜索播放」：$q")
                verifyLater("C 浏览服务", before, q)
            } catch (e: Throwable) {
                result("C 浏览服务", "⚠️ 连上了，但发不了播放指令：${e.message}")
            }
        }
    }

    private fun testD(key: String, uri: String, want: String) {
        if (uri.isEmpty() || !guard()) return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(NCM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handlers = packageManager.queryIntentActivities(i, 0)
        log("$key $uri → 网易云能接的界面 ${handlers.size} 个")
        if (handlers.isEmpty()) return result("$key 链接", "❌ 网易云不认这个链接：$uri")
        freshStart(want) {
            val before = title(ncm())
            try { startActivity(i) } catch (e: Throwable) { return@freshStart result("$key 链接", "❌ 打开出错 ${e.message}") }
            verifyLater("$key 链接", before, want)
        }
    }

    // D6：打开歌曲链接让网易云播放，2 秒后自己切回前台（安卓 10 起限制后台抢前台，要实测）
    private var resumedAt = 0L

    override fun onResume() {
        super.onResume()
        resumedAt = System.currentTimeMillis()
    }

    private fun testD6() {
        if (!guard()) return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("orpheus://song/$SONG_ID")).setPackage(NCM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        freshStart("起风了") {
            val before = title(ncm())
            val launched = System.currentTimeMillis()
            try { startActivity(i) } catch (e: Throwable) { return@freshStart result("D6 链接+跳回", "❌ 打开出错 ${e.message}") }
            log("D6 已打开歌曲链接，2 秒后尝试切回微光，别手动切回来")
            ui.postDelayed({
                try {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                } catch (e: Throwable) { log("D6 切回出错 ${e.message}") }
            }, 2000)
            ui.postDelayed({
                val now = title(ncm())
                val song = if (now.contains("起风了") && now != before) "歌 ✅ 放上了" else "歌 ❌ 没放上（$now）"
                val back = if (resumedAt > launched + 1500) "跳回 ✅ 自动回到了微光" else "跳回 ❌ 没能自己回来（被系统拦了）"
                result("D6 链接+跳回", "$song · $back")
            }, 6000)
        }
    }

    // E：按 ID 播放、不跳进网易云。ID 格式照抄它自己上报的 MEDIA_ID（other_id__歌曲ID）
    private fun testE(key: String, viaBrowser: Boolean, mediaId: String) {
        if (!guard()) return
        freshStart("起风了") {
            val before = title(ncm())
            val go: (MediaController) -> Unit = { c ->
                try {
                    c.transportControls.playFromMediaId(mediaId, Bundle())
                    log("$key 发出「按 ID 播放」$mediaId（${if (viaBrowser) "浏览服务会话" else "通知里的会话"}）")
                    verifyLater(key, before, "起风了")
                } catch (e: Throwable) { result(key, "❌ 调用出错 ${e.message}") }
            }
            if (viaBrowser) withUcar(key) { b -> go(MediaController(this, b.sessionToken)) }
            else ncm()?.let(go) ?: result(key, "❌ 没找到网易云的播放会话")
        }
    }

    // F：读「我的音乐」→ 第一个歌单 → 歌曲（带 ID）
    private var lastSongs: List<MediaBrowser.MediaItem> = emptyList()

    private fun testF() {
        if (!guard()) return
        withUcar("F 我的音乐") { b ->
            children(b, b.root) { top ->
                log("F 根目录：" + (top?.joinToString("；") { item(it) } ?: "读取失败"))
                val mine = top?.firstOrNull { it.description.title?.toString()?.contains("我的") == true }
                    ?: return@children result("F 我的音乐", "❌ 根目录没有「我的音乐」")
                children(b, mine.mediaId!!) { lists ->
                    log("F 我的音乐 ${lists?.size ?: 0} 项：" + (lists?.take(15)?.joinToString("；") { item(it) } ?: "读取失败"))
                    val pl = lists?.firstOrNull { it.isBrowsable }
                        ?: return@children result("F 我的音乐", if (lists.isNullOrEmpty()) "❌ 读不到内容（可能要车机授权或登录）" else "⚠️ 有内容但没有可展开的歌单")
                    children(b, pl.mediaId!!) { songs ->
                        val ss = songs?.filter { it.isPlayable && it.mediaId != null } ?: emptyList()
                        lastSongs = ss
                        log("F 歌单「${pl.description.title}」${songs?.size ?: 0} 项：" + ss.take(10).joinToString("；") { item(it) })
                        result("F 我的音乐", if (ss.isNotEmpty()) "✅ 读到歌单「${pl.description.title}」里 ${ss.size} 首歌（带 ID）" else "⚠️ 歌单打开了，但没有可播放的歌")
                    }
                }
            }
        }
    }

    private fun testF2() {
        val s = lastSongs.getOrNull(2) ?: lastSongs.firstOrNull() ?: return result("F2 放歌单里的歌", "❌ 先点 F")
        val want = s.description.title?.toString()?.take(6) ?: return
        if (!guard()) return
        freshStart(want) {
            withUcar("F2 放歌单里的歌") { b ->
                val before = title(ncm())
                MediaController(this, b.sessionToken).transportControls.playFromMediaId(s.mediaId!!, Bundle())
                log("F2 放《${s.description.title}》[${s.mediaId}]")
                verifyLater("F2 放歌单里的歌", before, want)
            }
        }
    }

    // G：用 androidx 的浏览器发搜索（车机语音搜歌走这条），看网易云的车机服务支不支持
    private var compat: MediaBrowserCompat? = null

    private fun testG() {
        if (!guard()) return
        val q = query()
        val svc = packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(NCM), 0)
        if (svc.isEmpty()) return result("G 车机搜索", "❌ 没有浏览服务")
        val info = svc[0].serviceInfo
        compat?.disconnect()
        lateinit var b: MediaBrowserCompat
        b = MediaBrowserCompat(this, ComponentName(info.packageName, info.name), object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                b.search(q, null, object : MediaBrowserCompat.SearchCallback() {
                    override fun onSearchResult(query: String, extras: Bundle?, items: MutableList<MediaBrowserCompat.MediaItem>) {
                        log("G 搜索「$query」返回 ${items.size} 项：" + items.take(8).joinToString("；") { "${it.description.title}[${it.mediaId}]" })
                        val first = items.firstOrNull { it.isPlayable && it.mediaId != null }
                            ?: return result("G 车机搜索", if (items.isEmpty()) "⚠️ 支持搜索，但没有结果" else "⚠️ 有结果，但不能直接播放")
                        val before = title(ncm())
                        MediaControllerCompat(this@MainActivity, b.sessionToken).transportControls.playFromMediaId(first.mediaId!!, null)
                        verifyLater("G 车机搜索", before, q)
                    }
                    override fun onError(query: String, extras: Bundle?) {
                        result("G 车机搜索", "❌ 网易云的车机服务不支持搜索")
                    }
                })
            }
            override fun onConnectionFailed() { result("G 车机搜索", "❌ 连接被拒") }
        }, null)
        compat = b
        try { b.connect() } catch (e: Throwable) { result("G 车机搜索", "❌ 连接出错 ${e.message}") }
    }

    // H：点「喜欢」。会真的改你账号里的红心，再按一次取消
    private fun testH() {
        val c = ncm() ?: return result("H 喜欢", "❌ 没找到网易云的播放会话")
        val act = c.playbackState?.customActions?.firstOrNull { it.name.toString() == "like" || it.action.contains("like", true) }
            ?: return result("H 喜欢", "❌ 没找到 like 按钮")
        val beforeIcon = act.icon
        val beforeCollect = c.metadata?.getLong("ucar.media.metadata.COLLECT_STATE")
        log("H 发送 like（action=${act.action}）")
        c.transportControls.sendCustomAction(act.action, Bundle())
        ui.postDelayed({
            val n = ncm()
            val a2 = n?.playbackState?.customActions?.firstOrNull { it.action == act.action }
            val col = n?.metadata?.getLong("ucar.media.metadata.COLLECT_STATE")
            val changed = a2?.icon != beforeIcon || col != beforeCollect
            result("H 喜欢", (if (changed) "✅ 状态变了" else "❓ 没看到状态变化") +
                "（图标 $beforeIcon→${a2?.icon}，收藏 $beforeCollect→$col）请看网易云里红心变没变")
        }, 1800)
    }

    // ---------------- 5 · 频谱 ----------------

    private fun judgeViz() {
        result("频谱·方式1", when {
            vizFrames == 0 -> "❌ 一帧数据都没收到"
            vizPeak < 0.002f -> "❌ 收到 $vizFrames 帧但全是静音（系统不给，或网易云没在放）"
            else -> "✅ 有声音数据，最高强度 %.3f（%d 帧）".format(vizPeak, vizFrames)
        })
    }

    private fun startViz() = askAudio {
        stopViz(false)
        try {
            val v = Visualizer(0)
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024))
            val mags = FloatArray(v.captureSize / 2)
            val bands = FloatArray(32)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null) return
                    val n = minOf(fft.size / 2, mags.size)
                    var sum = 0f
                    mags[0] = kotlin.math.abs(fft[0].toFloat()) / 128f
                    for (k in 1 until n) {
                        val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                        mags[k] = sqrt(re * re + im * im) / 128f
                        sum += mags[k]
                    }
                    vizLevel = sum / n
                    if (vizLevel > vizPeak) vizPeak = vizLevel
                    vizFrames++
                    Fft.toBands(mags, bands)
                    Fft.toUnit(bands, -48f)
                    val copy = bands.copyOf()
                    ui.post { vizView.set(copy) }
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            vizPeak = 0f; vizFrames = 0
            v.setEnabled(true)
            viz = v
            log("方式1 已开启，8 秒后自动判断（提前按停止也会判断）")
            ui.postDelayed({ if (viz != null) judgeViz() }, 8000)
        } catch (e: Throwable) {
            result("频谱·方式1", "❌ 开不了：${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun stopViz(judge: Boolean = true) {
        if (viz != null && judge) judgeViz()
        try { viz?.setEnabled(false); viz?.release() } catch (_: Throwable) {}
        viz = null
    }

    private fun judgeCapture() {
        val st = Probe.captureStatus
        result("频谱·方式2", when {
            st.startsWith("失败") -> "❌ $st"
            Probe.capturePeak < 0.0005f -> "❌ 录到的全是静音（网易云多半禁止了被录制）状态：$st"
            else -> "✅ 截到了声音，最高强度 %.4f".format(Probe.capturePeak)
        })
    }

    private var capturing = false

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < 29) return result("频谱·方式2", "❌ 系统低于安卓 10，不支持")
        askAudio {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }
    }

    @Deprecated("Activity 结果回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) return result("频谱·方式2", "❌ 授权弹窗被取消（要选「整个屏幕」再点开始）")
        Probe.capturePeak = 0f
        Probe.captureStatus = "启动中"
        startForegroundService(Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data))
        capturing = true
        log("方式2 已开启，8 秒后自动判断（提前按停止也会判断）")
        ui.postDelayed({ if (capturing) judgeCapture() }, 8000)
    }

    private fun stopCapture() {
        if (capturing) judgeCapture()
        capturing = false
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    // ---------------- 6 · 报告 ----------------

    private fun copyReport() {
        val sb = StringBuilder()
        sb.append("微光验证报告 ").append(versionName()).append('\n')
        sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · 安卓 ").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
        sb.append("网易云：").append(appVersion(NCM) ?: "没装").append('\n')
        sb.append("通知使用权：").append(if (listenerOn()) "开" else "关").append(" · 录音：").append(if (audioOn()) "给了" else "没给").append("\n\n")
        sb.append("== 结果 ==\n")
        if (results.isEmpty()) sb.append("（还没测）\n")
        for ((k, v) in results) sb.append(k).append("：").append(v).append('\n')
        sb.append("\n== 正在播放 ==\n")
        val cs = controllers()
        if (cs.isEmpty()) sb.append("（无）\n") else cs.forEach { sb.append(describe(it, true)) }
        sb.append("\n== 过程记录 ==\n").append(log)
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("微光验证报告", sb.toString()))
        Toast.makeText(this, "报告已复制，粘贴发给我", Toast.LENGTH_LONG).show()
    }
}
