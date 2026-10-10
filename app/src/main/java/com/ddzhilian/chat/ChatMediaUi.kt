@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.ddzhilian.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

@Composable
fun AttachmentThumbnail(message: Message, vm: ChatViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    var attempt by remember(message.id) { mutableIntStateOf(0) }
    var error by remember(message.id, attempt) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, message.id, message.localAttachment, s.mediaRevision, attempt, s.connected) {
        try { val file = vm.attachmentFile(message); value = withContext(Dispatchers.IO) { AttachmentFiles.decode(file, 600) }; error = value == null }
        catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
    }
    Box(modifier.sizeIn(minWidth = 170.dp, maxWidth = 260.dp).height(176.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), message.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (error) TextButton(onClick = { attempt++ }) { Text("图片未缓存 · 重试") }
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun ImageSendPreview(uri: Uri, onDismiss: () -> Unit, onSend: (Boolean) -> Unit) {
    val context = LocalContext.current
    var original by remember(uri) { mutableStateOf(false) }
    val mime = remember(uri) { context.contentResolver.getType(uri) }
    val compressible = mime in setOf("image/jpeg", "image/png", "image/webp")
    var error by remember(uri) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val factor = (1600.0 / maxOf(info.size.width, info.size.height)).coerceAtMost(1.0)
                    decoder.setTargetSize((info.size.width * factor).toInt().coerceAtLeast(1), (info.size.height * factor).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            var sample = 1; while (maxOf(options.outWidth, options.outHeight) / sample > 1600) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }
        }.getOrNull() }; error = value == null
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("发送图片") }, text = {
        Column {
            bitmap?.let { Image(it.asImageBitmap(), "待发送图片", Modifier.fillMaxWidth().heightIn(max = 340.dp), contentScale = ContentScale.Fit) }
            if (error) Text("此格式暂不支持预览，可发送原文件")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(original || !compressible, { original = it }, enabled = compressible); Text("原图") }
            Text(if (original || !compressible) "保持原文件；最大 20 MB" else "长边缩至 2048 像素，转为 JPEG（透明区域不保留）", fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { onSend(original || !compressible) }) { Text("发送") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun ImageGallery(images: List<Message>, initialId: String, vm: ChatViewModel, onSave: (Message) -> Unit, onDismiss: () -> Unit) {
    if (images.isEmpty()) { LaunchedEffect(Unit) { onDismiss() }; return }
    val pager = rememberPagerState(initialPage = images.indexOfFirst { it.id == initialId }.coerceAtLeast(0), pageCount = { images.size })
    var zoomed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                val message = images[page]
                var retry by remember(message.id) { mutableIntStateOf(0) }
                var error by remember(message.id, retry) { mutableStateOf<String?>(null) }
                val bitmap by produceState<Bitmap?>(null, message.id, retry) {
                    try { val file = vm.attachmentFile(message); value = withContext(Dispatchers.IO) { AttachmentFiles.decode(file, 2400) }; if (value == null) error = "无法解码此图片" }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "图片加载失败" }
                }
                var scale by remember(message.id) { mutableFloatStateOf(1f) }
                var offset by remember(message.id) { mutableStateOf(Offset.Zero) }
                LaunchedEffect(pager.currentPage) { if (page == pager.currentPage) { scale = 1f; offset = Offset.Zero; zoomed = false } }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap!!.asImageBitmap(), message.fileName, Modifier.fillMaxSize()
                        .pointerInput(message.id) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero; zoomed = scale > 1f }) }
                        .transformable(rememberTransformableState { zoom, pan, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f); zoomed = scale > 1.01f
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                            offset = Offset(offset.x.coerceIn(-1800f, 1800f), offset.y.coerceIn(-2400f, 2400f))
                        }, canPan = { scale > 1f })
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
                    else if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(error!!, color = Color.White); TextButton(onClick = { retry++ }) { Text("重试") } }
                    else CircularProgressIndicator()
                }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = .6f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭图片", tint = Color.White) }
                Text("${pager.currentPage + 1} / ${images.size}", Modifier.weight(1f), color = Color.White)
                TextButton(onClick = { images.getOrNull(pager.currentPage)?.let(onSave) }) { Text("保存", color = Color.White) }
            }
            Text("双指缩放 · 双击还原 · 原比例时左右切换", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp), color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
