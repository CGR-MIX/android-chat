package com.ddzhilian.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NativeCallController(private val app: ChatApplication, private val chat: ChatViewModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<NativeCall?>(null)
    val state = mutable.asStateFlow()
    var engine: NativeCallEngine? = null
        private set
    private var sessions = emptyList<CallSession>()
    private var ice = JSONArray()
    private var timeout: Job? = null
    private var preparing: Job? = null
    private var heartbeat: Job? = null
    private var pending: Pair<Peer, Boolean>? = null
    private var pendingRoom: String? = null
    private val pendingSignals = mutableListOf<JSONObject>()
    fun snapshot(p: JSONObject) {
        sessions = parseCallSessions(p)
        ice = p.optJSONObject("rtcConfig")?.optJSONArray("iceServers") ?: JSONArray()
        val call = mutable.value
        if (call != null && sessions.none { it.id == call.session.id && it.peerId == call.session.peerId }) end("通话会话已结束")
        tryPending()
    }
    fun sessionCreated(p: JSONObject) {
        val session = CallSession(p.getString("sessionId"), p.getString("roomId"), p.getJSONObject("peer").getString("deviceId"))
        sessions = replaceCallSession(sessions, session)
        tryPending()
    }
    private fun hasPermissions(video: Boolean) =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            (!video || ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    fun start(peer: Peer, video: Boolean) {
        if (mutable.value != null || pending != null) { chat.showError("请先结束当前通话"); return }
        if (!chat.uiVisible || !hasPermissions(video)) { chat.showError("请先允许麦克风和摄像头权限"); return }
        if (!peer.online || !chat.state.value.connected) { chat.showError("对方当前不在线"); return }
        chat.voice.stop()
        pending = peer to video
        pendingRoom = chat.state.value.room?.takeIf { !it.isPublic && it.members.any { member -> member.id == peer.id } }?.id
        // Re-ensure on the server: cached sessions may have expired without a snapshot.
        val room = pendingRoom
        val sent = if (room != null) chat.command("join-room", JSONObject().put("roomId", room))
            else chat.command("request-connect", JSONObject().put("targetDeviceId", peer.id).put("reason", "manual"))
        if (!sent) { pending = null; pendingRoom = null; return }
        armTimeout(15_000, "建立通话超时，请重试")
    }
    private fun tryPending() {
        val (peer, video) = pending ?: return
        val session = sessions.firstOrNull { it.peerId == peer.id && it.roomId == pendingRoom }
            ?: sessions.firstOrNull { pendingRoom == null && it.peerId == peer.id } ?: return
        invite(session, peer, video)
    }
    private fun invite(session: CallSession, peer: Peer, video: Boolean) {
        pending = null; pendingRoom = null
        val call = NativeCall(UUID.randomUUID().toString(), session, peer.name, video, CallPhase.Outgoing)
        mutable.value = call
        // Acquire while the Activity is visible; Android 14+ forbids background camera/mic FGS starts.
        prepare(call) {
            if (chat.command("call-invite", call.control())) armTimeout(45_000, "对方暂时无人接听") else end()
        }
    }
    fun receive(type: String, p: JSONObject) {
        if (type == "call-invite") {
            val session = sessions.firstOrNull { it.id == p.optString("sessionId") && it.peerId == p.optString("fromDeviceId") } ?: return
            if (p.optString("callId").isBlank() || (p.has("roomId") && p.optString("roomId") != session.roomId)) return
            val peerName = chat.state.value.peers.firstOrNull { it.id == session.peerId }?.name ?: "设备"
            val incoming = NativeCall(p.getString("callId"), session, peerName, p.optString("kind") == "video", CallPhase.Incoming)
            if (mutable.value?.id == incoming.id) return
            if (mutable.value != null || pending != null) { chat.command("call-reject", incoming.control()); return }
            chat.voice.stop()
            mutable.value = incoming
            if (!chat.uiVisible && chat.state.value.backgroundNotifications) app.notifications.incoming(incoming)
            armTimeout(45_000, "未接来电")
            return
        }
        val call = mutable.value ?: return
        if (type == "signal") {
            if (p.optString("purpose") != "media" || !call.matchesSignal(p)) return
            val signal = p.optJSONObject("signal") ?: return
            if (call.phase == CallPhase.Incoming || preparing?.isActive == true) {
                if (pendingSignals.size < 256) pendingSignals.add(signal)
            } else engine?.signal(signal)
            return
        }
        if (!call.matches(p)) return
        when (type) {
            "call-accept" -> if (call.phase == CallPhase.Outgoing) {
                mutable.value = call.copy(phase = CallPhase.Connecting)
                armTimeout(30_000, "通话连接超时，请重拨")
                engine?.offer()
            }
            "call-reject" -> end("对方未接听或正忙", notifyPeer = false)
            "call-hangup" -> end("通话已结束", notifyPeer = false)
        }
    }
    fun accept() {
        val call = mutable.value?.takeIf { it.phase == CallPhase.Incoming } ?: return
        if (!chat.uiVisible || !hasPermissions(call.video)) { chat.showError("请在应用内允许通话权限后接听"); return }
        mutable.value = call.copy(phase = CallPhase.Connecting)
        app.notifications.clearCall()
        prepare(call) {
            if (!chat.command("call-accept", call.control())) { end(); return@prepare }
            armTimeout(30_000, "通话连接超时，请重拨")
            pendingSignals.toList().forEach { engine?.signal(it) }; pendingSignals.clear()
        }
    }
    private fun prepare(call: NativeCall, ready: () -> Unit) {
        timeout?.cancel()
        preparing = scope.launch {
            try {
                check(withTimeout(10_000) { ChatConnectionService.startMedia(app, call.video, call.id) }) { "无法启动后台通话服务" }
                if (mutable.value?.id != call.id) return@launch
                val rtc = NativeCallEngine(app, app.rtc, send = { signal ->
                    if (mutable.value?.id == call.id) chat.command("signal", JSONObject().put("sessionId", call.session.id)
                        .put("targetDeviceId", call.session.peerId).put("purpose", "media").put("signal", signal))
                }, connected = {
                    if (mutable.value?.id == call.id) {
                        timeout?.cancel()
                        mutable.update { it?.copy(phase = CallPhase.Active, connectedAt = it.connectedAt ?: SystemClock.elapsedRealtime()) }
                        if (heartbeat?.isActive != true) heartbeat = scope.launch {
                            while (mutable.value?.id == call.id) {
                                chat.command("session-state", JSONObject().put("sessionId", call.session.id)
                                    .put("targetDeviceId", call.session.peerId).put("state", "connected"))
                                delay(25_000)
                            }
                        }
                    }
                }, remoteVideo = { mutable.update { it?.copy(remoteVideo = true) } }, failed = { end(it) })
                engine = rtc
                rtc.start(call.video, ice)
                ready()
            } catch (e: TimeoutCancellationException) { end("启动通话服务超时，请重试") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { end(e.message ?: "无法打开麦克风或摄像头") }
        }
    }
    private fun armTimeout(ms: Long, message: String) {
        timeout?.cancel(); timeout = scope.launch { delay(ms); end(message) }
    }
    fun end(message: String? = null, notifyPeer: Boolean = true) {
        val call = mutable.value
        if (notifyPeer && call != null && chat.state.value.connected) chat.command(if (call.phase == CallPhase.Incoming) "call-reject" else "call-hangup", call.control())
        preparing?.cancel(); preparing = null; timeout?.cancel(); timeout = null; heartbeat?.cancel(); heartbeat = null
        engine?.close(); engine = null; pending = null; pendingRoom = null; pendingSignals.clear(); mutable.value = null
        app.notifications.clearCall(); ChatConnectionService.finishMedia(app)
        if (message != null) chat.showError(message)
    }
    fun serverError(p: JSONObject) {
        if ((mutable.value != null || pending != null) && p.optString("code") in setOf("SESSION_NOT_FOUND", "SESSION_FORBIDDEN", "DEVICE_NOT_FOUND", "ROOM_NOT_FOUND")) {
            end("通话会话已失效或对方离线，请重新拨打", notifyPeer = false)
            chat.command("request-snapshot")
        }
    }
    fun mute() { mutable.value?.let { engine?.mute(!it.muted); mutable.value = it.copy(muted = !it.muted) } }
    fun camera() { mutable.value?.takeIf { it.video }?.let { runCatching { engine?.camera(!it.cameraOff); mutable.value = it.copy(cameraOff = !it.cameraOff) }.onFailure { error -> end(error.message ?: "无法切换摄像头状态") } } }
    fun speaker() { mutable.value?.let { engine?.speaker(!it.speaker); mutable.value = it.copy(speaker = !it.speaker) } }
    fun switchCamera() { engine?.switchCamera() }
    fun dispose() { end(notifyPeer = false); scope.cancel() }
}
