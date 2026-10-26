package com.ivyea.weiguang.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// 系统整体输出的频谱（Visualizer 会话 0）。真机验证过能拿到网易云的声音；只要录音权限，不弹录屏授权
object Spectrum {
    const val N = 48

    private val _bands = MutableStateFlow(FloatArray(N))
    val bands: StateFlow<FloatArray> = _bands

    // 最近有没有收到真实声音；没有时界面按节奏模拟
    @Volatile var live = false
        private set

    private var viz: Visualizer? = null
    private val smooth = FloatArray(N)
    private val prev = FloatArray(N)
    private var ref = 0.02f
    private var silentFrames = 0

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context) {
        if (viz != null || !granted(ctx)) return
        try {
            val v = Visualizer(0)
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024))
            val mags = FloatArray(v.captureSize / 2)
            val raw = FloatArray(N)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) process(fft, mags, raw)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.setEnabled(true)
            viz = v
        } catch (_: Throwable) {
            viz = null
        }
    }

    fun stop() {
        Listen.pause()
        try { viz?.setEnabled(false); viz?.release() } catch (_: Throwable) {}
        viz = null
        live = false
        smooth.fill(0f)
        _bands.value = FloatArray(N)
    }

    private fun process(fft: ByteArray, mags: FloatArray, raw: FloatArray) {
        val n = minOf(fft.size / 2, mags.size)
        if (n < 8) return
        for (k in 1 until n) {
            val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
            mags[k] = sqrt(re * re + im * im) / 128f
        }
        // 线性频点 → 对数频段（低频细、高频粗），跳过最低几个直流附近的点
        var peak = 0f
        val lo0 = 2.0; val hi0 = n.toDouble()
        for (b in 0 until N) {
            val lo = (lo0 * (hi0 / lo0).pow(b.toDouble() / N)).toInt()
            val hi = max(lo + 1, (lo0 * (hi0 / lo0).pow((b + 1).toDouble() / N)).toInt()).coerceAtMost(n)
            var s = 0f
            for (k in lo until hi) s = max(s, mags[k])
            raw[b] = s
            peak = max(peak, s)
        }
        // 自动增益：系统给的数值跟音量走，按最近的峰值归一
        ref = max(ref * 0.996f, peak).coerceAtLeast(0.004f)
        if (peak < 0.0008f) silentFrames++ else silentFrames = 0
        live = silentFrames < 40
        val out = FloatArray(N)
        var flux = 0f; var hi = 0f; var sum = 0f
        for (b in 0 until N) {
            val v = sqrt((raw[b] / ref).coerceIn(0f, 1f))
            flux += max(0f, v - prev[b]); prev[b] = v
            sum += v; if (b >= N * 2 / 3) hi += v
            smooth[b] = if (v > smooth[b]) smooth[b] + (v - smooth[b]) * 0.65f else smooth[b] + (v - smooth[b]) * 0.18f
            out[b] = smooth[b]
        }
        _bands.value = out
        main.post { if (live) Listen.frame(flux / N, if (sum > 0f) hi / sum else 0f) else Listen.pause() }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
}
