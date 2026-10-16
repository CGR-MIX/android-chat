package com.ddzhilian.chat

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * 版本更新通道（网络与安装部分）。refs 走 git smart HTTP，安装包走同一个 tag 的 Release 附件，
 * 附件缺失时回退到 tag 里直接提交的构建产物，因此不需要自建更新服务，也不需要 GitHub API 配额。
 */

private const val UPDATE_USER_AGENT = "DdzhilianChat-Updater"
private const val UPDATE_READ_CHUNK = 64 * 1024
private const val UPDATE_PROGRESS_INTERVAL_MS = 200L

/** 更新通道在界面上的状态，检查、下载、安装都由它驱动。 */
sealed interface UpdateState {
    /** 本次打开还没点过检查。 */
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** 已经是最新：[tag] 是通道里最新的版本 tag。 */
    data class Current(val tag: String) : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val received: Long, val total: Long) : UpdateState
    data class Ready(val release: UpdateRelease, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** 交给系统安装器的结果；[NeedsPermission] 需要用户先允许本应用安装应用。 */
sealed interface InstallRequest {
    data object Started : InstallRequest
    data object NeedsPermission : InstallRequest
    data class Failed(val message: String) : InstallRequest
}

class UpdateChannel(private val client: OkHttpClient, private val directory: File) {
    /** 读 git 仓库的 refs 广告，返回版本号最高的 tag；仓库里没有版本 tag 时返回 null。 */
    suspend fun check(): UpdateRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(UPDATE_REFS_URL)
            .header("Accept", "application/x-git-upload-pack-advertisement")
            .header("User-Agent", UPDATE_USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(channelError(response.code))
            val body = response.body?.string().orEmpty()
            if (body.isEmpty()) throw IOException("更新通道返回空响应")
            latestRelease(parseRefAdvertisement(body))
        }
    }

    /** 依次尝试候选地址，成功时返回已落盘的安装包。 */
    suspend fun download(release: UpdateRelease, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = File(directory, UPDATE_ASSET_NAME)
        var failure: IOException? = null
        for (url in release.apkUrls) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", UPDATE_USER_AGENT).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("更新包响应为空")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(UPDATE_READ_CHUNK)
                            var received = 0L
                            var reported = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                received += count
                                val now = System.currentTimeMillis()
                                if (now - reported >= UPDATE_PROGRESS_INTERVAL_MS) {
                                    reported = now
                                    onProgress(received, total)
                                }
                            }
                            onProgress(received, total)
                        }
                    }
                }
                if (!target.looksLikeApk()) throw IOException("下载到的内容不是 APK")
                return@withContext target
            } catch (error: IOException) {
                failure = error
                target.delete()
            }
        }
        throw IOException(failure?.message ?: "更新包下载失败")
    }
}

/** APK 就是 zip：没有 PK 头说明拿到的是错误页而不是安装包。 */
private fun File.looksLikeApk(): Boolean = runCatching {
    inputStream().use { stream ->
        val head = ByteArray(2)
        var read = 0
        while (read < head.size) {
            val count = stream.read(head, read, head.size - read)
            if (count < 0) break
            read += count
        }
        read == head.size && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }
}.getOrDefault(false)

/**
 * 把已下载的安装包交给系统安装器。Android 8 起应用必须先被允许「安装未知应用」，
 * 此时返回 [InstallRequest.NeedsPermission]，由界面跳到对应的系统设置页。
 */
fun requestInstall(context: Context, file: File): InstallRequest {
    if (!file.exists()) return InstallRequest.Failed("更新包已不在本机，请重新下载")
    if (!context.packageManager.canRequestPackageInstalls()) return InstallRequest.NeedsPermission
    return runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        InstallRequest.Started
    }.getOrElse { InstallRequest.Failed(it.message ?: "无法启动系统安装器") }
}

/** 「安装未知应用」授权页；授权后回到设置页再点一次安装即可。 */
fun unknownAppSourcesIntent(context: Context): Intent =
    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, ("package:" + context.packageName).toUri())

/** 匿名读不到 git 仓库时给出可操作的提示，而不是把 HTTP 码直接丢给用户。 */
private fun channelError(code: Int): String = when (code) {
    401, 403, 404 -> "更新通道不可匿名访问（HTTP $code）：需要把版本仓库设为公开，检查更新才能免登录工作。"
    else -> "更新通道返回 HTTP $code"
}
