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
    private var browser: MediaBrowser? = null
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

        section("4 · 点歌", "微光能不能让网易云放一首指定的歌。A~C 不用跳出去；D 会打开网易云，看完回来。")
        queryEdit = EditText(this).apply { setText("起风了"); inputType = InputType.TYPE_CLASS_TEXT; textSize = 15f }
        root.addView(queryEdit)
        row(btn("A 会话搜索") { testA() }, btn("B 系统搜索") { testB() }, btn("C 浏览服务") { testC() })
        root.addView(text("D · 链接打开（下面的链接可以改）", 13f, 0xFF75738F.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        uriEdit = EditText(this).apply { setText("orpheus://song/$SONG_ID"); inputType = InputType.TYPE_TEXT_VARIATION_URI; textSize = 13f }
        root.addView(uriEdit)
        row(btn("D1 搜索链接") { testD("D1", "orpheus://search?keyword=" + Uri.encode(query()), false) },
            btn("D2 歌曲链接") { testD("D2", "orpheus://song/$SONG_ID", true) })
        row(btn("D3 网页歌曲") { testD("D3", "https://music.163.com/song?id=$SONG_ID", true) },
            btn("D4 手机网页") { testD("D4", "https://y.music.163.com/m/song?id=$SONG_ID", true) })
        row(btn("D5 打开上面填的链接") { testD("D5", uriEdit.text.toString().trim(), false) })

        section("5 · 频谱", "网易云在放歌时点开，看下面的柱子会不会跟着动。方式 2 弹窗时选「整个屏幕」。")
        row(btn("方式1 系统可视化") { startViz() }, btn("停止") { stopViz() })
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

        ui.post(tick)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopViz()
        browser?.disconnect()
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
        val custom = st?.customActions?.joinToString("、") { it.name.toString() }
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

    private fun verifyLater(key: String, before: String, want: String) {
        ui.postDelayed({
            val now = title(ncm())
            val r = when {
                now.isEmpty() -> "❓ 读不到网易云当前歌名"
                now.contains(want, ignoreCase = true) -> "✅ 放上了《$now》"
                now != before -> "⚠️ 歌变了，但不是要的：《$before》→《$now》"
                else -> "❌ 歌没变，还是《$now》"
            }
            result(key, r)
        }, 6000)
    }

    private fun testA() {
        val c = ncm() ?: return result("A 会话搜索", "❌ 没找到网易云的播放会话")
        val q = query()
        val supported = (c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L
        log("A 网易云${if (supported) "声明了" else "没声明"}「按搜索播放」，照样试一下：$q")
        val before = title(c)
        try { c.transportControls.playFromSearch(q, Bundle()) } catch (e: Throwable) { return result("A 会话搜索", "❌ 调用出错 ${e.message}") }
        verifyLater("A 会话搜索", before, q)
    }

    private fun testB() {
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

    private fun testC() {
        val q = query()
        val svc = packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(NCM), 0)
        log("C 网易云的媒体浏览服务：${svc.joinToString { it.serviceInfo.name }.ifEmpty { "没有" }}")
        if (svc.isEmpty()) return result("C 浏览服务", "❌ 网易云没有对外的媒体浏览服务")
        val info = svc[0].serviceInfo
        browser?.disconnect()
        lateinit var b: MediaBrowser
        b = MediaBrowser(this, ComponentName(info.packageName, info.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                log("C 连上了，根目录：${b.root}")
                b.subscribe(b.root, object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                        log("C 根目录 ${children.size} 项：" + children.take(15).joinToString("；") {
                            "${it.description.title}${if (it.isPlayable) "(可播)" else ""}${if (it.isBrowsable) "(可展开)" else ""}"
                        })
                    }
                    override fun onError(parentId: String) { log("C 读根目录失败") }
                })
                b.search(q, null, object : MediaBrowser.SearchCallback() {
                    override fun onSearchResult(query: String, extras: Bundle?, items: MutableList<MediaBrowser.MediaItem>) {
                        log("C 搜索「$query」返回 ${items.size} 项：" + items.take(8).joinToString("；") { "${it.description.title}" })
                        val first = items.firstOrNull { it.isPlayable && it.mediaId != null }
                        if (first == null) { result("C 浏览服务", "⚠️ 连上了，但搜索没有可播放的结果"); return }
                        val before = title(ncm())
                        MediaController(this@MainActivity, b.sessionToken).transportControls.playFromMediaId(first.mediaId!!, null)
                        verifyLater("C 浏览服务", before, q)
                    }
                    override fun onError(query: String, extras: Bundle?) {
                        result("C 浏览服务", "⚠️ 连上了，但不支持搜索（根目录内容见上一条）")
                    }
                })
            }
            override fun onConnectionFailed() { result("C 浏览服务", "❌ 网易云拒绝连接（多半只放行车机 / Android Auto）") }
            override fun onConnectionSuspended() { log("C 连接被挂起") }
        }, null)
        browser = b
        try { b.connect() } catch (e: Throwable) { result("C 浏览服务", "❌ 连接出错 ${e.message}") }
    }

    private fun testD(key: String, uri: String, songLink: Boolean) {
        if (uri.isEmpty()) return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(NCM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handlers = packageManager.queryIntentActivities(i, 0)
        log("$key $uri → 网易云能接的界面 ${handlers.size} 个")
        if (handlers.isEmpty()) return result("$key 链接", "❌ 网易云不认这个链接：$uri")
        val before = title(ncm())
        try { startActivity(i) } catch (e: Throwable) { return result("$key 链接", "❌ 打开出错 ${e.message}") }
        // 打开歌曲页不一定自动播放；回来看结果时也请在报告里补一句"打开后看到了什么"
        verifyLater("$key 链接", before, if (songLink) "起风了" else query())
    }

    // ---------------- 5 · 频谱 ----------------

    private fun startViz() = askAudio {
        stopViz()
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
            v.setEnabled(true)
            viz = v
            vizPeak = 0f; vizFrames = 0
            log("方式1 已开启，等 8 秒自动判断")
            ui.postDelayed({
                if (viz == null) return@postDelayed
                result("频谱·方式1", when {
                    vizFrames == 0 -> "❌ 一帧数据都没收到"
                    vizPeak < 0.002f -> "❌ 收到 $vizFrames 帧但全是静音（系统不给，或网易云没在放）"
                    else -> "✅ 有声音数据，最高强度 %.3f".format(vizPeak)
                })
            }, 8000)
        } catch (e: Throwable) {
            result("频谱·方式1", "❌ 开不了：${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun stopViz() {
        try { viz?.setEnabled(false); viz?.release() } catch (_: Throwable) {}
        viz = null
    }

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
        if (resultCode != RESULT_OK || data == null) return result("频谱·方式2", "❌ 授权弹窗被取消")
        Probe.capturePeak = 0f
        Probe.captureStatus = "启动中"
        startForegroundService(Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data))
        log("方式2 已开启，等 8 秒自动判断")
        ui.postDelayed({
            val st = Probe.captureStatus
            result("频谱·方式2", when {
                st.startsWith("失败") -> "❌ $st"
                Probe.capturePeak < 0.0005f -> "❌ 录到的全是静音（网易云多半禁止了被录制）"
                else -> "✅ 截到了声音，最高强度 %.4f".format(Probe.capturePeak)
            })
        }, 8000)
    }

    private fun stopCapture() {
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
