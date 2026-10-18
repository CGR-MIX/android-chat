package com.ddzhilian.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 把 `/api/ai/avatar` 拉成聊天里用的圆头像。图片按 URL（含内容哈希）缓存到 cacheDir，
 * 所以后台换头像后新 URL 会自然重新下载；解码按需降采样，避免占内存。
 */
class AiAvatarStore(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val lock = Any()
    private val memory = mutableMapOf<String, Bitmap?>()

    /** 只在内存/磁盘已有缓存时返回，用于首帧绘制，不阻塞界面。 */
    fun cached(avatarUrl: String?): Bitmap? {
        if (avatarUrl.isNullOrBlank()) return null
        synchronized(lock) {
            if (memory.containsKey(avatarUrl)) return memory[avatarUrl]
        }

        val file = fileFor(avatarUrl)
        if (file == null || !file.isFile) {
            synchronized(lock) { memory[avatarUrl] = null }
            return null
        }

        val bitmap = decode(file)
        synchronized(lock) { memory[avatarUrl] = bitmap }
        return bitmap
    }

    /** 下载并缓存；失败返回 null，界面回退到文字头像。 */
    suspend fun load(avatarUrl: String?): Bitmap? {
        if (avatarUrl.isNullOrBlank()) return null
        cached(avatarUrl)?.let { return it }

        val bitmap = withContext(Dispatchers.IO) {
            runCatching { download(avatarUrl) }.getOrNull()
        }
        synchronized(lock) { memory[avatarUrl] = bitmap }
        return bitmap
    }

    private fun fileFor(avatarUrl: String): File? = runCatching {
        val directory = File(context.cacheDir, "ai-avatar")
        if (!directory.isDirectory && !directory.mkdirs()) {
            return null
        }
        File(directory, "${avatarUrl.hashCode().toUInt().toString(16)}.img")
    }.getOrNull()

    private fun download(avatarUrl: String): Bitmap? {
        val request = Request.Builder().url(avatarUrl).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            if (detectImageType(bytes) == null) return@use null
            val file = fileFor(avatarUrl) ?: return@use null
            file.writeBytes(bytes)
            decode(file)
        }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) {
            sample *= 2
        }

        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun detectImageType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) {
            return "image/jpeg"
        }
        val riff = String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF"
        val webp = String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        return if (riff && webp) "image/webp" else null
    }
}
