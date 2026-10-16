package com.ddzhilian.chat

/*
 * 版本更新通道（协议层）。版本来源就是 git 仓库自身：先读 git smart HTTP 的 refs 广告拿到
 * refs/tags，再按最高版本 tag 取该 tag 的安装包。没有自建更新服务器，也不消耗 GitHub API 配额。
 *
 * 这里只有字符串与数值处理，网络和 Android 部分在 UpdateChannel.kt，因此可以直接在 JVM 单元测试里验证。
 */

const val UPDATE_REPOSITORY_OWNER = "CGR-MIX"
const val UPDATE_REPOSITORY_NAME = "android-chat"

/** 发布时随 tag 上传的安装包文件名，与 scripts/build-android-chat.mjs 的产物名一致。 */
const val UPDATE_ASSET_NAME = "ddzhilian-chat-debug.apk"

/** 回退通道：直接提交进 tag 的构建产物目录。 */
const val UPDATE_ASSET_DIRECTORY = "output"

/** git smart HTTP 的 refs 广告端点；GitHub 对公开仓库匿名开放，无需鉴权。 */
const val UPDATE_REFS_URL =
    "https://github.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME.git/info/refs?service=git-upload-pack"

val UPDATE_RELEASES_URL = "https://github.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases"

/** git 广告里的一行：对象名 + ref 全名。 */
data class GitRef(val sha: String, val name: String)

/** 语义化版本，只比较主/次/修订号。 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val raw: String) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = when {
        major != other.major -> major - other.major
        minor != other.minor -> minor - other.minor
        else -> patch - other.patch
    }
    override fun toString(): String = "$major.$minor.$patch"
}

/** 通道里的一个版本：tag 名（含 v 前缀）与解析后的版本号。 */
data class UpdateRelease(val tag: String, val version: AppVersion) {
    /** 先试 Release 附件，再回退到 tag 内提交的产物目录。 */
    val apkUrls: List<String> = listOf(
        "$UPDATE_RELEASES_URL/download/$tag/$UPDATE_ASSET_NAME",
        "https://raw.githubusercontent.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/$tag/$UPDATE_ASSET_DIRECTORY/$UPDATE_ASSET_NAME",
    )
    val pageUrl: String = "$UPDATE_RELEASES_URL/tag/$tag"
}

private val VERSION_PATTERN = Regex("^[vV]?(\\d{1,5})(?:\\.(\\d{1,5}))?(?:\\.(\\d{1,5}))?$")

/**
 * "v1.4.0" / "1.4" / "v2" 都能解析；带 `-`/`+` 的预发布或构建元数据不进正式通道，返回 null。
 */
fun parseVersion(raw: String): AppVersion? {
    val text = raw.trim()
    if (text.isEmpty() || text.any { it == '-' || it == '+' }) return null
    val match = VERSION_PATTERN.matchEntire(text) ?: return null
    return AppVersion(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].ifEmpty { "0" }.toInt(),
        patch = match.groupValues[3].ifEmpty { "0" }.toInt(),
        raw = text,
    )
}

/** `refs/tags/v1.4.0` → `v1.4.0`；分支、peeled tag（`^{}`）与非版本 tag 返回 null。 */
fun versionTagName(ref: String): String? {
    if (!ref.startsWith("refs/tags/")) return null
    val tag = ref.removePrefix("refs/tags/")
    if (tag.isEmpty() || tag.endsWith("^{}") || tag.endsWith("/")) return null
    return tag.takeIf { parseVersion(it) != null }
}

/**
 * git-upload-pack 的 refs 广告是 pkt-line 流：每行前 4 位十六进制是含自身在内的长度，
 * `0000` 为 flush（分段结束），直接跟在对象名后的 NUL 之后是一行能力列表（只出现在第一行）。
 */
fun parseRefAdvertisement(payload: String): List<GitRef> {
    val refs = mutableListOf<GitRef>()
    var index = 0
    while (index + 4 <= payload.length) {
        val size = payload.substring(index, index + 4).toIntOrNull(16) ?: return refs
        index += 4
        if (size == 0) continue // flush-pkt：继续读下一段
        if (size < 4) return refs // delim-pkt 或长度非法，之后的内容不可信
        val line = payload.substring(index, minOf(index + size - 4, payload.length))
        index += size - 4
        val text = line.substringBefore('\u0000').trimEnd('\n')
        if (text.isEmpty() || text.startsWith("#")) continue
        val space = text.indexOf(' ')
        if (space <= 0) continue
        val name = text.substring(space + 1).trim()
        if (name.isNotEmpty()) refs += GitRef(text.substring(0, space), name)
    }
    return refs
}

/** 仓库里版本号最高的 tag；一个版本 tag 都没有时返回 null。 */
fun latestRelease(refs: List<GitRef>): UpdateRelease? = refs
    .mapNotNull { ref -> versionTagName(ref.name)?.let { tag -> parseVersion(tag)?.let { UpdateRelease(tag, it) } } }
    .maxByOrNull { it.version }

/** 当前 versionName 是否落后于通道里的最新版本；当前版本解析不出来时不误报更新。 */
fun isUpdateAvailable(currentVersion: String, latest: AppVersion): Boolean {
    val current = parseVersion(currentVersion) ?: return false
    return current < latest
}
