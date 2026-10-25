package com.ivyea.weiguang.probe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

object Fft {
    // 原地基 2 FFT，长度必须是 2 的幂
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    // 线性频点 → 对数分布的若干频段（取段内最大值）
    fun toBands(mags: FloatArray, out: FloatArray) {
        val m = mags.size.toDouble()
        val b = out.size
        for (i in 0 until b) {
            val lo = max(1, m.pow(i.toDouble() / b).toInt())
            val hi = max(lo + 1, m.pow((i + 1).toDouble() / b).toInt()).coerceAtMost(mags.size)
            var s = 0f
            for (k in lo until hi) s = max(s, mags[k])
            out[i] = s
        }
    }

    // 振幅(0~1) → 分贝 → 0~1，floorDb 以下算 0
    fun toUnit(bands: FloatArray, floorDb: Float) {
        for (i in bands.indices) {
            val db = 20f * log10(bands[i] + 1e-7f)
            bands[i] = ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
        }
    }
}
