package com.ivyea.weiguang.probe

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

// 方式 2：用"录制播放声音"授权截取别的 App 正在放的声音（安卓 10+）。
// 安卓 14 起必须先拿到用户授权、再以 mediaProjection 类型的前台服务去用这个授权。
class CaptureService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll(); stopSelf(); return START_NOT_STICKY
        }
        startFg()
        if (Build.VERSION.SDK_INT < 29) {
            Probe.captureStatus = "失败：系统低于安卓 10，不支持"
            stopSelf(); return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            Probe.captureStatus = "失败：没拿到授权数据"
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    running = false
                    Probe.captureStatus = "系统停止了录制"
                }
            }, Handler(Looper.getMainLooper()))
            projection = p
            startRecord(p)
        } catch (e: Throwable) {
            Probe.captureStatus = "失败：${e.javaClass.simpleName}: ${e.message}"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "录制播放声音", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cap")
            .setContentTitle("微光验证")
            .setContentText("正在截取播放声音做频谱")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    @SuppressLint("MissingPermission")
    @TargetApi(29)
    private fun startRecord(p: MediaProjection) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val rate = 44100
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(max(minBuf, 8192))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        record = r
        r.startRecording()
        running = true
        Probe.capturePeak = 0f
        Probe.captureStatus = "录制中"

        Thread {
            val n = 1024
            val buf = ShortArray(n)
            val re = FloatArray(n); val im = FloatArray(n)
            val mags = FloatArray(n / 2)
            val bands = FloatArray(32)
            while (running) {
                var got = 0
                while (got < n && running) {
                    val k = r.read(buf, got, n - got)
                    if (k <= 0) break
                    got += k
                }
                if (got < n) { Thread.sleep(20); continue }
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768f
                    sum += v * v
                    val w = 0.5f - 0.5f * cos(2 * PI * i / (n - 1)).toFloat()
                    re[i] = v * w; im[i] = 0f
                }
                val rms = sqrt(sum / n).toFloat()
                Fft.fft(re, im)
                for (k in 0 until n / 2) mags[k] = sqrt(re[k] * re[k] + im[k] * im[k]) / (n / 4f)
                Fft.toBands(mags, bands)
                Fft.toUnit(bands, -72f)
                Probe.captureBands = bands.copyOf()
                Probe.captureLevel = rms
                if (rms > Probe.capturePeak) Probe.capturePeak = rms
            }
        }.start()
    }

    private fun stopAll() {
        running = false
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        if (Probe.captureStatus == "录制中") Probe.captureStatus = "已停止"
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}
