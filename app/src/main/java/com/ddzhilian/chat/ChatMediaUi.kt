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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlinx.coroutines.*

/** Decodes one attachment off the main thread and keeps the failure reason for a retry affordance. */
@Composable
private fun rememberAttachmentBitmap(message: Message, vm: ChatViewModel, maximum: Int, attempt: Int): Pair<Bitmap?, String?> {
    val s by vm.state.collectAsStateWithLifecycle()
    var failure by remember(message.id, attempt) { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null, message.id, message.localAttachment, s.mediaRevision, attempt, s.connected) {
        try {
            val file = vm.attachmentFile(message)
            val decoded = withContext(Dispatchers.IO) { AttachmentFiles.decode(file, maximum) }
            if (decoded == null) failure = "无法解码此图片" else value = decoded
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e.message ?: "图片加载失败" }
    }
    return bitmap to failure
}

/**
 * A run of images rendered as one stacked card (4:3 poker-style deck) that can be expanded into
 * an ordered, original-ratio vertical list. Stacked mode only swipes horizontally, never loops,
 * and the card that leaves the deck animates back to the bottom.
 */
@Composable
fun ImageStackCard(
    images: List<Message>, vm: ChatViewModel,
    onOpen: (Message) -> Unit, onLongPress: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = images.size
    if (count == 0) return
    val ids = remember(images) { images.map { it.id } }
    var expanded by remember(ids) { mutableStateOf(false) }

    if (expanded) {
        Column(modifier.widthIn(max = 288.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${images.size} 张图片", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { expanded = false }) { Text("收起") }
            }
            images.forEach { message -> key(message.id) { ExpandedImageRow(message, vm, onOpen, onLongPress) } }
        }
        return
    }

    StackedDeck(images, vm, onOpen, onLongPress, modifier) { expanded = true }
}

