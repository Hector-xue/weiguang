package com.ivyea.weiguang.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// AI 作曲：用户自带作曲服务的密钥。
// ElevenLabs：POST /v1/music，头 xi-api-key，直接回 mp3。
// MiniMax：POST /v1/music_generation，Bearer；2026-08-20 起不再对新用户开放，老用户还能用。
// 先让对话 AI 把用户的话和此刻的情况写成作曲描述（没配就直接用原话），生成的歌存在本机、放进「微光作曲」
object Gen {
    data class Preset(val kind: String, val name: String, val base: String, val model: String)

    val presets = listOf(
        Preset("elevenlabs", "ElevenLabs", "https://api.elevenlabs.io", "music_v1"),
        Preset("minimax", "MiniMax", "https://api.minimax.cn", "music-2.6"),
    )

    val ready: Boolean get() = Prefs.genKey.isNotBlank() && Prefs.genBase.isNotBlank()

    private val _state = MutableStateFlow("")
    val state: StateFlow<String> = _state
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    class GenError(msg: String) : Exception(msg)

    // 返回放进曲库的那首歌
    suspend fun make(idea: String, instrumental: Boolean, seconds: Int = 90): Song {
        if (_busy.value) throw GenError("上一首还在写")
        if (!ready) throw GenError("先在设置里填作曲服务的密钥")
        _busy.value = true
        try {
            _state.value = "在想这首歌的样子…"
            val (title, prompt) = plan(idea, instrumental)
            _state.value = "在作曲，一般要一两分钟…"
            val bytes = if (Prefs.genKind == "minimax") minimax(prompt, instrumental) else eleven(prompt, instrumental, seconds)
            val name = "wg_" + System.currentTimeMillis() + ".mp3"
            val f = Sources.genFile(name) ?: throw GenError("存不下文件")
            withContext(Dispatchers.IO) { f.writeBytes(bytes) }
            _state.value = ""
            return Library.addGenerated(title, "微光作曲", name)
        } catch (e: GenError) {
            _state.value = e.message ?: "作曲失败"; throw e
        } catch (e: Throwable) {
            _state.value = "作曲失败：${e.message ?: e.javaClass.simpleName}"; throw GenError(_state.value)
        } finally {
            _busy.value = false
        }
    }

    // 标题 + 给作曲模型的描述
    private suspend fun plan(idea: String, instrumental: Boolean): Pair<String, String> {
        val stamp = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date())
        val fallback = (idea.ifBlank { "此刻" }.take(12)) to idea.ifBlank { "a gentle, warm song for this moment" }
        if (!Ai.ready) return "${fallback.first} · $stamp" to fallback.second
        return try {
            val raw = Ai.chat(
                """
                你帮用户把一句话变成一首歌的作曲描述。结合此刻的时间、天气和用户的口味。
                只回复 JSON：{"title":"中文歌名，不超过8个字","prompt":"给作曲模型的描述：风格、情绪、乐器、速度、${if (instrumental) "纯音乐无人声" else "人声用中文演唱"}，英文，不超过400字符"}
                """.trimIndent(),
                "现在是${Buddy.period()}。${Weather.line?.let { "外面$it。" } ?: ""}用户最常听：${Buddy.taste().joinToString("、")}。\n用户说：${idea.ifBlank { "给我写一首此刻的歌" }}",
                maxTokens = 300, temperature = 0.9,
            )
            val o = Ai.json(raw) ?: return "${fallback.first} · $stamp" to fallback.second
            o.optString("title").ifBlank { fallback.first } to o.optString("prompt").ifBlank { fallback.second }
        } catch (_: Ai.AiError) { "${fallback.first} · $stamp" to fallback.second }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 300_000
        return c
    }

    private suspend fun eleven(prompt: String, instrumental: Boolean, seconds: Int): ByteArray = withContext(Dispatchers.IO) {
        val c = open(Prefs.genBase.trimEnd('/') + "/v1/music?output_format=mp3_44100_128")
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("xi-api-key", Prefs.genKey)
            val body = JSONObject().put("prompt", prompt).put("music_length_ms", seconds * 1000)
                .put("model_id", Prefs.genModel.ifBlank { "music_v1" }).put("force_instrumental", instrumental)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw GenError(when (code) {
                401 -> "作曲服务的密钥不对"
                402 -> "作曲服务余额不足或套餐不含作曲"
                422 -> "作曲请求被拒：" + (c.errorStream?.bufferedReader()?.readText()?.take(80) ?: "")
                429 -> "作曲服务太忙，稍后再试"
                else -> "作曲服务返回 $code"
            })
            c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private suspend fun minimax(prompt: String, instrumental: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val c = open(Prefs.genBase.trimEnd('/') + "/v1/music_generation")
        val url = try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer ${Prefs.genKey}")
            val body = JSONObject().put("model", Prefs.genModel.ifBlank { "music-2.6" }).put("prompt", prompt.take(2000))
                .put("output_format", "url")
                .put("audio_setting", JSONObject().put("sample_rate", 44100).put("bitrate", 256000).put("format", "mp3"))
            if (instrumental) body.put("is_instrumental", true) else body.put("lyrics_optimizer", true)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            val o = JSONObject(text)
            val st = o.optJSONObject("base_resp")?.optInt("status_code") ?: -1
            if (st != 0) throw GenError(when (st) {
                1002 -> "作曲服务限流，稍后再试"
                1004, 2049 -> "作曲服务的密钥不对"
                1008 -> "作曲服务余额不足（MiniMax 作曲已不对新用户开放）"
                2013 -> "作曲参数不对，检查模型名"
                else -> o.optJSONObject("base_resp")?.optString("status_msg")?.ifBlank { null } ?: "作曲服务返回错误 $st"
            })
            o.optJSONObject("data")?.optString("audio")?.takeIf { it.startsWith("http") } ?: throw GenError("作曲服务没给出音频")
        } finally { c.disconnect() }
        val d = open(url)
        try { d.inputStream.use { it.readBytes() } } finally { d.disconnect() }
    }
}
