package com.ddzhilian.chat

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Byte counts, not synthetic progress. Unknown totals stay indeterminate. */
data class TransferProgress(val id: String, val roomId: String, val name: String, val received: Long = 0, val total: Long = -1, val phase: String = "正在接收") {
    val percent: Int? get() = if (total > 0) ((received.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null
    val detail get() = "${humanBytes(received)} / ${if (total > 0) humanBytes(total) else "未知大小"}"
}
fun humanBytes(n: Long): String = when { n >= 1024 * 1024 -> "%.1f MB".format(n / 1048576.0); n >= 1024 -> "%.1f KB".format(n / 1024.0); else -> "$n B" }

class AttachmentTransferService : Service() {
    private val app get() = application as ChatApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var current: TransferProgress? = null
    private var lastNotification = 0L
    private var lastUiProgress = 0L
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "附件接收进度", NotificationManager.IMPORTANCE_LOW).apply { description = "实时进度、取消、接收完成或失败" })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            if (intent.getStringExtra("transferId") == current?.id) job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || job?.isActive == true) { if (job == null) stopSelf(); return START_NOT_STICKY }
        val message = runCatching { localMessage(JSONObject(intent.getStringExtra("message")!!)) }.getOrNull()
        val destination = intent.getStringExtra("destination")?.let(Uri::parse)
        if (message == null || destination == null) { stopSelf(); return START_NOT_STICKY }
        val progress = TransferProgress(intent.getStringExtra("transferId") ?: UUID.randomUUID().toString(), message.roomId, message.fileName ?: "附件")
        current = progress
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(progress, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notification(progress, true))
        } catch (e: Exception) { app.chat.downloadChanged(null); app.chat.showError("无法启动附件接收：${e.message}"); stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            var terminal = "接收完成"
            app.getSharedPreferences("native_chat", 0).edit().putString("interruptedDownload", progress.name).apply()
            try {
                app.chat.receiveAttachment(message, destination) { bytes, total ->
                    val next = progress.copy(received = bytes, total = total)
                    val tick = SystemClock.elapsedRealtime()
                    if (tick - lastUiProgress >= 120 || bytes == total || bytes == 0L) withContext(Dispatchers.Main.immediate) {
                        lastUiProgress = tick; current = next; app.chat.downloadChanged(next)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotification >= 500) { lastNotification = now; if (app.notifications.enabled()) manager.notify(ID, notification(next, true)) }
                    }
                }
            } catch (e: CancellationException) { terminal = "已取消接收"; removePartial(destination) }
            catch (e: Exception) { terminal = "接收失败"; removePartial(destination); app.chat.showError(e.message ?: terminal) }
            finally {
                val finished = (current ?: progress).copy(phase = terminal)
                app.chat.downloadChanged(null)
                app.getSharedPreferences("native_chat", 0).edit().remove("interruptedDownload").apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (app.notifications.enabled()) manager.notify(ID, notification(finished, false))
                current = null; stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun removePartial(uri: Uri) {
        // Only the document the user just selected for this transfer, never another path.
        runCatching { if (DocumentsContract.isDocumentUri(this, uri)) DocumentsContract.deleteDocument(contentResolver, uri) else contentResolver.openOutputStream(uri, "wt")?.close() }
    }
    private fun notification(p: TransferProgress, active: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java)
            .setData(Uri.parse("ddchat://transfer/${p.id}" )).putExtra("roomId", p.roomId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(p.phase + " · " + p.name.take(48)).setContentText(if (active) "${p.percent?.let { "$it% · " }.orEmpty()}${p.detail}" else if (p.phase == "接收完成") "已保存到选择的位置" else "点击返回聊天")
            .setSubText("附件接收").setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active)
            .setAutoCancel(!active).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (active) {
            val cancel = PendingIntent.getService(this, 41, Intent(this, AttachmentTransferService::class.java).setAction("cancel")
                .setData(Uri.parse("ddchat://cancel-transfer/${p.id}" )).putExtra("transferId", p.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setProgress(100, p.percent ?: 0, p.percent == null).addAction(0, "取消接收", cancel)
            // Public Android API only. OEM decides promotion/Now Bar/island eligibility.
            if (Build.VERSION.SDK_INT >= 36) builder.setRequestPromotedOngoing(true).setShortCriticalText(p.percent?.let { "$it%" } ?: "接收中")
        }
        val ordinary = builder.build()
        if (Build.VERSION.SDK_INT >= 36 && active) {
            val style = Notification.ProgressStyle().setProgressIndeterminate(p.percent == null)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100))).setProgress(p.percent ?: 0)
            return Notification.Builder.recoverBuilder(this, ordinary).setStyle(style).build()
        }
        return ordinary
    }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val ID = 40
        private const val CHANNEL = "attachment-progress"
        fun start(context: Context, message: Message, destination: Uri, id: String) {
            ContextCompat.startForegroundService(context, Intent(context, AttachmentTransferService::class.java)
                .putExtra("message", message.toLocalJson().toString()).putExtra("destination", destination.toString()).putExtra("transferId", id))
        }
        fun cancel(context: Context, id: String) { context.startService(Intent(context, AttachmentTransferService::class.java).setAction("cancel").putExtra("transferId", id)) }
    }
}
