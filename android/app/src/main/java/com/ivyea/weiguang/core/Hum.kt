package com.ivyea.weiguang.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// 哼唱识别 / 听歌识曲：录 10 秒，交给 ACRCloud（用户自己的项目，哼唱和原曲都能认，看项目里开了哪个库）。
// 签名：base64(HMAC-SHA1(secret, "POST\n/v1/identify\n<key>\naudio\n1\n<时间戳>"))，见它的 Identification API 文档
object Hum {
    data class Guess(val title: String, val artist: String, val score: Int, val humming: Boolean)

    val ready: Boolean get() = Prefs.acrHost.isNotBlank() && Prefs.acrKey.isNotBlank() && Prefs.acrSecret.isNotBlank()

    class HumError(msg: String) : Exception(msg)

    private const val RATE = 16000

    // 录音：进度 0~1 回调给界面；返回 WAV
    @SuppressLint("MissingPermission")
    suspend fun record(seconds: Int = 10, onLevel: (Float, Float) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        } catch (e: Throwable) { throw HumError("打不开麦克风") }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw HumError("打不开麦克风，确认给了录音权限") }
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 10)
        val total = RATE * seconds
        var got = 0
        try {
            rec.startRecording()
            while (got < total && isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var peak = 0
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) { bb.putShort(buf[i]); peak = maxOf(peak, kotlin.math.abs(buf[i].toInt())) }
                pcm.write(bb.array())
                got += n
                onLevel(got.toFloat() / total, peak / 32768f)
            }
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
        }
        wav(pcm.toByteArray())
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    suspend fun identify(sample: ByteArray): List<Guess> = withContext(Dispatchers.IO) {
        if (!ready) throw HumError("先在设置里填 ACRCloud 的地址和密钥")
        val ts = (System.currentTimeMillis() / 1000).toString()
        val toSign = "POST\n/v1/identify\n${Prefs.acrKey}\naudio\n1\n$ts"
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(Prefs.acrSecret.toByteArray(), "HmacSHA1")) }
        val sig = Base64.encodeToString(mac.doFinal(toSign.toByteArray()), Base64.NO_WRAP)
        val host = Prefs.acrHost.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("sample", "hum.wav", sample.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("access_key", Prefs.acrKey)
            .addFormDataPart("sample_bytes", sample.size.toString())
            .addFormDataPart("timestamp", ts)
            .addFormDataPart("signature", sig)
            .addFormDataPart("data_type", "audio")
            .addFormDataPart("signature_version", "1")
            .build()
        val text = try {
            http.newCall(Request.Builder().url("https://$host/v1/identify").post(body).build()).execute().use { it.body?.string() ?: "" }
        } catch (e: Throwable) { throw HumError("连不上识曲服务：${e.message ?: ""}") }
        val o = try { JSONObject(text) } catch (_: Throwable) { throw HumError("识曲服务返回看不懂") }
        val st = o.optJSONObject("status")
        when (st?.optInt("code")) {
            0 -> {}
            1001 -> return@withContext emptyList()
            3001, 3014 -> throw HumError("ACRCloud 的密钥不对")
            3003, 3015 -> throw HumError("ACRCloud 次数用完了")
            else -> throw HumError("识曲服务：${st?.optString("msg") ?: "出错了"}")
        }
        val meta = o.optJSONObject("metadata") ?: return@withContext emptyList()
        fun list(key: String, humming: Boolean): List<Guess> {
            val a = meta.optJSONArray(key) ?: return emptyList()
            return List(a.length()) { i ->
                val m = a.getJSONObject(i)
                val ar = m.optJSONArray("artists")
                val artist = if (ar == null) "" else List(ar.length()) { ar.getJSONObject(it).optString("name") }.joinToString("/")
                // 原曲的分数是 0~100，哼唱的是 0~1（可能是字符串）
                val raw = m.optString("score").toDoubleOrNull() ?: 0.0
                Guess(m.optString("title"), artist, if (raw <= 1.0) (raw * 100).toInt() else raw.toInt(), humming)
            }
        }
        (list("music", false) + list("humming", true)).filter { it.title.isNotEmpty() }
            .distinctBy { Library.keyOf(it.title, it.artist) }.sortedByDescending { it.score }.take(5)
    }
}
