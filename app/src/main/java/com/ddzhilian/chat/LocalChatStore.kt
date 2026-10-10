package com.ddzhilian.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

fun cacheKey(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

data class RoomPreference(val pinned: Boolean = false, val muted: Boolean = false, val readAt: String = "")
data class LocalChatData(val rooms: List<Room>, val messages: List<Message>, val preferences: Map<String, RoomPreference>, val recalled: Set<String>)

/** Private, per-origin AND per-device storage. Tokens are never written to disk. */
class LocalChatStore(context: Context, identity: String) {
    val key = cacheKey(identity)
    val pendingDir = File(context.filesDir, "native-chat/$key/pending").apply { mkdirs() }
    val mediaDir = File(context.cacheDir, "native-chat/$key/media").apply { mkdirs() }
    private val helper = object : SQLiteOpenHelper(context, "chat-$key.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, room TEXT NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX room_messages ON messages(room)")
            db.execSQL("CREATE TABLE metadata (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE recalled (id TEXT PRIMARY KEY)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<Pair<(SQLiteDatabase) -> Unit, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
    private val writer = worker.launch { for ((action, result) in writes) runCatching { action(helper.writableDatabase) }.fold({ result.complete(Unit) }, { result.completeExceptionally(it) }) }
    private fun write(action: (SQLiteDatabase) -> Unit): Deferred<Unit> = CompletableDeferred<Unit>().also { result ->
        if (writes.trySend(action to result).isFailure) result.completeExceptionally(IllegalStateException("本地存储已关闭"))
    }
    suspend fun flush() { write {}.await() }
    fun pendingFile(id: String) = File(pendingDir, cacheKey(id))
    fun mediaFile(id: String) = File(mediaDir, cacheKey(id))
    fun save(message: Message): Deferred<Unit> = write { db ->
        val removed = db.rawQuery("SELECT id FROM recalled WHERE id=?", arrayOf(message.id)).use { it.moveToFirst() }
        val alreadySent = message.delivery != Delivery.Sent && db.rawQuery("SELECT payload FROM messages WHERE id=?", arrayOf(message.id)).use {
            it.moveToFirst() && JSONObject(it.getString(0)).optString("delivery") == Delivery.Sent.name
        }
        if (!removed && !alreadySent) db.insertWithOnConflict("messages", null, ContentValues().apply {
            put("id", message.id); put("room", message.roomId); put("payload", message.toLocalJson().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun metadata(id: String, value: String): Deferred<Unit> = write { db -> db.insertWithOnConflict("metadata", null, ContentValues().apply { put("id", id); put("payload", value) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun saveRooms(rooms: List<Room>) = metadata("rooms", JSONArray().apply { rooms.forEach { put(it.toLocalJson()) } }.toString())
    fun savePreferences(preferences: Map<String, RoomPreference>) = metadata("preferences", JSONObject().apply {
        preferences.forEach { (id, p) -> put(id, JSONObject().put("pinned", p.pinned).put("muted", p.muted).put("readAt", p.readAt)) }
    }.toString())
    fun remove(id: String, recall: Boolean = false): Deferred<Unit> = write { db ->
        db.delete("messages", "id=?", arrayOf(id))
        if (recall) db.insertWithOnConflict("recalled", null, ContentValues().apply { put("id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
        mediaFile(id).delete(); pendingFile(id).delete()
    }
    suspend fun load(): LocalChatData = withContext(Dispatchers.IO) {
        flush()
        val db = helper.readableDatabase
        fun metadata(id: String) = db.rawQuery("SELECT payload FROM metadata WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
        val rooms = runCatching { JSONArray(metadata("rooms") ?: "[]").objects().map(::localRoom) }.getOrDefault(emptyList())
        val preferences = runCatching { JSONObject(metadata("preferences") ?: "{}").let { p -> p.keys().asSequence().associateWith { id ->
            p.getJSONObject(id).let { RoomPreference(it.optBoolean("pinned"), it.optBoolean("muted"), it.optString("readAt")) }
        } } }.getOrDefault(emptyMap())
        val messages = db.rawQuery("SELECT payload FROM messages", null).use { c -> buildList {
            while (c.moveToNext()) runCatching { localMessage(JSONObject(c.getString(0))) }.getOrNull()?.let { add(if (it.delivery == Delivery.Sending) it.copy(delivery = Delivery.Queued) else it) }
        } }
        val recalled = db.rawQuery("SELECT id FROM recalled", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        // Remove interrupted staging files, never durable outbox attachments.
        val keep = db.rawQuery("SELECT id FROM messages", null).use { c -> buildSet { while (c.moveToNext()) add(cacheKey(c.getString(0))) } }
        pendingDir.listFiles()?.filterNot { it.name in keep }?.forEach { it.delete() }
        LocalChatData(rooms, messages, preferences, recalled)
    }
    suspend fun clearDownloadedHistory() {
        write { db ->
            val ids = db.rawQuery("SELECT id,payload FROM messages", null).use { c -> buildList {
                while (c.moveToNext()) if (JSONObject(c.getString(1)).optString("delivery") == Delivery.Sent.name) add(c.getString(0))
            } }
            db.beginTransaction()
            try { ids.forEach { db.delete("messages", "id=?", arrayOf(it)) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
            mediaDir.listFiles()?.forEach { it.delete() }
        }.await()
    }
    fun close() { writes.close(); CoroutineScope(Dispatchers.IO).launch { writer.join(); helper.close(); worker.cancel() } }
}

fun Message.toLocalJson() = JSONObject().put("id", id).put("room", roomId).put("sender", senderId).put("name", senderName)
    .put("text", text).put("created", createdAt).put("delivery", delivery.name).put("file", fileName ?: JSONObject.NULL)
    .put("mime", mimeType).put("size", size).put("local", localAttachment)
fun localMessage(p: JSONObject) = Message(p.getString("id"), p.getString("room"), p.getString("sender"), p.optString("name"), p.optString("text"), p.getString("created"),
    Delivery.valueOf(p.optString("delivery", "Sent")), if (p.isNull("file")) null else p.optString("file"), p.optString("mime"), p.optLong("size"), p.optBoolean("local"))
fun Room.toLocalJson() = JSONObject().put("id", id).put("public", isPublic).put("index", index).put("preview", preview).put("updated", updated).put("reason", reason)
    .put("members", JSONArray().apply { members.forEach { put(JSONObject().put("deviceId", it.id).put("deviceName", it.name).put("platform", it.platform).put("online", false)) } })
fun localRoom(p: JSONObject) = Room(p.getString("id"), p.optBoolean("public"), p.optInt("index"), p.optJSONArray("members").objects().map(::parsePeer), p.optString("preview"), p.optString("updated"), p.optString("reason"))
val Message.isImage get() = fileName != null && mimeType.startsWith("image/")
val Message.isVoice get() = fileName != null && (mimeType.startsWith("audio/") || fileName.startsWith("voice_") && fileName.endsWith(".m4a"))

