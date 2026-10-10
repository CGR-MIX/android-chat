package com.ddzhilian.chat

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_SERVER = "https://ws.ddzhilian.com"
const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024

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
