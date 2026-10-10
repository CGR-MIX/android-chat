package com.ddzhilian.chat

import org.json.JSONObject

// Keep call identity separate from the room: one room may have several peer sessions.
data class CallSession(val id: String, val roomId: String, val peerId: String)
enum class CallPhase { Incoming, Outgoing, Connecting, Active }
data class NativeCall(
    val id: String, val session: CallSession, val peerName: String, val video: Boolean,
    val phase: CallPhase, val muted: Boolean = false, val cameraOff: Boolean = !video,
    val speaker: Boolean = video, val connectedAt: Long? = null, val remoteVideo: Boolean = false,
) {
    fun control() = JSONObject().put("callId", id).put("sessionId", session.id)
        .put("targetDeviceId", session.peerId).put("roomId", session.roomId).put("kind", if (video) "video" else "audio")
    fun matches(p: JSONObject) = p.optString("callId") == id && matchesSignal(p)
    fun matchesSignal(p: JSONObject) = p.optString("sessionId") == session.id && p.optString("fromDeviceId") == session.peerId
}
fun parseCallSessions(p: JSONObject) = p.optJSONArray("sessions").objects().map {
    CallSession(it.getString("sessionId"), it.getString("roomId"), it.getString("peerId"))
}

// Snapshots also contain historical previews. Seed once per connection, never notify old history.
class MessageNotificationCursor {
    private var seeded = false
    private val seen = mutableMapOf<String, String>()
    private val files = mutableSetOf<String>()
    fun reset() { seeded = false; seen.clear(); files.clear() }
    fun consume(snapshot: Snapshot, raw: JSONObject): List<Pair<String, String>> {
        val notifications = mutableListOf<Pair<String, String>>()
        raw.optJSONArray("rooms").objects().forEach { room ->
            val id = room.getString("roomId")
            val stamp = room.optString("historyTextLatestAt")
            val before = seen.put(id, stamp)
            if (seeded && before != null && stamp.isNotBlank() && stamp > before &&
                room.optString("historyTextLatestSourceDeviceId").let { it.isNotBlank() && it != snapshot.selfId } &&
                snapshot.rooms.any { it.id == id && it.joined(snapshot.selfId) }) {
                notifications += id to "有新消息"
            }
        }
        snapshot.files.forEach { file ->
            if (files.add(file.id) && seeded && file.senderId != snapshot.selfId &&
                snapshot.rooms.any { it.id == file.roomId && it.joined(snapshot.selfId) }) notifications += file.roomId to "收到新附件"
        }
        seeded = true
        return notifications.distinctBy { it.first }
    }
}

// A renewed server session supersedes stale IDs for the same room/peer pair.
fun replaceCallSession(sessions: List<CallSession>, session: CallSession) =
    sessions.filterNot { it.id == session.id || (it.roomId == session.roomId && it.peerId == session.peerId) } + session
