package com.ddzhilian.chat

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_SERVER = "https://ws.ddzhilian.com"
const val MAX_ATTACHMENT_BYTES = 500L * 1024 * 1024
/** 玻璃填充的不透明度固定为 40%：设置页不再提供调节入口，也不再读取历史偏好。 */
const val GLASS_OPACITY = .40f
/** Lowest allowed value: deliberately very transparent, the blur still separates it from the page. */
const val MIN_GLASS_OPACITY = .15f
/** Upper bound for one picker batch, so a single tap cannot queue an unbounded transfer. */
const val MAX_BATCH_ATTACHMENTS = 9
/** 面向用户的单附件上限文案，避免提示与常量脱节。 */
val MAX_ATTACHMENT_LABEL = (MAX_ATTACHMENT_BYTES / (1024L * 1024L)).toString() + " MB"

data class ServerAddress(val http: HttpUrl) {
    val websocket: String get() = http.newBuilder().addPathSegment("ws").build().toString()
        .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
    fun api(path: String): HttpUrl = http.newBuilder().addPathSegments(path.removePrefix("/")).build()
    companion object {
        fun parse(raw: String, allowHttp: Boolean): ServerAddress {
            val url = raw.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty()) { "服务器地址不能包含账号或密码" }
            require(url.encodedPath == "/" && url.query == null && url.fragment == null) { "请填写服务器根地址，不要附加路径、查询或 /ws" }
            require(url.isHttps || allowHttp) { "正式版本仅允许 HTTPS" }
            return ServerAddress(url)
        }
    }
}

data class Peer(val id: String, val name: String, val platform: String, val online: Boolean)
data class Room(val id: String, val isPublic: Boolean, val index: Int, val members: List<Peer>, val preview: String, val updated: String, val reason: String = "") {
    val isLan get() = reason == "lan-discovery"
    fun title(selfId: String) = if (isPublic) "公共聊天室" + if (index > 1) " · $index" else ""
        else if (isLan) "局域网聊天室" else members.filter { it.id != selfId }.joinToString("、") { it.name }.ifBlank { "私聊" }
    fun joined(selfId: String) = members.any { it.id == selfId }
}
enum class Delivery { Sent, Queued, Sending, Failed }
data class Message(
    val id: String, val roomId: String, val senderId: String, val senderName: String,
    val text: String, val createdAt: String, val delivery: Delivery = Delivery.Sent,
    val fileName: String? = null, val mimeType: String = "application/octet-stream", val size: Long = 0,
    val localAttachment: Boolean = false,
)
data class HistoryPage(val messages: List<Message>, val hasMore: Boolean, val cursor: JSONObject?)
data class Snapshot(val selfId: String, val token: String, val shortCode: String, val rooms: List<Room>, val peers: List<Peer>, val files: List<Message>)

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun parsePeer(p: JSONObject) = Peer(p.getString("deviceId"), p.optString("deviceName", "未命名设备"), p.optString("platform"), p.optBoolean("online", true))
fun parseMessage(m: JSONObject, file: Boolean = false) = Message(
    id = m.getString("historyId"), roomId = m.getString("roomId"), senderId = m.getString("sourceDeviceId"),
    senderName = m.optString("sourceDeviceName", "设备"), text = m.optString("text"), createdAt = m.getString("createdAt"),
    fileName = if (file) m.getString("fileName") else null, mimeType = m.optString("mimeType", "application/octet-stream"), size = m.optLong("size"),
)
fun parseSnapshot(p: JSONObject): Snapshot {
    val self = p.getJSONObject("self")
    return Snapshot(self.getString("deviceId"), self.getString("historyAuthToken"), self.getString("shortCode"),
        p.optJSONArray("rooms").objects().map { r -> Room(r.getString("roomId"), r.optBoolean("isPublic"), r.optInt("publicIndex", 1),
            r.optJSONArray("members").objects().map(::parsePeer), r.optString("historyTextPreview"), r.optString("historyTextLatestAt", r.optString("updatedAt")), r.optString("reason")) },
        p.optJSONArray("peers").objects().map(::parsePeer), p.optJSONArray("historyFiles").objects().map { parseMessage(it, true) })
}
fun parseHistory(p: JSONObject) = HistoryPage(p.optJSONArray("texts").objects().map { parseMessage(it) }, p.optBoolean("hasMore"), p.optJSONObject("nextCursor"))
fun mergeMessages(old: List<Message>, incoming: List<Message>, recalled: Set<String> = emptySet()): List<Message> =
    (old + incoming).associateBy { it.id }.values.filterNot { it.id in recalled }.sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id })
