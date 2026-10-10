package com.ddzhilian.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import java.io.File

object AttachmentFiles {
    suspend fun stage(context: Context, uri: Uri, destination: File, original: Boolean, progress: (Long, Long) -> Unit): Pair<String, String> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val info = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) to if (it.isNull(1)) -1L else it.getLong(1) else null
        }
        var name = info?.first ?: "附件"
        var mime = resolver.getType(uri) ?: "application/octet-stream"
        require(info?.second?.let { it <= MAX_ATTACHMENT_BYTES } != false) { "单个附件不能超过 20 MB" }
        resolver.openInputStream(uri)?.use { input -> destination.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024); var bytes = 0L
            while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; bytes += n
                require(bytes <= MAX_ATTACHMENT_BYTES) { "单个附件不能超过 20 MB" }; output.write(buffer, 0, n); progress(bytes, info?.second ?: -1)
            }
        } } ?: error("无法读取附件")
        if (!original && mime in setOf("image/jpeg", "image/png", "image/webp")) {
            val image = decode(destination, 2048) ?: error("无法读取图片")
            try { destination.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 88, it)) { "图片压缩失败" } } } finally { image.recycle() }
            name = name.substringBeforeLast('.', name) + ".jpg"; mime = "image/jpeg"
        }
        name to mime
    }
    @Suppress("DEPRECATION")
    fun decode(file: File, maximum: Int = 1600): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > maximum) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
        } }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
}
