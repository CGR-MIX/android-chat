package com.ddzhilian.chat

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class ChatNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel("connection", "后台连接", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("messages", "聊天消息", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("calls", "来电", NotificationManager.IMPORTANCE_HIGH))
    }
    private fun open(room: String? = null, request: Int = 0) = PendingIntent.getActivity(context, request,
        Intent(context, MainActivity::class.java).setData(android.net.Uri.parse("ddchat://notification/" + (room ?: "home"))).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("roomId", room),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun base(channel: String) = NotificationCompat.Builder(context, channel).setSmallIcon(com.ddzhilian.chat.R.drawable.ic_notification)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open())
    fun ongoing(call: NativeCall?, connected: Boolean): Notification {
        val builder = base("connection").setContentTitle(if (call == null) "点点直连 · 后台连接" else if (call.phase == CallPhase.Incoming) "点点直连 · 来电" else "点点直连 · 通话中")
            .setContentText(if (call != null) "点击返回通话" else if (connected) "在线接收消息和来电" else "正在重新连接")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (call != null) {
            val end = PendingIntent.getService(context, 2, Intent(context, ChatConnectionService::class.java)
                .setAction("end").putExtra("callId", call.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "挂断", end)
        }
        return builder.build()
    }
    fun enabled() = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    private fun post(tag: String?, id: Int, notification: Notification) {
        if (enabled()) manager.notify(tag, id, notification)
    }
    fun message(room: Room) {
        post(room.id, 20, base("messages").setContentTitle("点点直连").setContentText("你有新的聊天消息")
            .setContentIntent(open(room.id, room.id.hashCode())).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE).build())
    }
    fun incoming(call: NativeCall) {
        val reject = PendingIntent.getService(context, 3, Intent(context, ChatConnectionService::class.java)
            .setAction("end").putExtra("callId", call.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(null, 21, base("calls").setContentTitle(if (call.video) "视频来电" else "语音来电")
            .setContentText("${call.peerName} · 点击打开并接听").setContentIntent(open(request = 1))
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH).setOngoing(true)
            .setTimeoutAfter(45_000).addAction(0, "拒绝", reject).addAction(0, "打开接听", open(request = 1)).build())
    }
    fun clearCall() = manager.cancel(21)
    fun clearRoom(room: String) = manager.cancel(room, 20)
}

class ChatConnectionService : Service() {
    private val app get() = application as ChatApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var media = false
    private var video = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); running = this
        scope.launch { app.chat.calls.state.collect { refresh() } }
        scope.launch { app.chat.state.map { it.connected }.distinctUntilChanged().collect { refresh() } }
    }
    private fun refresh() {
        val call = app.chat.calls.state.value
        val notification = app.notifications.ongoing(call, app.chat.state.value.connected)
        var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        if (media && Build.VERSION.SDK_INT >= 30) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(10, notification, types) else startForeground(10, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.action == "media") {
                val callId = intent.getStringExtra("callId")
                val request = mediaReady?.takeIf { it.first == callId }
                if (request == null || !request.second.isActive || app.chat.calls.state.value?.id != callId) {
                    refresh()
                    if (app.chat.calls.state.value == null && !app.chat.state.value.backgroundNotifications) stopSelf()
                    return START_NOT_STICKY
                }
                media = true; video = intent.getBooleanExtra("video", false)
            }
            refresh()
            if (intent?.action == "media") { mediaReady?.second?.complete(true); mediaReady = null }
            if (intent?.action == "end" && intent.getStringExtra("callId") == app.chat.calls.state.value?.id) app.chat.calls.end()
            if (intent == null && !app.chat.state.value.backgroundNotifications) stopSelf()
        } catch (e: Exception) {
            mediaReady?.second?.complete(false); mediaReady = null
            app.chat.showError("后台服务启动失败：${e.message}"); stopSelf()
        }
        return if (app.chat.state.value.backgroundNotifications) START_STICKY else START_NOT_STICKY
    }
    override fun onDestroy() {
        running = null; scope.cancel(); mediaReady?.second?.complete(false); mediaReady = null
        if (app.chat.calls.state.value != null) app.chat.calls.end("后台通话服务已停止")
        super.onDestroy()
    }
    companion object {
        private var running: ChatConnectionService? = null
        private var mediaReady: Pair<String, CompletableDeferred<Boolean>>? = null
        fun keepConnected(app: ChatApplication, enabled: Boolean) {
            if (enabled) runCatching { ContextCompat.startForegroundService(app, Intent(app, ChatConnectionService::class.java)) }
                .onFailure { app.chat.showError("无法启动后台连接，请重新打开应用后重试") }
            else if (app.chat.calls.state.value == null) app.stopService(Intent(app, ChatConnectionService::class.java))
        }
        suspend fun startMedia(app: ChatApplication, video: Boolean, callId: String): Boolean {
            val ready = CompletableDeferred<Boolean>(); mediaReady = callId to ready
            try {
                ContextCompat.startForegroundService(app, Intent(app, ChatConnectionService::class.java).setAction("media").putExtra("video", video).putExtra("callId", callId))
                return ready.await()
            } finally { if (mediaReady?.second === ready) mediaReady = null }
        }
        fun finishMedia(app: ChatApplication) {
            running?.let { it.media = false; it.video = false
                if (app.chat.state.value.backgroundNotifications) it.refresh() else it.stopSelf()
            }
        }
    }
}
