package com.ddzhilian.chat

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChatState(
    val server: String = DEFAULT_SERVER, val name: String = "Android 手机", val status: String = "连接中",
    val backgroundNotifications: Boolean = false,
    val archivedRoomIds: Set<String> = emptySet(), val localReady: Boolean = false, val preferences: Map<String, RoomPreference> = emptyMap(),
    val unread: Map<String, Int> = emptyMap(), val firstUnreadId: String? = null, val jumpMessageId: String? = null,
    val uploadProgress: TransferProgress? = null, val downloadProgress: TransferProgress? = null,
    val mediaRevision: Int = 0, val searchResults: List<Message> = emptyList(),
    val connected: Boolean = false, val selfId: String = "", val shortCode: String = "",
    val rooms: List<Room> = emptyList(), val peers: List<Peer> = emptyList(), val activeRoomId: String? = null,
    val messages: List<Message> = emptyList(), val drafts: Map<String, String> = emptyMap(),
    val error: String? = null, val loading: Boolean = false, val hasMore: Boolean = false,
    val transferring: Boolean = false, val notice: String? = null, val reduceTransparency: Boolean = false,
) {
    val room get() = rooms.firstOrNull { it.id == activeRoomId }
    val draft get() = drafts[activeRoomId].orEmpty()
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("native_chat", 0)
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val mutable = MutableStateFlow(ChatState(
        server = prefs.getString("server", DEFAULT_SERVER)!!,
        name = prefs.getString("name", Build.MODEL)!!,
        reduceTransparency = prefs.getBoolean("reduceTransparency", false),
        backgroundNotifications = prefs.getBoolean("backgroundNotifications", false),
    ))
    val state: StateFlow<ChatState> = mutable.asStateFlow()
    val calls = NativeCallController(app as ChatApplication, this)
    val voice = VoiceController(app, this)
    private val notifications = (app as ChatApplication).notifications
    private val notificationCursor = MessageNotificationCursor()
    var uiVisible = false
        private set
    fun visible(value: Boolean) {
        uiVisible = value
        if (!value) voice.stop()
        if (value) { connect(); if (state.value.backgroundNotifications) ChatConnectionService.keepConnected(getApplication(), true) }
    }
    fun backgroundNotifications(enabled: Boolean) {
        if (enabled && !notifications.enabled()) { showError("请在系统设置中允许通知后再开启"); return }
        prefs.edit().putBoolean("backgroundNotifications", enabled).apply()
        mutable.update { it.copy(backgroundNotifications = enabled) }
        ChatConnectionService.keepConnected(getApplication(), enabled)
    }
    private val auth = MutableStateFlow("")
    private var scope = newScope()
    private var socket: WebSocket? = null
    private var socketVersion = 0
    private var reconnectJob: Job? = null
    private var handshakeJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshTarget: String? = null
    private var refreshAgain = false
    private var retryCount = 0
    private var stopped = false
    private var address = ServerAddress.parse(state.value.server, BuildConfig.DEBUG)
    private val queues = mutableMapOf<String, Channel<Message>>()
    private val cache = mutableMapOf<String, List<Message>>()
    private val cursors = mutableMapOf<String, JSONObject?>()
    private val recalled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var liveRoomIds = emptySet<String>()
    private val refreshAuthMutex = Mutex()
    private var navigating = false
    val local by lazy { LocalChatStore(getApplication(), address.http.toString() + identity()) }
    private val queuedIds = mutableSetOf<String>()
    private val submittingRooms = mutableSetOf<String>()
    private var clearingCache = false
    private val sendJobs = mutableMapOf<String, Job>()
    private val uploads = Mutex()
    private val mediaLock = Mutex()
    private var stagingJob: Job? = null
    private var searchQuery = ""
    private var unreadBoundary = ""

    init { scope.launch {
        try {
            val saved = local.load()
            prefs.getString("interruptedDownload", null)?.let { showError("上次附件接收被中断，请重新保存：" + it); prefs.edit().remove("interruptedDownload").apply() }
            recalled.addAll(saved.recalled)
            saved.messages.sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id }).groupBy { it.roomId }.forEach { (id, list) -> cache[id] = list }
            mutable.update { it.copy(rooms = saved.rooms, preferences = saved.preferences, selfId = identity()) }
        } catch (e: Exception) { showError("读取本地记录失败：" + e.message) }
        finally {
            mutable.update { it.copy(localReady = true) }
            state.value.activeRoomId?.let { openRoom(it) }
            connect()
        }
    } }
    private fun newScope() = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
    private fun identity(): String {
        val key = "device:" + address.http
        return prefs.getString(key, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(key, it).apply() }
    }
    fun connect() {
        if (stopped || !state.value.localReady || state.value.connected) return
        reconnectJob?.cancel()
        val version = ++socketVersion
        notificationCursor.reset()
        socket?.cancel()
        auth.value = ""
        mutable.update { it.copy(connected = false, status = "连接中") }
        socket = client.newWebSocket(Request.Builder().url(address.websocket).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                scope.launch {
                    if (version != socketVersion) { ws.cancel(); return@launch }
                    ws.send(event("hello", JSONObject().put("deviceId", identity()).put("deviceName", state.value.name)
                        .put("platform", "android").put("discoverable", true).put("allowShortCode", true).put("autoConnect", false)))
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch {
                    if (version != socketVersion) return@launch
                    try { receive(JSONObject(text)) } catch (e: Exception) { showError("无法读取服务端消息：" + e.message) }
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { disconnected(version, response?.let { "服务连接失败 (HTTP " + it.code + ")" } ?: "网络连接中断") }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { disconnected(version) }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason); disconnected(version) }
        })
        handshakeJob?.cancel()
        handshakeJob = scope.launch { delay(20_000); if (version == socketVersion && !state.value.connected) { socket?.cancel(); disconnected(version) } }
    }
    private fun disconnected(version: Int, reason: String = "连接中断") {
        scope.launch {
            if (version != socketVersion || stopped || reconnectJob?.isActive == true) return@launch
            handshakeJob?.cancel()
            if (calls.state.value != null) calls.end("网络中断，通话已结束", notifyPeer = false)
            auth.value = ""
            mutable.update { it.copy(connected = false, status = reason + " · 正在重试") }
            reconnectJob = scope.launch {
                delay((1000L shl retryCount.coerceAtMost(5)).coerceAtMost(30_000))
                retryCount++
                reconnectJob = null
                connect()
            }
        }
    }
    private fun receive(e: JSONObject) {
        val p = e.optJSONObject("payload") ?: JSONObject()
        when (e.optString("type")) {
            "welcome", "directory-snapshot" -> {
                val s = parseSnapshot(p)
                val newMessages = notificationCursor.consume(s, p)
                handshakeJob?.cancel(); retryCount = 0; auth.value = s.token
                liveRoomIds = s.rooms.map { it.id }.toSet()
                val archived = state.value.rooms.filter { it.id !in liveRoomIds && cache[it.id].orEmpty().isNotEmpty() }.map { room -> room.copy(members = room.members.map { it.copy(online = false) }) }
                mutable.update { it.copy(connected = true, status = "已连接", selfId = s.selfId, shortCode = s.shortCode, rooms = s.rooms + archived, archivedRoomIds = archived.map { r -> r.id }.toSet(), peers = s.peers) }
                calls.snapshot(p)
                local.saveRooms(state.value.rooms)
                if (!clearingCache) s.files.forEach { local.save(it) }
                if (!uiVisible && state.value.backgroundNotifications) newMessages.filterNot { state.value.preferences[it.first]?.muted == true }.forEach { (id, _) -> s.rooms.firstOrNull { it.id == id }?.let(notifications::message) }
                // The server intentionally leaves historyTexts empty; text pages are fetched separately.
                if (!clearingCache) cache.keys.toList().forEach { roomId ->
                    cache[roomId] = mergeMessages(cache[roomId].orEmpty(), s.files.filter { it.roomId == roomId }, recalled)
                }
                if (!clearingCache) s.files.groupBy { it.roomId }.forEach { (id, files) -> cache[id] = mergeMessages(cache[id].orEmpty(), files, recalled) }
                val active = state.value.activeRoomId
                updateUnread(p)
                cache.values.flatten().filter { it.delivery == Delivery.Queued && it.roomId in s.rooms.filter { r -> r.joined(s.selfId) }.map { r -> r.id } }.forEach(::enqueue)
                if (e.optString("type") == "welcome" && active != null && s.rooms.none { it.id == active }) {
                    publishMessages(active); showError("此前房间已结束，本地记录仍可查看；请重新加入后发送")
                }
                if (!clearingCache && active != null && s.rooms.any { it.id == active && it.joined(s.selfId) }) {
                    publishMessages(active)
                    refresh(active)
                }
            }
            "public-room-created", "private-room-created" -> { navigating = false; openRoom(p.getString("roomId")) }
            "session-created" -> { calls.sessionCreated(p); if (navigating) { navigating = false; openRoom(p.getString("roomId")) } }
            "call-invite", "call-accept", "call-reject", "call-hangup", "signal" -> calls.receive(e.getString("type"), p)
            "history-recalled" -> {
                val id = p.getString("historyId"); val room = p.getString("roomId")
                local.remove(id, true); recalled.add(id); cache[room] = cache[room].orEmpty().filterNot { it.id == id }; publishMessages(room)
            }
            "error" -> { calls.serverError(p); navigating = false; if (state.value.activeRoomId != null && state.value.room == null) back(); showError(p.optString("message", "服务器拒绝了请求")) }
        }
    }
    fun command(type: String, payload: JSONObject = JSONObject()): Boolean {
        if (!state.value.connected || socket?.send(event(type, payload)) != true) { showError("尚未连接服务器，请稍后重试"); return false }
        return true
    }
    fun createRoom() { navigating = command("create-public-room") }
    fun privateChat(peer: Peer) { navigating = command("request-connect", JSONObject().put("targetDeviceId", peer.id).put("reason", "manual").put("createNewRoom", true)) }
    fun pair(code: String) {
        if (code.trim().isEmpty()) return
        navigating = command("pair-by-short-code", JSONObject().put("shortCode", code.trim()))
    }
    fun join(id: String) { if (command("join-room", JSONObject().put("roomId", id.trim()))) openRoom(id.trim()) }
    fun openRoom(id: String) {
        voice.stop()
        unreadBoundary = if ((state.value.unread[id] ?: 0) > 0) state.value.preferences[id]?.readAt.orEmpty() else ""
        notifications.clearRoom(id)
        val room = state.value.rooms.firstOrNull { it.id == id }
        if (state.value.connected && room != null && !room.joined(state.value.selfId)) command("join-room", JSONObject().put("roomId", id))
        val savedDraft = prefs.getString("draft:" + address.http + ":" + id, "").orEmpty()
        mutable.update { it.copy(activeRoomId = id, messages = cache[id].orEmpty(), drafts = it.drafts + (id to (it.drafts[id] ?: savedDraft)), hasMore = cursors[id] != null, firstUnreadId = null, jumpMessageId = null) }
        publishMessages(id)
        if (state.value.connected && id in liveRoomIds && room?.joined(state.value.selfId) == true) refresh(id)
    }
    fun back() { voice.stop(); refreshJob?.cancel(); mutable.update { it.copy(activeRoomId = null, loading = false) } }
    fun draft(text: String) {
        val room = state.value.activeRoomId ?: return
        mutable.update { it.copy(drafts = it.drafts + (room to text)) }
        prefs.edit().putString("draft:" + address.http + ":" + room, text).apply()
    }
    fun sendText() {
        val room = state.value.room ?: return
        val text = state.value.draft.trim()
        if (text.isEmpty() || room.id in state.value.archivedRoomIds || !room.joined(state.value.selfId) || !submittingRooms.add(room.id)) return
        val message = Message(UUID.randomUUID().toString(), room.id, state.value.selfId, state.value.name, text, Instant.now().toString(), Delivery.Queued)
        scope.launch {
            try {
                local.save(message).await()
                putMessage(message)
                // Only clear the submitted draft, never a newer one typed during persistence.
                if (state.value.activeRoomId == room.id && state.value.draft.trim() == text) draft("")
                if (state.value.connected) enqueue(message)
            } catch (e: Exception) { showError("无法保存待发送消息：" + e.message) }
            finally { submittingRooms.remove(room.id) }
        }
    }
    fun retry(message: Message) {
        if (message.delivery !in setOf(Delivery.Failed, Delivery.Queued)) return
        val pending = message.copy(delivery = Delivery.Queued)
        putMessage(pending)
        if (state.value.connected) enqueue(pending)
    }
    fun cancelPending(message: Message) {
        sendJobs[message.id]?.cancel()
        cache[message.roomId] = cache[message.roomId].orEmpty().filterNot { it.id == message.id }
        local.remove(message.id)
        publishMessages(message.roomId)
    }
    private fun enqueue(message: Message) {
        if (!queuedIds.add(message.id)) return
        val queue = queues.getOrPut(message.roomId) {
            Channel<Message>(Channel.UNLIMITED).also { channel -> scope.launch {
                for (item in channel) {
                    if (cache[item.roomId].orEmpty().none { it.id == item.id && it.delivery != Delivery.Sent }) { queuedIds.remove(item.id); continue }
                    val task = launch {
                        try {
                            local.flush()
                            withTimeout(30_000) { state.first { it.connected } }
                            require(item.roomId in liveRoomIds && state.value.rooms.any { it.id == item.roomId && it.joined(state.value.selfId) }) { "原房间已结束，请保留内容并重新加入" }
                            if (item.fileName == null) {
                                putMessage(item.copy(delivery = Delivery.Sending))
                                val response = json(address.api("api/history/text"), "POST", textPayload(item).toString().toRequestBody("application/json".toMediaType()))
                                putMessage(parseMessage(response.getJSONObject("text")))
                            } else uploads.withLock {
                                val file = local.pendingFile(item.id)
                                require(file.exists()) { "待发送附件已丢失，请重新选择" }
                                putMessage(item.copy(delivery = Delivery.Sending))
                                mutable.update { it.copy(transferring = true, uploadProgress = TransferProgress(item.id, item.roomId, item.fileName, total = file.length(), phase = "正在发送")) }
                                val sendingJob = currentCoroutineContext()[Job]!!
                                val body = object : RequestBody() {
                                    override fun contentType() = item.mimeType.toMediaType()
                                    override fun contentLength() = file.length()
                                    override fun writeTo(sink: okio.BufferedSink) {
                                        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); var count = 0L
                                            while (true) { sendingJob.ensureActive(); val n = input.read(buffer); if (n < 0) break; sink.write(buffer, 0, n); count += n
                                                mutable.update { it.copy(uploadProgress = it.uploadProgress?.takeIf { p -> p.id == item.id }?.copy(received = count)) }
                                            }
                                        }
                                    }
                                }
                                try {
                                    val url = address.api("api/history/upload").newBuilder().addQueryParameter("roomId", item.roomId).addQueryParameter("historyId", item.id).build()
                                    val result = json(url, "POST", body, mapOf("x-file-name" to URLEncoder.encode(item.fileName, "UTF-8").replace("+", "%20"), "x-file-created-at" to item.createdAt))
                                    val sent = parseMessage(result.getJSONObject("file"), true)
                                    withContext(Dispatchers.IO) { if (file.exists()) file.copyTo(local.mediaFile(sent.id), overwrite = true) }
                                    putMessage(sent); local.flush(); withContext(Dispatchers.IO) { file.delete() }
                                } finally { mutable.update { it.copy(transferring = false, uploadProgress = null) } }
                            }
                        } catch (e: TimeoutCancellationException) { putMessage(item.copy(delivery = Delivery.Failed)); showError("等待网络超时，内容已保留，可点击重试") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { putMessage(item.copy(delivery = Delivery.Failed)); showError(e.message ?: "发送失败，点按重试") }
                    }
                    sendJobs[item.id] = task
                    task.join(); sendJobs.remove(item.id); queuedIds.remove(item.id)
                }
            } }
        }
        queue.trySend(message)
    }
    private fun putMessage(message: Message) {
        if (message.delivery != Delivery.Sent && cache[message.roomId].orEmpty().any { it.id == message.id && it.delivery == Delivery.Sent }) return
        cache[message.roomId] = mergeMessages(cache[message.roomId].orEmpty(), listOf(message), recalled)
        local.save(message)
        publishMessages(message.roomId)
        if (searchQuery.isNotBlank()) searchMessages(searchQuery)
    }
    private fun publishMessages(id: String) {
        if (state.value.activeRoomId != id) return
        val list = cache[id].orEmpty()
        val first = if (unreadBoundary.isNotBlank()) list.firstOrNull { it.senderId != state.value.selfId && it.createdAt > unreadBoundary }?.id else null
        mutable.update { it.copy(messages = list, firstUnreadId = it.firstUnreadId ?: first) }
    }
    fun locateFirstUnread() {
        val id = state.value.activeRoomId ?: return
        val boundary = unreadBoundary.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            while (state.value.activeRoomId == id && state.value.connected && cursors[id] != null &&
                cache[id].orEmpty().filter { it.fileName == null && it.delivery == Delivery.Sent }.minOfOrNull { it.createdAt }?.let { it > boundary } == true) {
                val before = cursors[id].toString()
                refresh(id, true); refreshJob?.join()
                if (cursors[id].toString() == before) break
            }
            if (state.value.activeRoomId != id) return@launch
            val target = cache[id].orEmpty().firstOrNull { it.senderId != state.value.selfId && it.createdAt > boundary }?.id
            mutable.update { it.copy(firstUnreadId = target, jumpMessageId = target) }
        }
    }
    fun loadOlder() { state.value.activeRoomId?.let { refresh(it, true) } }
    private fun refresh(id: String, older: Boolean = false) {
        if (!state.value.connected || id !in liveRoomIds || clearingCache) return
        // Keep a pagination request intact when unrelated directory broadcasts arrive.
        if (older && state.value.loading) return
        if (!older && state.value.loading && refreshTarget == id) { refreshAgain = true; return }
        refreshJob?.cancel()
        refreshTarget = id
        refreshAgain = false
        refreshJob = scope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val url = address.api("api/history/text").newBuilder().addQueryParameter("roomId", id).addQueryParameter("limit", "100")
                if (older) cursors[id]?.let { url.addQueryParameter("beforeCreatedAt", it.getString("createdAt")).addQueryParameter("beforeHistoryId", it.getString("historyId")) }
                val page = parseHistory(json(url.build()))
                val incomingIds = page.messages.map { it.id }.toSet()
                val historyGap = !older && page.hasMore && cache[id].orEmpty().none { it.fileName == null && it.delivery == Delivery.Sent && it.id in incomingIds }
                cache[id] = mergeMessages(cache[id].orEmpty(), page.messages, recalled)
                page.messages.forEach { local.save(it) }
                // A live latest-page refresh must not overwrite the cursor for older loaded pages.
                if (older || !cursors.containsKey(id) || historyGap) cursors[id] = if (page.hasMore) page.cursor else null
                publishMessages(id)
                if (state.value.activeRoomId == id) mutable.update { it.copy(hasMore = cursors[id] != null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e.message ?: "加载消息失败") }
            finally {
                mutable.update { it.copy(loading = false) }
                if (isActive && refreshAgain && state.value.activeRoomId == id) { refreshAgain = false; refresh(id) }
            }
        }
    }
    fun recall(message: Message) {
        if (message.senderId != state.value.selfId || message.delivery != Delivery.Sent) return
        scope.launch {
            try {
                json(address.api("api/history/" + (if (message.fileName == null) "text/" else "file/") + message.id), "DELETE")
                local.remove(message.id, true); recalled.add(message.id); cache[message.roomId] = cache[message.roomId].orEmpty().filterNot { it.id == message.id }; publishMessages(message.roomId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e.message ?: "撤回失败") }
        }
    }
    fun upload(uri: Uri, roomId: String, original: Boolean = true) {
        val room = state.value.rooms.firstOrNull { it.id == roomId } ?: return
        if (stagingJob?.isActive == true || state.value.transferring || roomId in state.value.archivedRoomIds || !room.joined(state.value.selfId)) return
        val id = UUID.randomUUID().toString()
        stagingJob = scope.launch {
            val file = local.pendingFile(id)
            var persisted = false
            mutable.update { it.copy(transferring = true, uploadProgress = TransferProgress(id, roomId, "附件", phase = "准备附件")) }
            try {
                val (name, mime) = AttachmentFiles.stage(getApplication(), uri, file, original) { bytes, total ->
                    mutable.update { it.copy(uploadProgress = it.uploadProgress?.copy(received = bytes, total = total)) }
                }
                val message = Message(id, roomId, state.value.selfId, state.value.name, "", Instant.now().toString(), Delivery.Queued, name, mime, file.length(), true)
                ensureActive()
                withContext(NonCancellable) { local.save(message).await(); putMessage(message); persisted = true }
                if (state.value.connected) enqueue(message)
            } catch (e: CancellationException) { if (!persisted) file.delete(); throw e }
            catch (e: Exception) { if (!persisted) file.delete(); showError(e.message ?: "无法准备附件") }
            finally { mutable.update { it.copy(transferring = false, uploadProgress = null) } }
        }
    }
    fun sendVoice(file: File, roomId: String, duration: Int) {
        val id = UUID.randomUUID().toString()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { file.copyTo(local.pendingFile(id), overwrite = true); file.delete() }
                val message = Message(id, roomId, state.value.selfId, state.value.name, "", Instant.now().toString(), Delivery.Queued,
                    "voice_" + duration + "s.m4a", "audio/mp4", local.pendingFile(id).length(), true)
                local.save(message).await(); putMessage(message)
                if (state.value.connected) enqueue(message)
            } catch (e: Exception) { showError(e.message ?: "语音保存失败") }
        }
    }
    fun cancelUpload() {
        val p = state.value.uploadProgress ?: return
        if (p.phase == "准备附件") stagingJob?.cancel()
        else cache[p.roomId].orEmpty().firstOrNull { it.id == p.id }?.let { message ->
            sendJobs[p.id]?.cancel(); putMessage(message.copy(delivery = Delivery.Failed))
        }
    }
    suspend fun attachmentFile(message: Message): File = mediaLock.withLock {
        withContext(Dispatchers.IO) {
            require(message.id !in recalled) { "附件已撤回" }
            if (message.localAttachment && local.pendingFile(message.id).exists()) return@withContext local.pendingFile(message.id)
            val file = local.mediaFile(message.id)
            if (file.exists()) return@withContext file
            require(state.value.connected) { "附件尚未缓存，请联网后重试" }
            val part = File(local.mediaDir, cacheKey(message.id) + ".part")
            try {
                authorized(address.api("api/history/download/" + message.id)).useCancellable { response ->
                    checkResponse(response)
                    response.body!!.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(65536); var total = 0L
                        while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX_ATTACHMENT_BYTES) { "附件超过大小限制" }; output.write(buffer, 0, n) }
                    } }
                }
                require(message.id !in recalled) { "附件已撤回" }
                check(part.renameTo(file)) { "缓存写入失败" }
                file
            } finally { part.delete() }
        }
    }
    fun download(message: Message, destination: Uri) {
        if (state.value.downloadProgress != null) { showError("请先完成或取消当前接收"); return }
        val id = UUID.randomUUID().toString()
        downloadChanged(TransferProgress(id, message.roomId, message.fileName ?: "附件"))
        try { AttachmentTransferService.start(getApplication(), message, destination, id) }
        catch (e: Exception) { downloadChanged(null); showError(e.message ?: "无法启动附件接收") }
    }
    fun downloadChanged(progress: TransferProgress?) { mutable.update { it.copy(downloadProgress = progress) } }
    fun cancelDownload() { state.value.downloadProgress?.let { AttachmentTransferService.cancel(getApplication(), it.id) } }
    suspend fun receiveAttachment(message: Message, destination: Uri, onProgress: suspend (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        require(message.id !in recalled) { "附件已撤回" }
        val cached = if (message.localAttachment) local.pendingFile(message.id) else local.mediaFile(message.id)
        val resolver = getApplication<Application>().contentResolver
        suspend fun copy(input: java.io.InputStream, expected: Long) {
            var received = 0L
            resolver.openOutputStream(destination, "wt")?.use { output ->
                val bytes = ByteArray(65536); onProgress(0, expected)
                while (true) { ensureActive(); val n = input.read(bytes); if (n < 0) break; output.write(bytes, 0, n); received += n; onProgress(received, expected) }
                require(expected < 0 || received == expected) { "附件未完整接收，请重试" }
            } ?: error("无法写入选择的位置")
        }
        if (cached.exists()) cached.inputStream().use { copy(it, cached.length()) }
        else {
            require(state.value.connected) { "请联网后接收此附件" }
            authorized(address.api("api/history/download/" + message.id)).use { response ->
                checkResponse(response); val body = response.body ?: error("附件为空")
                body.byteStream().use { copy(it, body.contentLength()) }
            }
        }
    }
    fun roomPreference(id: String, pinned: Boolean? = null, muted: Boolean? = null) {
        val old = state.value.preferences[id] ?: RoomPreference()
        val updated = state.value.preferences + (id to old.copy(pinned = pinned ?: old.pinned, muted = muted ?: old.muted))
        mutable.update { it.copy(preferences = updated) }; local.savePreferences(updated)
        if (pinned != null && state.value.connected) command("update-room-state", JSONObject().put("roomId", id).put("pinned", pinned))
    }
    fun markVisibleRead(id: String, latest: String) {
        if (!uiVisible || state.value.activeRoomId != id || latest.isBlank()) return
        val old = state.value.preferences[id] ?: RoomPreference()
        if (old.readAt >= latest) return
        val next = state.value.preferences + (id to old.copy(readAt = latest))
        mutable.update { it.copy(preferences = next, unread = it.unread + (id to 0)) }; local.savePreferences(next)
        if (state.value.connected && id in liveRoomIds) command("update-room-state", JSONObject().put("roomId", id).put("lastReadAt", latest))
    }
    private fun updateUnread(raw: JSONObject) {
        val preferences = state.value.preferences.toMutableMap()
        raw.optJSONArray("rooms").objects().forEach { room ->
            val id = room.getString("roomId")
            if (id !in preferences) preferences[id] = RoomPreference(readAt = room.optString("historyTextLatestAt").ifBlank { raw.optString("serverTime").ifBlank { Instant.now().toString() } })
        }
        val unread = state.value.rooms.associate { room ->
            val boundary = preferences[room.id]?.readAt.orEmpty()
            val count = cache[room.id].orEmpty().count { it.delivery == Delivery.Sent && it.senderId != state.value.selfId && it.createdAt > boundary }
            val summary = raw.optJSONArray("rooms").objects().firstOrNull { it.optString("roomId") == room.id }
            val more = summary?.optString("historyTextLatestAt").orEmpty() > boundary && summary?.optString("historyTextLatestSourceDeviceId") != state.value.selfId
            room.id to if (!room.joined(state.value.selfId)) 0 else maxOf(count, if (more) 1 else 0)
        }
        mutable.update { it.copy(preferences = preferences, unread = unread) }; local.savePreferences(preferences)
    }
    fun searchMessages(query: String) {
        searchQuery = query
        mutable.update { it.copy(searchResults = if (query.isBlank()) emptyList() else cache.values.flatten().filter { m ->
            m.id !in recalled && (m.text.contains(query, true) || m.fileName?.contains(query, true) == true)
        }.sortedByDescending { m -> m.createdAt }.take(100)) }
    }
    fun openSearchResult(message: Message) { openRoom(message.roomId); mutable.update { it.copy(jumpMessageId = message.id) } }
    fun clearCache() {
        if (state.value.transferring || state.value.downloadProgress != null) { showError("传输完成后再清理缓存"); return }
        scope.launch {
            try {
                clearingCache = true; refreshAgain = false; refreshJob?.cancelAndJoin()
                voice.stop(); mediaLock.withLock { local.clearDownloadedHistory() }
                cache.keys.toList().forEach { id -> cache[id] = cache[id].orEmpty().filter { it.delivery != Delivery.Sent } }
                cursors.clear(); state.value.activeRoomId?.let(::publishMessages)
                mutable.update { it.copy(mediaRevision = it.mediaRevision + 1, searchResults = emptyList(), notice = "已清理缓存，待发送内容和草稿已保留") }
            } catch (e: Exception) { showError(e.message ?: "清理失败") }
            finally { clearingCache = false }
        }
    }
    private suspend fun json(url: HttpUrl, method: String = "GET", body: RequestBody? = null, headers: Map<String, String> = emptyMap()): JSONObject =
        authorized(url, method, body, headers).use { response ->
            withContext(Dispatchers.IO) { checkResponse(response); JSONObject(response.body?.string().orEmpty().ifBlank { "{}" }) }
        }
    private suspend fun authorized(url: HttpUrl, method: String = "GET", body: RequestBody? = null, headers: Map<String, String> = emptyMap()): Response {
        val token = withTimeout(20_000) { auth.first { it.isNotEmpty() } }
        suspend fun call(token: String): Response {
            val request = Request.Builder().url(url).header("Authorization", "Bearer " + token).method(method, body)
            headers.forEach { (k, v) -> request.header(k, v) }
            return client.newCall(request.build()).await()
        }
        val response = call(token)
        if (response.code != 401) return response
        response.close()
        refreshAuthMutex.withLock {
            if (auth.value == token) { mutable.update { it.copy(connected = false) }; connect() }
            withTimeout(20_000) { auth.first { it.isNotEmpty() && it != token } }
        }
        return call(auth.value)
    }
    private fun checkResponse(response: Response) {
        if (!response.isSuccessful) {
            val error = runCatching { JSONObject(response.body?.string().orEmpty()).optString("error") }.getOrNull()
            throw IOException("请求失败 (" + response.code + ")：" + (error?.take(220)?.ifBlank { "服务暂不可用" } ?: "服务暂不可用"))
        }
    }
    fun settings(name: String, reduce: Boolean) {
        if (name.trim().isEmpty()) { showError("请填写设备名称"); return }
        // The connection configuration is intentionally not editable from the settings screen.
        prefs.edit().putString("name", name.trim()).putBoolean("reduceTransparency", reduce).apply()
        mutable.update { it.copy(name = name.trim(), reduceTransparency = reduce, notice = "设置已保存") }
        if (state.value.connected) command("update-settings", JSONObject().put("deviceName", name.trim())) else connect()
    }
    fun showError(message: String) { mutable.update { it.copy(error = message) } }
    fun clearNotice() { mutable.update { it.copy(error = null, notice = null) } }
    override fun onCleared() { voice.stop(); calls.dispose(); local.close(); stopped = true; socketVersion++; socket?.close(1000, "App closed"); scope.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); super.onCleared() }
}
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response, onCancellation = { _, value, _ -> value.close() }) }
    })
}


/** Closing the response unblocks a stalled network read when the transfer is cancelled. */
private suspend fun <T> Response.useCancellable(block: suspend (Response) -> T): T = coroutineScope {
    val response = this@useCancellable
    val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { response.close() }
    }
    try { block(response) } finally { watcher.cancel(); response.close() }
}
