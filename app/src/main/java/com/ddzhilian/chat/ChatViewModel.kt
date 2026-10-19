package com.ddzhilian.chat

import android.app.Application
import android.graphics.Bitmap
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

/** 管理员模式的界面状态：隐秘入口 → 密码验证 → 面板。 */
sealed interface AdminMode {
    object Closed : AdminMode
    object Gate : AdminMode
    data class Open(val dashboard: AdminDashboard) : AdminMode
}

/** 历史暂存文件列表里的一行（服务端 /api/admin/history/files）。 */
data class AdminHistoryFile(
    val historyId: String,
    val roomId: String,
    val fileName: String,
    val size: Long,
    val createdAt: String,
    val senderName: String,
    val mimeType: String,
)

fun parseAdminHistoryFile(p: JSONObject): AdminHistoryFile = AdminHistoryFile(
    historyId = p.optString("historyId"),
    roomId = p.optString("roomId"),
    fileName = p.optString("fileName", "未命名文件"),
    size = p.optLong("size"),
    createdAt = p.optString("createdAt"),
    senderName = p.optString("sourceDeviceName", "设备"),
    mimeType = p.optString("mimeType", "application/octet-stream"),
)
/** 一次性打开请求：把附件交给系统默认应用时需要的 URI 与 MIME。 */
data class AttachmentOpen(val uri: Uri, val mimeType: String)

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
    val glassOpacity: Float = GLASS_OPACITY,
    val update: UpdateState = UpdateState.Idle,
    /** 下载完成后要交给系统默认应用打开的一次性请求；界面消费后清空。 */
    val openRequest: AttachmentOpen? = null,
    /** 后台配置的助手名称/唤醒词/头像，拉取失败时保持默认值。 */
    val ai: AiBranding = AiBranding(),
    /** 正在等待 AI 回复的房间；界面据此显示「思考中」。 */
    val aiThinkingRoom: String? = null,
    val aiAvatarRevision: Int = 0,
    val admin: AdminMode = AdminMode.Closed,
    val adminBusy: Boolean = false,
    val adminError: String? = null,
    val adminNotice: String? = null,
    val adminHistoryFiles: List<AdminHistoryFile> = emptyList(),
    /** 非空表示还能继续加载更多历史附件。 */
    val adminHistoryCursor: AdminHistoryCursor? = null,
    val adminHistoryLoading: Boolean = false,
) {
    val room get() = rooms.firstOrNull { it.id == activeRoomId }
    val draft get() = drafts[activeRoomId].orEmpty()
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("native_chat", 0)
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    /** 附件上传/下载不限总时长：只在单次读写卡住（readTimeout）或连接失败时才中断。 */
    private val transferClient = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()
    /** 更新通道要跟随 GitHub 到对象存储的 302 跳转，因此单独放开重定向且不限总时长。 */
    private val updateClient = client.newBuilder().followRedirects(true).followSslRedirects(true).callTimeout(0, TimeUnit.SECONDS).build()
    private val updateChannel = UpdateChannel(updateClient, File(app.cacheDir, "updates"))
    /** 用户点开一个还没下载的附件时记下的意图：接收完成后立刻用系统默认应用打开。 */
    private var pendingOpenId: String? = null
    /** AI 助手品牌、提问与管理员会话共用同一套 HTTP 客户端配置。 */
    private val aiClient by lazy { AiClient(address, client) }
    // branding 里的头像地址是相对路径，用当前服务端根地址补全（lambda 而不是快照，避免换地址后过期）。
    private val aiAvatars by lazy { AiAvatarStore(getApplication(), client) { address.http } }
    private val adminCredentials by lazy { AdminCredentialStore(getApplication()) }
    private var aiBrandingLoaded = false
    /** 「版本」连点计数：超时或成功进入后归零，不留下可见痕迹。 */
    private var versionTapCount = 0
    private var lastVersionTapAt = 0L
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

    /**
     * 检查更新：版本来源是 git 仓库的版本 tag，不经过自建更新服务；失败只改状态文案，
     * 不打断聊天与后台连接。
     */
    fun checkForUpdate() {
        if (state.value.update is UpdateState.Checking) return
        mutable.update { it.copy(update = UpdateState.Checking) }
        scope.launch {
            val result = runCatching { updateChannel.check() }
            mutable.update { current ->
                current.copy(update = result.fold(
                    onSuccess = { release ->
                        when {
                            release == null -> UpdateState.Failed("仓库里还没有版本 tag")
                            isUpdateAvailable(BuildConfig.VERSION_NAME, release.version) -> UpdateState.Available(release)
                            else -> UpdateState.Current(release.tag)
                        }
                    },
                    onFailure = { UpdateState.Failed(it.message ?: "检查更新失败") },
                ))
            }
        }
    }

    /** 下载更新包：先试 tag 的 Release 附件，再回退到 tag 内提交的产物；进度写回状态。 */
    fun downloadUpdate() {
        val release = (state.value.update as? UpdateState.Available)?.release ?: return
        mutable.update { it.copy(update = UpdateState.Downloading(release, 0L, 0L)) }
        scope.launch {
            val result = runCatching {
                updateChannel.download(release) { received, total ->
                    mutable.update { it.copy(update = UpdateState.Downloading(release, received, total)) }
                }
            }
            mutable.update { current ->
                current.copy(update = result.fold(
                    onSuccess = { file -> UpdateState.Ready(release, file) },
                    onFailure = { UpdateState.Failed(it.message ?: "更新包下载失败") },
                ))
            }
        }
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
    private var batchJob: Job? = null
    private suspend fun stageOne(uri: Uri, roomId: String, original: Boolean, position: Int, total: Int) {
        val id = UUID.randomUUID().toString()
        val file = local.pendingFile(id)
        var persisted = false
        mutable.update { it.copy(uploadProgress = TransferProgress(id, roomId, if (total > 1) "附件 $position/$total" else "附件", phase = "准备附件")) }
        try {
            val (name, mime) = AttachmentFiles.stage(getApplication(), uri, file, original) { bytes, expected ->
                mutable.update { it.copy(uploadProgress = it.uploadProgress?.takeIf { p -> p.id == id }?.copy(received = bytes, total = expected)) }
            }
            val message = Message(id, roomId, state.value.selfId, state.value.name, "", Instant.now().toString(), Delivery.Queued, name, mime, file.length(), true)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { local.save(message).await(); putMessage(message); persisted = true }
            if (state.value.connected) enqueue(message)
        } catch (e: CancellationException) { if (!persisted) file.delete(); throw e }
        catch (e: Exception) { if (!persisted) file.delete(); showError(e.message ?: "无法准备附件") }
    }
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
                if (!clearingCache) {
                    val serverFileIds = s.files.map { it.id }.toSet()
                    cache.keys.toList().forEach { roomId ->
                        // Snapshot's historyFiles is authoritative: a server-side recall (or prune)
                        // removes the record, so any local Delivery.Sent file missing from it is gone.
                        cache[roomId] = mergeMessages(cache[roomId].orEmpty(), s.files.filter { it.roomId == roomId }, recalled)
                            .filterNot { it.fileName != null && it.delivery == Delivery.Sent && it.roomId in s.rooms.map { r -> r.id } && it.id !in serverFileIds }
                        cache[roomId].orEmpty().filter { it.fileName != null && it.delivery == Delivery.Sent && it.roomId in s.rooms.map { r -> r.id } && it.id !in serverFileIds }
                            .forEach { local.remove(it.id, true); recalled.add(it.id) }
                    }
                    s.files.groupBy { it.roomId }.forEach { (id, files) -> cache[id] = mergeMessages(cache[id].orEmpty(), files, recalled) }
                }
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
                // 每次握手成功都顺手刷新一次助手品牌（名称/唤醒词/头像）；失败静默。
                loadAiBranding()
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
        if (state.value.connected && id in liveRoomIds && room?.joined(state.value.selfId) == true) { refresh(id); scope.launch { syncRecalled(id) } }
    }
    fun back() { voice.stop(); refreshJob?.cancel(); mutable.update { it.copy(activeRoomId = null, loading = false) } }
    /** Copies the message text (or file name) to the clipboard from the glass long-press menu. */
    fun copyMessage(message: Message) {
        // 复制给的是气泡里看到的纯文字，而不是富文本 / Markdown 源码。
        val text = message.fileName ?: plainChatText(message.text)
        if (text.isBlank()) return
        val clipboard = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("点点直连", text))
        mutable.update { it.copy(notice = "已复制") }
    }
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
        // 只有 `@名称` / 唤醒词命中才转给 AI：服务端会把回复写进同一房间历史，
        // 与网页端完全一致，其它成员和后续刷新都能看到这条消息。
        val aiPrompt = parseAiMention(text, state.value.ai)
        scope.launch {
            try {
                local.save(message).await()
                putMessage(message)
                // Only clear the submitted draft, never a newer one typed during persistence.
                if (state.value.activeRoomId == room.id && state.value.draft.trim() == text) draft("")
                if (state.value.connected) enqueue(message)
                when {
                    aiPrompt == null -> Unit
                    aiPrompt.isBlank() -> showNotice("在 @" + state.value.ai.name + " 后面写上问题，或发送 /help 查看可用指令")
                    awaitDelivered(message) -> askAi(room.id, aiPrompt)
                    else -> showError("消息还没发出去，AI 提问已取消；点按消息重试后再发一次")
                }
            } catch (e: Exception) { showError("无法保存待发送消息：" + e.message) }
            finally { submittingRooms.remove(room.id) }
        }
    }

    /** 等本机消息真正落到服务端再提问，否则模型读不到用户刚发的问题。 */
    private suspend fun awaitDelivered(message: Message, timeoutMs: Long = 25_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (cache[message.roomId].orEmpty().firstOrNull { it.id == message.id }?.delivery) {
                Delivery.Sent -> return true
                Delivery.Failed -> return false
                else -> delay(120)
            }
        }
        return false
    }

    /** 向 AI 提问；回复由服务端写入房间历史，这里只负责刷新与「思考中」状态。 */
    private suspend fun askAi(roomId: String, prompt: String) {
        mutable.update { it.copy(aiThinkingRoom = roomId) }
        try {
            val token = withTimeout(20_000) { auth.first { it.isNotEmpty() } }
            aiClient.chat(token, roomId, prompt, state.value.name)
            refresh(roomId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e.message ?: "AI 请求失败，请稍后重试")
        } finally {
            mutable.update { it.copy(aiThinkingRoom = null) }
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
                                    val result = json(url, "POST", body, mapOf("x-file-name" to URLEncoder.encode(item.fileName, "UTF-8").replace("+", "%20"), "x-file-created-at" to item.createdAt), longRunning = true)
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
                syncRecalled(id)
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
    private suspend fun syncRecalled(roomId: String) {
        if (!state.value.connected || roomId !in liveRoomIds) return
        try {
            val response = json(address.api("api/history/recalled").newBuilder().addQueryParameter("roomId", roomId).build())
            val recalledList = response.optJSONArray("recalled")
            if (recalledList == null) return
            for (index in 0 until recalledList.length()) {
                val id = recalledList.optJSONObject(index)?.optString("historyId") ?: continue
                if (id.isBlank()) continue
                recalled.add(id)
                local.remove(id, true)
                if (cache[roomId].orEmpty().any { it.id == id }) {
                    cache[roomId] = cache[roomId].orEmpty().filterNot { it.id == id }
                }
            }
            publishMessages(roomId)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }

    fun savedUriFor(message: Message): Uri? {
        if (message.fileName == null) return null
        return prefs.getString(savedKey(message), null)?.let(Uri::parse)
    }

    fun markSaved(message: Message, uri: Uri) {
        if (message.fileName == null) return
        prefs.edit().putString(savedKey(message), uri.toString()).apply()
        // 这次接收如果是用户点开触发的，落盘后立刻交给系统默认应用打开。
        if (pendingOpenId == message.id) signalOpen(message, uri)
    }

    private fun savedKey(message: Message) = "saved:" + address.http + ":" + message.id

    fun saveAttachment(message: Message) {
        if (message.fileName == null) return
        val existing = savedUriFor(message)
        if (existing != null) {
            // 已经在本机（含上一次接收）：点开时不再重复下载。
            if (pendingOpenId == message.id) signalOpen(message, existing)
            return
        }
        // 保留打开意图：重复点同一条正在接收的附件时，接收完成后仍然会打开它。
        if (state.value.downloadProgress != null) { showError("请先完成或取消当前接收"); return }
        scope.launch(Dispatchers.IO) {
            try {
                val destination = AttachmentFiles.defaultDownloadDestination(getApplication(), message.fileName!!)
                withContext(Dispatchers.Main.immediate) { download(message, destination) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pendingOpenId = null; showError(e.message ?: "无法保存附件") }
        }
    }

    /** 用户点开还没下载的附件：先记下意图，接收完成后由 [markSaved] 交给系统默认应用打开。 */
    fun expectOpenAfterDownload(message: Message) { pendingOpenId = message.id }

    /** 界面已经处理完这次打开请求。 */
    fun consumeOpenRequest() { if (state.value.openRequest != null) mutable.update { it.copy(openRequest = null) } }

    /** 附件已在本机：把打开请求交给界面，由界面拉起系统默认应用。 */
    private fun signalOpen(message: Message, uri: Uri) {
        pendingOpenId = null
        mutable.update { it.copy(openRequest = AttachmentOpen(uri, message.mimeType)) }
    }

    /** Stages and queues one or more picked attachments. A batch is sent in order, one at a time. */
    fun uploadAll(uris: List<Uri>, roomId: String, original: Boolean = true) {
        val room = state.value.rooms.firstOrNull { it.id == roomId } ?: return
        if (uris.isEmpty() || batchJob?.isActive == true) return
        if (state.value.transferring || roomId in state.value.archivedRoomIds || !room.joined(state.value.selfId)) return
        val accepted = uris.take(MAX_BATCH_ATTACHMENTS)
        if (uris.size > accepted.size) showError("一次最多选择 $MAX_BATCH_ATTACHMENTS 个附件，已发送前 ${accepted.size} 个")
        batchJob = scope.launch {
            mutable.update { it.copy(transferring = true) }
            try {
                accepted.forEachIndexed { index, uri -> stageOne(uri, roomId, original, index + 1, accepted.size) }
            } finally {
                mutable.update { it.copy(transferring = false, uploadProgress = null) }
            }
        }
    }

    /** Single-attachment entry point kept for callers that only have one uri. */
    fun upload(uri: Uri, roomId: String, original: Boolean = true) = uploadAll(listOf(uri), roomId, original)
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
        // Cancelling a staged batch stops the remaining files; finished ones keep their own retry entry.
        if (p.phase == "准备附件") batchJob?.cancel()
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
                authorized(address.api("api/history/download/" + message.id), longRunning = true).useCancellable { response ->
                    checkResponse(response)
                    response.body!!.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(65536); var total = 0L
                        while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX_ATTACHMENT_BYTES) { "附件超过大小限制（$MAX_ATTACHMENT_LABEL）" }; output.write(buffer, 0, n) }
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

    fun downloadChanged(progress: TransferProgress?) {
        // 接收结束还没被消费，说明这次下载失败或被取消，丢掉打开意图，避免以后误开。
        if (progress == null) pendingOpenId = null
        mutable.update { it.copy(downloadProgress = progress) }
    }
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
            authorized(address.api("api/history/download/" + message.id), longRunning = true).use { response ->
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

    private suspend fun json(url: HttpUrl, method: String = "GET", body: RequestBody? = null, headers: Map<String, String> = emptyMap(), longRunning: Boolean = false): JSONObject =
        authorized(url, method, body, headers, longRunning).use { response ->
            withContext(Dispatchers.IO) { checkResponse(response); JSONObject(response.body?.string().orEmpty().ifBlank { "{}" }) }
        }

    private suspend fun authorized(url: HttpUrl, method: String = "GET", body: RequestBody? = null, headers: Map<String, String> = emptyMap(), longRunning: Boolean = false): Response {
        val token = withTimeout(20_000) { auth.first { it.isNotEmpty() } }
        suspend fun call(token: String): Response {
            val request = Request.Builder().url(url).header("Authorization", "Bearer " + token).method(method, body)
            headers.forEach { (k, v) -> request.header(k, v) }
            return (if (longRunning) transferClient else client).newCall(request.build()).await()
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

    /** 聊天界面用的助手头像；还没下载完时返回 null，界面回退文字头像。 */
    fun aiAvatar(): Bitmap? = aiAvatars.cached(state.value.ai.avatarUrl)

    /** 拉取后台配置的助手名称/唤醒词/头像；失败保留既有状态，不打断聊天。 */
    fun loadAiBranding(force: Boolean = false) {
        if (aiBrandingLoaded && !force) return
        scope.launch {
            val branding = runCatching { aiClient.branding() }.getOrNull() ?: return@launch
            applyBranding(branding)
        }
    }

    private suspend fun applyBranding(branding: AiBranding) {
        aiBrandingLoaded = true
        mutable.update { it.copy(ai = branding) }
        if (branding.avatarUrl != null) {
            aiAvatars.load(branding.avatarUrl)
            mutable.update { it.copy(aiAvatarRevision = it.aiAvatarRevision + 1) }
        }
    }

    /** 长按助手头像要用的名称：没拉到过配置就先等一次，超时或失败回退当前值。 */
    suspend fun aiMentionName(): String {
        if (!aiBrandingLoaded) {
            val branding = withTimeoutOrNull(4_000) { runCatching { aiClient.branding() }.getOrNull() }
            if (branding != null) applyBranding(branding)
        }
        return state.value.ai.name.ifBlank { DEFAULT_AI_NAME }
    }

    /**
     * 长按助手头像：把 `@名称 ` 补到当前房间草稿末尾，返回插入用的名称。
     * 不在房间里时只返回名称，不改草稿。
     */
    suspend fun mentionAi(): String {
        val name = aiMentionName()
        if (state.value.activeRoomId != null) draft(appendAiMention(state.value.draft, name))
        return name
    }

    /** 设置页「版本」行连点 7 次进入管理员验证；间隔超过 3 秒重新计数。 */
    fun adminVersionTap() {
        val now = System.currentTimeMillis()
        if (now - lastVersionTapAt > 3_000) versionTapCount = 0
        lastVersionTapAt = now
        versionTapCount += 1
        if (versionTapCount < 7) return

        versionTapCount = 0
        if (state.value.admin is AdminMode.Open) return
        mutable.update { it.copy(admin = AdminMode.Gate, adminError = null, adminNotice = null) }
    }

    /** 读取本机保存的管理员账号；密码经 Android Keystore 加密，Keystore 不可用时返回 null。 */
    fun savedAdminCredentials(): Pair<String, String>? = adminCredentials.load()

    fun hasSavedAdminCredentials(): Boolean = adminCredentials.hasSaved()

    fun adminForgetCredentials() {
        adminCredentials.clear()
        showNotice("已清除本机保存的管理员账号")
    }
    fun adminCancelGate() {
        if (state.value.adminBusy) return
        mutable.update { it.copy(admin = AdminMode.Closed, adminError = null) }
    }

    /** 用主站管理员账号验证；成功后面板直接复用后台返回的同一份配置。 */
    fun adminLogin(email: String, password: String, remember: Boolean = true) {
        if (state.value.adminBusy) return
        if (email.isBlank() || password.isEmpty()) {
            mutable.update { it.copy(adminError = "请填写管理员邮箱与密码") }
            return
        }

        mutable.update { it.copy(adminBusy = true, adminError = null) }
        scope.launch {
            runCatching { aiClient.adminLogin(email, password) }
                .onSuccess { payload ->
                    // 记住账号时才写 Keystore 加密副本；取消勾选即刻清掉旧记录。
                    if (remember) adminCredentials.save(email, password) else adminCredentials.clear()
                    mutable.update {
                        it.copy(admin = AdminMode.Open(parseAdminDashboard(payload)), adminBusy = false, adminError = null)
                    }
                    loadAiBranding(force = true)
                    loadAdminHistory(reset = true)
                }
                .onFailure { error ->
                    mutable.update { it.copy(adminBusy = false, adminError = error.message ?: "管理员登录失败") }
                }
        }
    }

    fun adminLogout() {
        scope.launch {
            runCatching { aiClient.adminLogout() }
            aiBrandingLoaded = false
            mutable.update {
                it.copy(
                    admin = AdminMode.Closed, adminBusy = false, adminError = null, adminNotice = null,
                    adminHistoryFiles = emptyList(), adminHistoryCursor = null, adminHistoryLoading = false,
                )
            }
        }
    }

    fun adminClose() {
        mutable.update { it.copy(admin = AdminMode.Closed, adminBusy = false, adminError = null, adminNotice = null) }
    }

    fun adminRefresh() {
        if (state.value.adminBusy) return
        mutable.update { it.copy(adminBusy = true, adminError = null) }
        scope.launch {
            runCatching { aiClient.adminState() }
                .onSuccess { payload ->
                    mutable.update { it.copy(admin = AdminMode.Open(parseAdminDashboard(payload)), adminBusy = false) }
                    loadAdminHistory(reset = true)
                }
                .onFailure { error ->
                    mutable.update { it.copy(adminBusy = false, adminError = error.message ?: "后台状态刷新失败") }
                }
        }
    }

    /** 保存 AI 配置切片（policy / providers / models），返回的 dashboard 立刻回写界面。 */
    fun adminSaveAiConfig(payload: JSONObject, doneMessage: String) {
        if (state.value.adminBusy) return
        mutable.update { it.copy(adminBusy = true, adminError = null) }
        scope.launch {
            runCatching { aiClient.saveAiConfig(payload) }
                .onSuccess { saved ->
                    mutable.update {
                        it.copy(admin = AdminMode.Open(parseAdminDashboard(saved)), adminBusy = false, adminNotice = doneMessage)
                    }
                    loadAiBranding(force = true)
                }
                .onFailure { error ->
                    mutable.update { it.copy(adminBusy = false, adminError = error.message ?: "AI 配置保存失败") }
                }
        }
    }

    fun adminUploadAvatar(uri: Uri) {
        if (state.value.adminBusy) return
        mutable.update { it.copy(adminBusy = true, adminError = null) }
        scope.launch {
            val read = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: throw IOException("无法读取所选图片")
                    if (bytes.size > 8 * 1024 * 1024) throw IOException("头像不能超过 8 MB")
                    val mimeType = when {
                        bytes.size >= 4 && bytes[0] == 0x89.toByte() -> "image/png"
                        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
                        else -> "image/webp"
                    }
                    mimeType to bytes
                }
            }

            read.onSuccess { (mimeType, bytes) ->
                runCatching { aiClient.uploadAvatar(bytes, mimeType) }
                    .onSuccess { saved ->
                        mutable.update {
                            it.copy(admin = AdminMode.Open(parseAdminDashboard(saved)), adminBusy = false, adminNotice = "头像已更新")
                        }
                        loadAiBranding(force = true)
                    }
                    .onFailure { error ->
                        mutable.update { it.copy(adminBusy = false, adminError = error.message ?: "头像上传失败") }
                    }
            }.onFailure { error ->
                mutable.update { it.copy(adminBusy = false, adminError = error.message ?: "头像读取失败") }
            }
        }
    }

    fun adminDeleteAvatar() {
        if (state.value.adminBusy) return
        mutable.update { it.copy(adminBusy = true, adminError = null) }
        scope.launch {
            runCatching { aiClient.deleteAvatar() }
                .onSuccess { saved ->
                    mutable.update {
                        it.copy(admin = AdminMode.Open(parseAdminDashboard(saved)), adminBusy = false, adminNotice = "头像已移除")
                    }
                    loadAiBranding(force = true)
                }
                .onFailure { error ->
                    mutable.update { it.copy(adminBusy = false, adminError = error.message ?: "头像移除失败") }
                }
        }
    }

    fun adminDeleteHistory(file: AdminHistoryFile) {
        if (state.value.adminBusy) return
        mutable.update { it.copy(adminBusy = true, adminError = null) }
        scope.launch {
            runCatching { aiClient.deleteHistory("file", file.historyId) }
                .onSuccess { saved ->
                    mutable.update { current ->
                        current.copy(
                            admin = AdminMode.Open(parseAdminDashboard(saved)),
                            adminHistoryFiles = current.adminHistoryFiles.filterNot { it.historyId == file.historyId },
                            adminBusy = false,
                            adminNotice = "已删除 " + file.fileName,
                        )
                    }
                }
                .onFailure { error ->
                    mutable.update { it.copy(adminBusy = false, adminError = error.message ?: "历史记录删除失败") }
                }
        }
    }

    fun adminClearHistory() {
        if (state.value.adminBusy) return
        mutable.update { it.copy(adminBusy = true, adminError = null) }
        scope.launch {
            runCatching { aiClient.clearHistory() }
                .onSuccess { saved ->
                    mutable.update {
                        it.copy(
                            admin = AdminMode.Open(parseAdminDashboard(saved)),
                            adminHistoryFiles = emptyList(),
                            adminHistoryCursor = null,
                            adminBusy = false,
                            adminNotice = "全站历史已清空",
                        )
                    }
                }
                .onFailure { error ->
                    mutable.update { it.copy(adminBusy = false, adminError = error.message ?: "清空历史失败") }
                }
        }
    }

    fun loadAdminHistory(reset: Boolean = false) {
        if (state.value.adminHistoryLoading) return
        if (!reset && state.value.adminHistoryCursor == null) return
        val cursor = if (reset) null else state.value.adminHistoryCursor
        mutable.update { it.copy(adminHistoryLoading = true) }
        scope.launch {
            runCatching { aiClient.historyFiles(limit = 30, cursor = cursor) }
                .onSuccess { payload ->
                    val files = payload.optJSONArray("files")?.let { array ->
                        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::parseAdminHistoryFile) }
                    }.orEmpty()
                    val next = payload.optJSONObject("nextCursor")?.let {
                        AdminHistoryCursor(it.optString("createdAt"), it.optString("historyId"))
                    }
                    val history = payload.optJSONObject("history")
                    mutable.update { current ->
                        val open = current.admin as? AdminMode.Open
                        current.copy(
                            adminHistoryFiles = if (reset) files else current.adminHistoryFiles + files,
                            adminHistoryCursor = next,
                            adminHistoryLoading = false,
                            admin = if (open != null && history != null) {
                                AdminMode.Open(open.dashboard.copy(history = history))
                            } else {
                                current.admin
                            },
                        )
                    }
                }
                .onFailure { error ->
                    mutable.update { it.copy(adminHistoryLoading = false, adminError = error.message ?: "历史文件加载失败") }
                }
        }
    }

    fun adminConsumeNotice() {
        mutable.update { it.copy(adminError = null, adminNotice = null) }
    }

    fun settings(name: String, reduce: Boolean) {
        if (name.trim().isEmpty()) { showError("请填写设备名称"); return }
        // The connection configuration is intentionally not editable from the settings screen.
        prefs.edit().putString("name", name.trim()).putBoolean("reduceTransparency", reduce).apply()
        mutable.update { it.copy(name = name.trim(), reduceTransparency = reduce, notice = "设置已保存") }
        if (state.value.connected) command("update-settings", JSONObject().put("deviceName", name.trim())) else connect()
    }

    fun showNotice(message: String) { mutable.update { it.copy(notice = message) } }
    fun showError(message: String) { mutable.update { it.copy(error = message) } }
    fun clearNotice() { mutable.update { it.copy(error = null, notice = null) } }
    override fun onCleared() { voice.stop(); calls.dispose(); local.close(); stopped = true; socketVersion++; socket?.close(1000, "App closed"); scope.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); super.onCleared() }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ ->
                value.close()
                cancel()
            }
        }
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