fun VoiceBubble(message: Message, vm: ChatViewModel, color: Color) {
    val state by vm.voice.state.collectAsStateWithLifecycle()
    val active = state.playingId == message.id
    Column(Modifier.widthIn(min = 180.dp, max = 240.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.voice.toggle(message) }) { Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (active) "停止语音" else "播放语音", tint = color) }
            Text(if (state.preparingId == message.id) "正在加载…" else if (active) "${state.position / 1000} / ${state.duration / 1000} 秒" else message.fileName?.substringAfter("voice_")?.substringBefore(".m4a")?.takeIf { message.fileName.startsWith("voice_") } ?: "语音消息", color = color, fontSize = 14.sp)
        }
        if (active) {
            Slider(value = state.position.toFloat().coerceIn(0f, state.duration.toFloat().coerceAtLeast(1f)), onValueChange = { vm.voice.seek(it.toInt()) }, valueRange = 0f..state.duration.toFloat().coerceAtLeast(1f))
            TextButton(onClick = { vm.voice.route(!state.speaker) }) { Text(if (state.speaker) "切换到听筒" else "切换到扬声器", color = color, fontSize = 12.sp) }
        }
    }
}

@Composable
fun VoiceRecordButton(roomId: String, vm: ChatViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by vm.voice.state.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var cancelling by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (!it) vm.showError("请允许麦克风权限后录制语音") }
    DisposableEffect(roomId) { onDispose { vm.voice.finishRecording(false) } }
    val label = if (!granted) "允许麦克风后录音" else if (state.recording) { if (cancelling) "松开取消" else "${state.seconds}s · 上滑取消" } else "按住说话"
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).background(if (cancelling) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
        .semantics { contentDescription = label; onClick(label = "开始或结束录音") {
            if (!granted) permission.launch(Manifest.permission.RECORD_AUDIO)
            else if (state.recording) vm.voice.finishRecording(true) else vm.voice.startRecording(roomId)
            true
        } }
        .pointerInput(roomId, granted) {
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                if (!granted) { permission.launch(Manifest.permission.RECORD_AUDIO); return@awaitEachGesture }
                val started = vm.voice.startRecording(roomId)
                if (!started) return@awaitEachGesture
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(); val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        cancelling = change.position.y < down.position.y - 72.dp.toPx(); change.consume()
                        if (!change.pressed) { released = true; break }
                    }
                } finally { vm.voice.finishRecording(released && !cancelling); cancelling = false }
            }
        }, contentAlignment = Alignment.Center) { Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
fun TransferStatus(s: ChatState, vm: ChatViewModel) {
    val download = s.downloadProgress; val progress = download ?: s.uploadProgress ?: return
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(progress.phase + (progress.percent?.let { " · $it%" } ?: ""), fontSize = 12.sp); Text(progress.detail, fontSize = 11.sp) }
                TextButton(onClick = { if (download != null) vm.cancelDownload() else vm.cancelUpload() }) { Text("取消") }
            }
            if (progress.percent != null) LinearProgressIndicator(progress = { progress.percent!! / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun CacheAndNotificationSettings(vm: ChatViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    TextButton(onClick = { confirm = true }) { Text("清理已下载历史与媒体缓存") }
    Text("保留待发送内容、草稿和会话偏好。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val systemName = remember { when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> "三星 / Now Bar"
        "xiaomi", "redmi" -> "小米 / 超级岛"
        "oppo", "oneplus", "realme" -> "OPPO 系 / 流体云"
        else -> "Android 实时活动"
    } }
    Text(systemName, Modifier.padding(top = 20.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("附件接收实时通知", Modifier.padding(top = 20.dp), fontSize = 14.sp)
    Text("Android 16 优先使用实时进度，旧系统显示常驻进度条。小米、三星、OPPO 的岛／Now Bar／流体云展示由系统决定。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "attachment-progress")) }) { Text("附件进度通知设置") }
    if (Build.VERSION.SDK_INT >= 36) TextButton(onClick = {
        runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
    }) { Text("系统实时活动权限") }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清理本地缓存？") }, text = { Text("会删除已下载历史、图片和语音。已发消息仍保留在服务器，待发送内容和草稿不受影响。离线时无法重新加载已清理内容。") }, confirmButton = { TextButton(onClick = { confirm = false; vm.clearCache() }) { Text("清理") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
fun MessageSearchDialog(s: ChatState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("搜索消息") }, text = {
        Column {
            OutlinedTextField(query, { query = it; vm.searchMessages(it) }, placeholder = { Text("文字或文件名") }, singleLine = true)
            Text("仅搜索此会话的本地已缓存记录", Modifier.padding(vertical = 12.dp), fontSize = 12.sp)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                val messages = s.searchResults.filter { it.roomId == s.activeRoomId }
                items(messages.size, key = { messages[it].id }) { i -> val message = messages[i]
                    TextButton(onClick = { vm.openSearchResult(message); onDismiss() }) { Text(message.fileName ?: message.text, maxLines = 3) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
