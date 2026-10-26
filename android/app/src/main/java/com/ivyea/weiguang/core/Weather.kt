package com.ivyea.weiguang.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

// 天气：只在设置里打开「参考天气」后才查。用系统缓存的大致位置（不主动定位），
// 坐标保留一位小数（约 10 公里）发给 Open-Meteo（免费、不要账号），半小时查一次
object Weather {
    @Volatile var line: String? = null
        private set
    @Volatile var rainy = false
        private set
    private var at = 0L

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh(ctx: Context): String? {
        if (!Prefs.weather || !granted(ctx)) { line = null; return null }
        if (line != null && System.currentTimeMillis() - at < 30 * 60_000) return line
        val loc = try {
            val lm = ctx.getSystemService(LocationManager::class.java)
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) { null } ?: return line
        val lat = (loc.latitude * 10).roundToInt() / 10.0
        val lon = (loc.longitude * 10).roundToInt() / 10.0
        val raw = withContext(Dispatchers.IO) {
            try {
                val c = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
            } catch (_: Throwable) { null }
        } ?: return line
        try {
            val cur = JSONObject(raw).getJSONObject("current")
            val code = cur.optInt("weather_code")
            val t = cur.optDouble("temperature_2m").roundToInt()
            rainy = code in 51..67 || code in 80..82 || code >= 95
            line = "${describe(code)} ${t}°"
            at = System.currentTimeMillis()
        } catch (_: Throwable) {}
        return line
    }

    // WMO 天气代码
    private fun describe(c: Int) = when (c) {
        0 -> "晴"
        1, 2 -> "少云"
        3 -> "阴"
        45, 48 -> "雾"
        in 51..57 -> "毛毛雨"
        in 61..65, in 80..82 -> "下雨"
        66, 67 -> "冻雨"
        in 71..77, 85, 86 -> "下雪"
        in 95..99 -> "雷雨"
        else -> "多云"
    }
}