fun event(type: String, payload: JSONObject = JSONObject()): String = JSONObject().put("type", type).put("payload", payload).toString()
fun textPayload(m: Message): JSONObject = JSONObject().put("historyId", m.id).put("roomId", m.roomId).put("text", m.text).put("createdAt", m.createdAt)

/** 服务端保存 AI 回复时使用的固定设备身份，用于在消息流里区分助手发言。 */
const val AI_BOT_DEVICE_ID = "bot_cloudflare_ai"
const val DEFAULT_AI_NAME = "DD直连小助手"
val DEFAULT_AI_WAKE_WORDS = listOf("ai", "bot")

/** 管理员在后台配置的助手品牌信息，与网页端 /api/ai/branding 一致。 */
data class AiBranding(
    val name: String = DEFAULT_AI_NAME,
    val wakeWords: List<String> = DEFAULT_AI_WAKE_WORDS,
    val avatarUrl: String? = null,
    val memoryTurns: Int = 0,
)

fun parseAiBranding(p: JSONObject): AiBranding {
    val wakeWords = p.optJSONArray("wakeWords")?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optString(index).trim().ifEmpty { null } }
    }.orEmpty()
    return AiBranding(
        name = p.optString("aiName").trim().ifEmpty { DEFAULT_AI_NAME },
        wakeWords = wakeWords.ifEmpty { DEFAULT_AI_WAKE_WORDS },
        avatarUrl = p.optString("avatarUrl").trim().ifEmpty { null },
        memoryTurns = p.optInt("memoryTurns", 0),
    )
}

private const val AI_MENTION_SEPARATORS = " \t\r\n:：,，"

/**
 * 解析 `@名称 问题` / `@唤醒词 问题`，返回提问正文；不是对助手的提及则返回 null。
 * 规则与网页端 `startsWithAiAssistantMention` / `parseAiAssistantMentionPrompt` 保持一致：
 * 提及必须出现在开头，且名称之后必须是分隔符或结尾，避免把 `@aix` 误判成 `@ai`。
 */
fun parseAiMention(text: String, branding: AiBranding): String? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("@")) return null

    // 长名称优先，避免短唤醒词抢走更长的助手名。
    val candidates = (listOf(branding.name) + branding.wakeWords)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedByDescending { it.length }
    val lower = trimmed.lowercase()

    for (candidate in candidates) {
        val label = "@" + candidate.lowercase()
        if (!lower.startsWith(label)) continue
        val rest = trimmed.substring(label.length)
        if (rest.isNotEmpty() && rest.first() !in AI_MENTION_SEPARATORS) continue
        return rest.dropWhile { it in AI_MENTION_SEPARATORS }.trim()
    }

    return null
}

private val aiHtmlLineBreak = Regex("(?i)<br\\s*/?>")
private val aiHtmlBlockEnd = Regex("(?i)</(p|div|li|h[1-6]|tr|blockquote)\\s*>")
private val aiHtmlListItem = Regex("(?i)<li\\s*>")
private val aiHtmlTag = Regex("<[^>]*>")
private val aiHtmlNumericEntity = Regex("&#(x?)([0-9a-fA-F]+);")
private val aiHtmlBlankLines = Regex("\n{3,}")

/**
 * 服务端保存的 AI 回复是富文本 HTML（`<p><strong>@某人</strong>…`），网页直接注入渲染，
 * 原生端只能显示纯文本，因此这里做一次最小清洗：块级标签转换行、去标签、解实体。
 */
fun stripChatMarkup(raw: String): String {
    if (raw.isEmpty() || (!raw.contains('<') && !raw.contains('&'))) return raw

    var text = raw
        .replace(aiHtmlLineBreak, "\n")
        .replace(aiHtmlBlockEnd, "\n")
        .replace(aiHtmlListItem, "· ")
        .replace(aiHtmlTag, "")

    if (text.contains('&')) {
        text = text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        text = aiHtmlNumericEntity.replace(text) { match ->
            val code = runCatching {
                if (match.groupValues[1].isNotEmpty()) {
                    match.groupValues[2].toInt(16)
                } else {
                    match.groupValues[2].toInt()
                }
            }.getOrNull()
            code?.takeIf { Character.isValidCodePoint(it) }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
        text = text.replace("&amp;", "&")
    }

    return text.replace(aiHtmlBlankLines, "\n\n").trim()
}
