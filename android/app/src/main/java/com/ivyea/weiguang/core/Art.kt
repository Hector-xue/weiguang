package com.ivyea.weiguang.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// 封面小图：内存 + 磁盘两级缓存，网易云的图片服务按 ?param=宽y高 直接给缩略图
object Art {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var dir: File? = null
    private val gate = Semaphore(4)

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.cacheDir, "art").apply { mkdirs() }
    }

    suspend fun load(url: String, px: Int): Bitmap? {
        val key = "$px:$url"
        mem.get(key)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                mem.get(key) ?: fetch(url, px, key)?.also { mem.put(key, it) }
            }
        }
    }

    private fun fetch(url: String, px: Int, key: String): Bitmap? {
        val f = dir?.let { File(it, Integer.toHexString(key.hashCode()) + "_" + key.length) }
        if (f != null && f.exists()) BitmapFactory.decodeFile(f.path)?.let { return it }
        return try {
            val c = URL("$url?param=${px}y$px").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            try {
                if (c.responseCode != 200) return null
                val bytes = c.inputStream.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                f?.let { runCatching { it.writeBytes(bytes) } }
                bmp
            } finally { c.disconnect() }
        } catch (_: Throwable) { null }
    }
}

@Composable
fun rememberArt(url: String?, px: Int = 240): ImageBitmap? =
    produceState<ImageBitmap?>(null, url, px) {
        value = if (url.isNullOrEmpty()) null else Art.load(url, px)?.asImageBitmap()
    }.value
