package com.ivyea.weiguang.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// 兼容 OpenAI 格式的聊天接口（DeepSeek / Kimi / 硅基流动 / 本地 Ollama 都行），用户自带密钥
object Ai {
    data class Preset(val name: String, val base: String, val model: String)

    val presets = listOf(
        Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        Preset("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        Preset("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
        Preset("本地 Ollama", "http://192.168.1.2:11434/v1", "qwen2.5"),
    )

    val ready: Boolean
        get() = Prefs.aiBase.isNotBlank() && Prefs.aiModel.isNotBlank() &&
            (Prefs.aiKey.isNotBlank() || Prefs.aiBase.startsWith("http://"))

    class AiError(msg: String) : Exception(msg)

    suspend fun chat(
        system: String,
        user: String,
        history: List<Pair<String, String>> = emptyList(),
        maxTokens: Int = 600,
        temperature: Double = 0.8,
    ): String = withContext(Dispatchers.IO) {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for ((role, content) in history) msgs.put(JSONObject().put("role", role).put("content", content))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", Prefs.aiModel)
            .put("messages", msgs)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false)
        val c = try {
            URL(Prefs.aiBase.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        } catch (e: Throwable) {
            throw AiError("AI 地址写得不对")
        }
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (Prefs.aiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${Prefs.aiKey}")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw AiError(when (code) {
                    401, 403 -> "AI 密钥不对或没有权限"
                    402 -> "AI 账户余额不足"
                    404 -> "AI 地址或模型名不对"
                    429 -> "AI 请求太频繁，稍后再试"
                    else -> "AI 返回错误 $code"
                })
            }
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: AiError) {
            throw e
        } catch (e: UnknownHostException) {
            throw AiError("连不上 AI 服务，检查网络")
        } catch (e: SocketTimeoutException) {
            throw AiError("AI 响应超时")
        } catch (e: Throwable) {
            throw AiError("AI 出错：${e.message ?: e.javaClass.simpleName}")
        } finally {
            c.disconnect()
        }
    }

    // 模型偶尔在 JSON 外面包几句话或代码块，截出第一个 { 到最后一个 }
    fun json(s: String): JSONObject? = try {
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) null else JSONObject(s.substring(a, b + 1))
    } catch (_: Throwable) { null }
}