@Composable
private fun ExpandedImageRow(message: Message, vm: ChatViewModel, onOpen: (Message) -> Unit, onLongPress: (Message) -> Unit) {
    var attempt by remember(message.id) { mutableIntStateOf(0) }
    val (bitmap, failure) = rememberAttachmentBitmap(message, vm, maximum = 1200, attempt = attempt)
    val ratio = bitmap?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height }
        ?.takeIf { it.isFinite() && it > .2f && it < 5f } ?: (4f / 3f)
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .combinedClickable(onClick = { onOpen(message) }, onLongClick = { onLongPress(message) })) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), message.fileName ?: "图片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (failure != null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("图片未缓存", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { attempt++ }) { Text("重试") }
        } else CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun StackedDeck(
    images: List<Message>, vm: ChatViewModel,
    onOpen: (Message) -> Unit, onLongPress: (Message) -> Unit,
    modifier: Modifier = Modifier, onExpand: () -> Unit,
) {
    val count = images.size
    val ids = remember(images) { images.map { it.id } }
    var index by remember(ids) { mutableIntStateOf(0) }
    val top = images[index.coerceIn(0, count - 1)]
    var attempt by remember(top.id) { mutableIntStateOf(0) }
    val (bitmap, failure) = rememberAttachmentBitmap(top, vm, maximum = 960, attempt = attempt)
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var dragX by remember(top.id) { mutableFloatStateOf(0f) }
    val sink = remember(top.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val peek = 9.dp
    fun settle() {
        val start = dragX
        val threshold = (cardSize.width * .24f).coerceAtLeast(64f)
        scope.launch {
            if (count > 1 && abs(start) > threshold) {
                sink.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                // Left drag (start < 0) reveals the OLDER image (which peeks on the right);
                // right drag flips back to the NEWER one. Mirrors the deck layout.
                index = (index + (if (start < 0f) 1 else -1)).coerceIn(0, count - 1)
            } else {
                animate(0f, start, 0f, spring(dampingRatio = .72f, stiffness = 420f)) { value, _ -> dragX = value }
            }
            dragX = 0f
        }
    }

    Box(modifier.padding(top = peek * 2)) {
        Box(Modifier.widthIn(max = 288.dp).heightIn(max = 380.dp).aspectRatio(4f / 3f)
            .onGloballyPositioned { cardSize = it.size }) {
            // Peek cards on the left and/or right, poker-style. Each shows its own thumbnail content.
            val leftCount = if (index == 0) 0 else if (index == count - 1) 2 else 1
            val rightCount = if (index == count - 1) 0 else if (index == 0) 2 else 1
            for (depth in 1..leftCount) {
                val queued = images.getOrNull(index - depth) ?: continue
                key("left-$depth-" + queued.id) {
                    val (queuedBitmap, _) = rememberAttachmentBitmap(queued, vm, maximum = 480, attempt = 0)
                    Box(Modifier.matchParentSize().graphicsLayer {
                        transformOrigin = TransformOrigin(.5f, .5f)
                        translationX = -(peek * depth).toPx()
                        rotationZ = -depth * 3f
                        val scale = 1f - depth * .05f
                        scaleX = scale; scaleY = scale
                    }.zIndex(-depth.toFloat()).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (queuedBitmap != null) Image(queuedBitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            for (depth in 1..rightCount) {
                val queued = images.getOrNull(index + depth) ?: continue
                key("right-$depth-" + queued.id) {
                    val (queuedBitmap, _) = rememberAttachmentBitmap(queued, vm, maximum = 480, attempt = 0)
                    Box(Modifier.matchParentSize().graphicsLayer {
                        transformOrigin = TransformOrigin(.5f, .5f)
                        translationX = (peek * depth).toPx()
                        rotationZ = depth * 3f
                        val scale = 1f - depth * .05f
                        scaleX = scale; scaleY = scale
                    }.zIndex(-depth.toFloat()).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (queuedBitmap != null) Image(queuedBitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            // The held card. It slides horizontally and, on release past the threshold, tucks back
            // to the bottom of the deck before index advances.
            Box(Modifier.matchParentSize().zIndex(if (sink.value > 0f) -2f else 0f).graphicsLayer {
                val remaining = 1f - sink.value
                translationX = dragX * remaining
                rotationZ = if (cardSize.width > 0) dragX * remaining / cardSize.width * 8f else 0f
                val scale = 1f - .08f * sink.value
                scaleX = scale; scaleY = scale
            }.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (count > 1) Modifier.pointerInput(top.id, count) {
                    detectHorizontalDragGestures(
                        // A new drag while the tuck-back animation is still playing would be
                        // multiplied by `remaining` ≈ 0, i.e. the card would not follow the finger.
                        // Reset immediately so consecutive swipes stay responsive.
                        onDragStart = {
                            dragX = 0f
                            if (sink.value > 0f) scope.launch { sink.snapTo(0f) }
                        },
                        onDragEnd = { settle() },
                        onDragCancel = { dragX = 0f },
                    ) { change, amount -> change.consume(); dragX += amount }
                } else Modifier)
                .combinedClickable(onClick = { onOpen(top) }, onLongClick = { onLongPress(top) })) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), top.fileName ?: "图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (failure != null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("图片未缓存", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { attempt++ }) { Text("重试") }
                } else CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
            }
            if (count > 1) {
                Text("${index + 1} / $count", Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(Color.Black.copy(alpha = .45f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 11.sp)
                TextButton(onClick = onExpand, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                    Text("展开", fontSize = 12.sp, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
}

/** Composer preview for one or more picked images; the compress choice applies to the whole batch. */
@Composable
fun ImageSendPreview(uris: List<Uri>, onDismiss: () -> Unit, onSend: (Boolean) -> Unit) {
    if (uris.isEmpty()) { LaunchedEffect(Unit) { onDismiss() }; return }
    val context = LocalContext.current
    var original by remember(uris) { mutableStateOf(false) }
    val compressible = remember(uris) { uris.all { context.contentResolver.getType(it) in setOf("image/jpeg", "image/png", "image/webp") } }
    val pager = rememberPagerState(pageCount = { uris.size })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (uris.size == 1) "发送图片" else "发送 ${uris.size} 张图片") }, text = {
        Column {
            Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                HorizontalPager(pager, Modifier.fillMaxWidth()) { page -> ImagePreviewPage(uris[page], Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
                if (uris.size > 1) Text("${pager.currentPage + 1} / ${uris.size}", Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color.Black.copy(alpha = .5f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp), color = Color.White, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(original || !compressible, { original = it }, enabled = compressible); Text("原图") }
            Text(if (original || !compressible) "保持原文件；最大 $MAX_ATTACHMENT_LABEL" else "长边缩至 2048 像素，转为 JPEG（透明区域不保留）", fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { onSend(original || !compressible) }) { Text("发送") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ImagePreviewPage(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    var failure by remember(uri) { mutableStateOf(false) }
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
        }.getOrNull() }; failure = value == null
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "待发送图片", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
        else if (failure) Text("此格式暂不支持预览，可发送原文件", Modifier.padding(16.dp), fontSize = 12.sp)
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** Full-screen viewer: original ratio, pinch zoom, and a pager across the cached room images. */
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
                TextButton(onClick = { images.getOrNull(pager.currentPage)?.let(onSave) }) { Text(images.getOrNull(pager.currentPage)?.let { if (vm.savedUriFor(it) != null) "打开" else "保存" } ?: "保存", color = Color.White) }
            }
            Text("原始比例 · 双指缩放 · 双击还原 · 原比例时左右切换", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp), color = Color.White, fontSize = 12.sp)
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
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(if (cancelling) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
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
                    TextButton(onClick = { vm.openSearchResult(message); onDismiss() }) { Text(message.fileName ?: plainChatText(message.text), maxLines = 3) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
