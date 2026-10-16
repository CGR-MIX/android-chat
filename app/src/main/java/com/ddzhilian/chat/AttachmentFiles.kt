package com.ddzhilian.chat

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        require(info?.second?.let { it <= MAX_ATTACHMENT_BYTES } != false) { "单个附件不能超过 $MAX_ATTACHMENT_LABEL" }
        resolver.openInputStream(uri)?.use { input -> destination.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024); var bytes = 0L
            while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; bytes += n
                require(bytes <= MAX_ATTACHMENT_BYTES) { "单个附件不能超过 $MAX_ATTACHMENT_LABEL" }; output.write(buffer, 0, n); progress(bytes, info?.second ?: -1)
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
    /** Width / height of the decoded (EXIF-corrected) image, or null when it cannot be read. */
    fun aspectRatio(file: File): Float? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val ratio = options.outWidth.toFloat() / options.outHeight
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        // decode() rotates orientation 5..8, so the on-screen ratio is the reciprocal.
        return if (orientation in 5..8) 1f / ratio else ratio
    }
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

    /**
     * A writeable Uri in the public Downloads folder (sub-folder 点点直连) without asking the user
     * to pick a path. API 29+ uses MediaStore and needs no storage permission; API 26–28 returns a
     * file:// Uri that needs the legacy WRITE_EXTERNAL_STORAGE permission.
     *
     * Existing downloads are never overwritten: `a.pdf`, `a (1).pdf`, `a (2).pdf`, …
     */
    fun defaultDownloadDestination(context: Context, fileName: String): Uri {
        val safe = fileName.ifBlank { "附件" }
        return if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            // 只按不带尾斜杠的路径查询会漏掉 MediaStore 规范化后的记录，于是同名判断恒为 false，
            // 去重就退化成了依赖系统自动改名；这里两种写法都查。
            val selection =
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} IN (?, ?)"
            val name = deduplicatedName(safe) { candidate ->
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    selection,
                    arrayOf(candidate, RELATIVE_PATH, RELATIVE_PATH_TRAILING),
                    null,
                )?.use { it.moveToFirst() } == true
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .resolve("点点直连").apply { mkdirs() }
            val name = deduplicatedName(safe) { candidate -> File(dir, candidate).exists() }
            @Suppress("DEPRECATION")
            Uri.fromFile(File(dir, name))
        }
    }

    /** Marks a MediaStore insert as complete after the bytes have been written. */
    fun finishPending(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < 29) return
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    /** 同名时按 `a.pdf`、`a (1).pdf`、`a (2).pdf` … 取第一个未被占用的序号；不覆盖已有文件。 */
    internal fun deduplicatedName(original: String, exists: (String) -> Boolean): String {
        val base = original.substringBeforeLast('.', original)
        val ext = original.substringAfterLast('.', "")
        if (!exists(original)) return original
        var index = 1
        while (true) {
            val candidate = "$base ($index)" + if (ext.isBlank()) "" else ".$ext"
            if (!exists(candidate)) return candidate
            index++
        }
    }
}

internal const val RELATIVE_PATH = "Download/点点直连"
/** MediaStore 写入后会把 RELATIVE_PATH 规范化为带尾斜杠的形式，查询必须同时匹配两种写法。 */
internal const val RELATIVE_PATH_TRAILING = "$RELATIVE_PATH/"
