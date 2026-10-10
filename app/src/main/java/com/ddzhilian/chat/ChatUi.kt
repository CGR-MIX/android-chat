@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.ddzhilian.chat

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Pine = Color(0xFF315F50)
private val Paper = Color(0xFFF0F3EF)
private val Ink = Color(0xFF182B24)
private val Muted = Color(0xFF60716A)
private val LightColors = lightColorScheme(primary = Pine, onPrimary = Color.White, background = Paper, onBackground = Ink,
    surface = Color(0xFFF8FAF6), onSurface = Ink, surfaceVariant = Color(0xFFE3EAE1), onSurfaceVariant = Muted,
    primaryContainer = Color(0xFFDCEADF), onPrimaryContainer = Ink)
private val DarkColors = darkColorScheme(primary = Color(0xFFAACBB7), onPrimary = Color(0xFF153C2D), background = Color(0xFF121C17),
    onBackground = Color(0xFFE1EBE2), surface = Color(0xFF1B2921), onSurface = Color(0xFFE1EBE2),
    surfaceVariant = Color(0xFF2A3C30), onSurfaceVariant = Color(0xFFB3C3B8), primaryContainer = Pine, onPrimaryContainer = Color.White)

@Composable
fun ChatApp(vm: ChatViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
      CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        val backdrop = rememberLayerBackdrop()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var dialog by remember { mutableStateOf<String?>(null) }
        var pendingDownload by remember { mutableStateOf<Message?>(null) }
        var selectionRoom by remember { mutableStateOf<String?>(null) }
        var imagePreview by remember { mutableStateOf<android.net.Uri?>(null) }
        var voiceMode by rememberSaveable { mutableStateOf(false) }
        val context = LocalContext.current
        var downloadDestination by remember { mutableStateOf<android.net.Uri?>(null) }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            downloadDestination?.let { uri -> pendingDownload?.let { message -> vm.download(message, uri) } }
            pendingDownload = null; downloadDestination = null
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { selected ->
                if (context.contentResolver.getType(selected)?.startsWith("image/") == true) imagePreview = selected
                else selectionRoom?.let { vm.upload(selected, it) }
            }
        }
        val saver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == Activity.RESULT_OK && uri != null && pendingDownload != null) {
                if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    downloadDestination = uri; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else { vm.download(pendingDownload!!, uri); pendingDownload = null }
            } else pendingDownload = null
        }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(s.error, s.notice) {
            val text = s.error ?: s.notice
            if (text != null) { snackbar.showSnackbar(text); vm.clearNotice() }
        }
        BackHandler(s.activeRoomId != null) { vm.back() }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Wallpaper(dark)
                if (s.activeRoomId == null) {
                    when (tab) {
                        0 -> RoomList(s, vm, onNew = { dialog = "new" })
                        1 -> DeviceList(s, vm, onPair = { dialog = "pair" })
                        else -> Settings(s, vm)
                    }
                } else {
                    Conversation(s, vm) { message ->
                        pendingDownload = message
                        saver.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(message.mimeType.ifBlank { "application/octet-stream" }).putExtra(Intent.EXTRA_TITLE, message.fileName))
                    }
                }
            }
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)
                .fillMaxWidth().glass(backdrop, s.reduceTransparency).heightIn(min = 64.dp).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (s.activeRoomId != null) {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回聊天列表") }
                    Column(Modifier.weight(1f)) {
                        Text(s.room?.title(s.selfId) ?: "正在加入…", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (s.connected) (s.room?.members?.count { it.online } ?: 0).toString() + " 人在线" else s.status,
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CallActions(s, vm)
                    IconButton(onClick = { dialog = "search" }) { Icon(Icons.Rounded.Search, "搜索消息") }
                    IconButton(onClick = { dialog = "info" }) { Icon(Icons.Rounded.Info, "房间信息") }
                } else {
                    Box(Modifier.padding(start = 10.dp, end = 10.dp).size(34.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Chat, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    }
                    Text("点点直连", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Box(Modifier.size(6.dp).background(if (s.connected) Color(0xFF61966F) else Color(0xFFC28750), CircleShape))
                    Text(if (s.connected) "在线" else "连接中", Modifier.padding(start = 6.dp, end = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { dialog = "new" }) { Icon(Icons.Rounded.Add, "新建或加入聊天") }
                }
            }
            if (s.activeRoomId == null) {
                LiquidChatDock(
                    selectedIndex = tab, onSelected = { tab = it }, backdrop = backdrop,
                    reduceTransparency = s.reduceTransparency,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(horizontal = 30.dp, vertical = 16.dp).widthIn(max = 420.dp).fillMaxWidth(),
                )
            } else {
                Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)
                    .widthIn(max = 760.dp).fillMaxWidth().glass(backdrop, s.reduceTransparency).padding(6.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = { selectionRoom = s.activeRoomId; picker.launch(arrayOf("*/*")) }, enabled = s.localReady && s.activeRoomId !in s.archivedRoomIds && s.room?.joined(s.selfId) == true && !s.transferring) {
                        if (s.transferring) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Add, "添加图片或文件")
                    }
                    IconButton(onClick = { voiceMode = !voiceMode; vm.voice.finishRecording(false) }) { Icon(if (voiceMode) Icons.Rounded.Keyboard else Icons.Rounded.Mic, if (voiceMode) "切换打字" else "切换语音消息") }
                    if (voiceMode) VoiceRecordButton(s.activeRoomId!!, vm, Modifier.weight(1f))
                    else BasicTextField(s.draft, vm::draft, Modifier.weight(1f).heightIn(min = 48.dp, max = 144.dp).padding(vertical = 13.dp, horizontal = 3.dp),
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 22.sp), maxLines = 5,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner -> Box { if (s.draft.isEmpty()) Text(if (s.connected) "聊点什么…" else "离线，发送后联网自动重试", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp); inner() } })
                    FilledIconButton(onClick = vm::sendText, enabled = !voiceMode && s.localReady && s.activeRoomId !in s.archivedRoomIds && s.draft.isNotBlank() && s.room?.joined(s.selfId) == true, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.ArrowUpward, "发送消息")
                    }
                }
            }
            if (s.uploadProgress != null || s.downloadProgress != null) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp, start = 18.dp, end = 18.dp)) { TransferStatus(s, vm) }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 16.dp, end = 16.dp))
        }
        imagePreview?.let { uri -> ImageSendPreview(uri, onDismiss = { imagePreview = null }, onSend = { original ->
            selectionRoom?.let { vm.upload(uri, it, original) }; imagePreview = null
        }) }
        NativeCallOverlay(vm)
        when (dialog) {
            "new" -> NewChatDialog(onDismiss = { dialog = null }, onCreate = { dialog = null; vm.createRoom() }, onJoin = { dialog = null; vm.join(it) }, onPair = { dialog = "pair" })
            "pair" -> EntryDialog("通过连接码聊天", "输入另一台设备显示的连接码", "连接码", { dialog = null }) { vm.pair(it); dialog = null }
            "search" -> MessageSearchDialog(s, vm) { dialog = null }
            "info" -> RoomInfo(s) { dialog = null }
        }
    }
    }
}

@Composable
internal fun Modifier.glass(
    backdrop: Backdrop, reduce: Boolean,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(32.dp),
): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    return if (reduce || Build.VERSION.SDK_INT < 33) this.background(surface, shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    else this.drawBackdrop(backdrop = backdrop, shape = { shape }, effects = {
        vibrancy(); blur(8.dp.toPx()); lens(18.dp.toPx(), 28.dp.toPx())
    }, onDrawSurface = { drawRect(surface.copy(alpha = .72f)) })
}

@Composable
private fun Wallpaper(dark: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(if (dark) Color(0xFF121C17) else Paper)
        drawCircle(Brush.radialGradient(listOf(if (dark) Color(0xFF324D3B) else Color(0xFFD8E4D1), Color.Transparent),
            center = Offset(size.width * .95f, size.height * .25f), radius = size.width * .95f), size.width * .95f, Offset(size.width * .95f, size.height * .25f))
        drawCircle(Brush.radialGradient(listOf(if (dark) Color(0xFF25372C) else Color(0xFFE1E8D9), Color.Transparent),
            center = Offset(0f, size.height * .85f), radius = size.width * .8f), size.width * .8f, Offset(0f, size.height * .85f))
    }
}

@Composable
private fun PageHeading(kicker: String, title: String, detail: String) {
    Text(kicker, fontSize = 10.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    Text(title, fontSize = 36.sp, letterSpacing = (-1).sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    Text(detail, fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RoomList(s: ChatState, vm: ChatViewModel, onNew: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val rooms = s.rooms.filter { it.title(s.selfId).contains(search, ignoreCase = true) || it.preview.contains(search, ignoreCase = true) }.sortedWith(compareByDescending<Room> { s.preferences[it.id]?.pinned == true }.thenByDescending { it.updated })
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 108.dp, bottom = 140.dp)) {
        item {
            OutlinedTextField(search, { search = it; vm.searchMessages(it) }, Modifier.fillMaxWidth().padding(bottom = 28.dp), placeholder = { Text("搜索聊天") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(20.dp)) }, singleLine = true, shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .4f), unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .7f)))
        }
        item { Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("全部会话", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.rooms.size.toString().padStart(2, '0'), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (!s.connected) item { ConnectionBanner(s, vm) }
        if (search.isNotBlank()) {
            item { Text("本地已缓存消息 · 最多 100 条", Modifier.padding(vertical = 12.dp), fontSize = 12.sp) }
            items(s.searchResults, key = { "search-" + it.id }) { message ->
                TextButton(onClick = { vm.openSearchResult(message) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) {
                    Text(message.senderName + " · " + formatTime(message.createdAt), fontSize = 11.sp)
                    Text(message.fileName ?: message.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } }
            }
        }
        items(rooms, key = { it.id }) { room ->
            var roomMenu by remember(room.id) { mutableStateOf(false) }
            Box {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = { vm.openRoom(room.id) }, onLongClick = { roomMenu = true }).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(room.title(s.selfId), room.isPublic || room.isLan)
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(room.title(s.selfId), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(room.preview.ifBlank { if (room.isPublic) "进来坐坐，打个招呼吧" else "开始你们的对话" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if ((s.unread[room.id] ?: 0) > 0) Badge { Text("未读") }
                    Row {
                        if (s.preferences[room.id]?.pinned == true) Icon(Icons.Rounded.PushPin, "已置顶", Modifier.size(14.dp))
                        if (s.preferences[room.id]?.muted == true) Icon(Icons.Rounded.NotificationsOff, "已免打扰", Modifier.size(14.dp))
                    }
                    Text(formatTime(room.updated), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (room.isPublic) "公共" else if (room.isLan) "局域网" else "私聊", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .08f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
            DropdownMenu(roomMenu, onDismissRequest = { roomMenu = false }) {
                DropdownMenuItem(text = { Text(if (s.preferences[room.id]?.pinned == true) "取消置顶" else "置顶会话") }, onClick = { roomMenu = false; vm.roomPreference(room.id, pinned = s.preferences[room.id]?.pinned != true) })
                DropdownMenuItem(text = { Text(if (s.preferences[room.id]?.muted == true) "关闭免打扰" else "消息免打扰") }, onClick = { roomMenu = false; vm.roomPreference(room.id, muted = s.preferences[room.id]?.muted != true) })
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
        }
        if (rooms.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.Chat, if (search.isBlank()) "对话，从这里开始" else "没有找到聊天", if (search.isBlank()) "连接服务器后加入公共房间，\n或和另一台设备发起私聊。" else "换个关键词试试。") }
        item { TextButton(onClick = onNew, Modifier.fillMaxWidth().padding(top = 24.dp)) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("开启一段新对话") } }
    }
}

@Composable
private fun Avatar(name: String, public: Boolean = false) {
    Box(Modifier.size(54.dp).clip(RoundedCornerShape(20.dp)).background(if (public) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (public) Icon(Icons.Rounded.Forum, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
        else Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium, fontSize = 22.sp)
    }
}

@Composable
private fun DeviceList(s: ChatState, vm: ChatViewModel, onPair: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 108.dp, bottom = 140.dp)) {
        item { PageHeading("A LITTLE CLOSER.", "在线设备", "找到彼此，直接聊。") }
        item { Surface(Modifier.fillMaxWidth().padding(vertical = 24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .7f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(22.dp)) {
                Text("我的连接码", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s.shortCode.ifBlank { "— — — —" }, Modifier.padding(vertical = 10.dp), fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp)
                Text(s.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onPair, contentPadding = PaddingValues(0.dp)) { Text("输入对方的连接码"); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(start = 6.dp).size(16.dp)) }
            }
        } }
        if (!s.connected) item { ConnectionBanner(s, vm) }
        items(s.peers.filter { it.online }, key = { it.id }) { peer ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { vm.privateChat(peer) }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(peer.name)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(peer.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(peer.platform.ifBlank { "在线设备" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "与 " + peer.name + " 发起私聊", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
        }
        if (s.peers.none { it.online }) item { EmptyState(Icons.Rounded.Devices, "还没有其他设备", "让另一台设备打开点点直连，\n连接同一服务器后即可发现。") }
    }
}

@Composable
private fun Conversation(s: ChatState, vm: ChatViewModel, onDownload: (Message) -> Unit) {
    val listState = rememberLazyListState()
    val newestFirst = s.messages.asReversed()
    val lastId = newestFirst.firstOrNull()?.id
    var galleryId by remember(s.activeRoomId) { mutableStateOf<String?>(null) }
    var positioned by remember(s.activeRoomId) { mutableStateOf(false) }
    LaunchedEffect(s.activeRoomId, s.jumpMessageId, s.firstUnreadId, lastId) {
        val target = s.jumpMessageId ?: if (!positioned) s.firstUnreadId else null
        val index = newestFirst.indexOfFirst { it.id == target }
        if (index >= 0) { listState.scrollToItem(index); positioned = true }
        else if (!positioned && newestFirst.isNotEmpty()) { listState.scrollToItem(0); positioned = true }
        else if (s.jumpMessageId == null && listState.firstVisibleItemIndex < 2) listState.scrollToItem(0)
    }
    LaunchedEffect(s.activeRoomId, lastId, listState.firstVisibleItemIndex, positioned) {
        if (positioned && listState.firstVisibleItemIndex == 0 && s.jumpMessageId == null) s.activeRoomId?.let { vm.markVisibleRead(it, s.messages.lastOrNull { it.delivery == Delivery.Sent }?.createdAt.orEmpty()) }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = listState, reverseLayout = true,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 100.dp, bottom = 126.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        items(newestFirst, key = { it.id }) { message ->
            Column {
                if (message.id == s.firstUnreadId) TextButton(onClick = vm::locateFirstUnread, modifier = Modifier.fillMaxWidth()) { Text("未读消息 · 定位最早未读") }
                MessageBubble(message, message.senderId == s.selfId, vm, onDownload) { galleryId = message.id }
            }
        }
        if (s.loading) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } }
        if (s.hasMore) item { TextButton(onClick = vm::loadOlder, enabled = !s.loading && s.connected, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") } }
        if (!s.connected) item { ConnectionBanner(s, vm) }
        if (s.activeRoomId in s.archivedRoomIds) item { Text("房间已结束 · 仅查看本地记录", Modifier.padding(16.dp), fontSize = 12.sp) }
    }
    galleryId?.let { id -> ImageGallery(s.messages.filter { it.isImage }, id, vm, onDownload) { galleryId = null } }
}

@Composable
private fun MessageBubble(message: Message, own: Boolean, vm: ChatViewModel, onDownload: (Message) -> Unit, onImage: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
        if (!own) Text(message.senderName, Modifier.padding(start = 5.dp, bottom = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = if (own) 6.dp else 22.dp, bottomStart = if (own) 22.dp else 6.dp))
                .background(if (own) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = .94f))
                .combinedClickable(onClick = { if (message.delivery == Delivery.Failed) vm.retry(message) else if (message.isImage) onImage() else if (message.isVoice) vm.voice.toggle(message) else if (message.fileName != null) onDownload(message) }, onLongClick = { menu = true })
                .padding(horizontal = 17.dp, vertical = 13.dp)) {
                val color = if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                if (message.isImage) AttachmentThumbnail(message, vm)
                else if (message.isVoice) VoiceBubble(message, vm, color)
                else if (message.fileName != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (message.mimeType.startsWith("image/")) Icons.Rounded.Image else Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = color, modifier = Modifier.size(30.dp))
                    Column(Modifier.padding(start = 10.dp).weight(1f, fill = false)) {
                        Text(message.fileName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(message.size) + " · 点击保存", fontSize = 10.sp, color = color.copy(alpha = .78f))
                    }
                } else Text(message.text, fontSize = 16.sp, lineHeight = 24.sp, color = color)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("复制") }, onClick = { clipboard.setText(AnnotatedString(message.fileName ?: message.text)); menu = false })
                if (own && message.delivery != Delivery.Sent) DropdownMenuItem(text = { Text("移除待发送消息") }, onClick = { menu = false; vm.cancelPending(message) })
                if (own && message.delivery == Delivery.Sent) DropdownMenuItem(text = { Text("撤回消息") }, onClick = { vm.recall(message); menu = false })
                if (message.fileName != null) DropdownMenuItem(text = { Text("保存附件") }, onClick = { onDownload(message); menu = false })
            }
        }
        Text(when (message.delivery) { Delivery.Queued -> "等待发送 · 已保存在本机"; Delivery.Sending -> "发送中…"; Delivery.Failed -> "发送失败 · 点击重试"; else -> formatTime(message.createdAt) },
            Modifier.padding(horizontal = 4.dp, vertical = 5.dp), fontSize = 10.sp,
            color = if (message.delivery == Delivery.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Settings(s: ChatState, vm: ChatViewModel) {
    var name by rememberSaveable(s.name) { mutableStateOf(s.name) }
    var reduce by rememberSaveable(s.reduceTransparency) { mutableStateOf(s.reduceTransparency) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 108.dp, bottom = 150.dp)) {
        PageHeading("MAKE YOURSELF AT HOME.", "偏好设置", "熟悉的连接，更自在的方式。")
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("设备名称") }, singleLine = true, shape = RoundedCornerShape(18.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("减少透明效果", fontWeight = FontWeight.Medium); Text("使用实色界面，提升可读性", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(reduce, { reduce = it })
        }
        Button(onClick = { vm.settings(name, reduce) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("保存设置") }
        BackgroundNotificationSetting(s, vm)
        CacheAndNotificationSettings(vm)
        if (!s.connected) TextButton(onClick = vm::connect, modifier = Modifier.fillMaxWidth()) { Text("重新连接") }
        HorizontalDivider(Modifier.padding(vertical = 28.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        Text("原生 Android · Liquid Glass", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("界面由 Kyant0 / AndroidLiquidGlass 驱动。Android 13 及以上启用折射与模糊，更早版本使用实色外观。", Modifier.padding(top = 10.dp), fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Kyant0/AndroidLiquidGlass"))) }, contentPadding = PaddingValues(0.dp)) { Text("查看开源项目  ↗", fontSize = 12.sp) }
        Text("版本 1.2.0 · 无广告 · 无通讯录权限", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("支持文字、文件及一对一音视频通话。后台通知需要保持连接；被系统终止或强制停止后无法保证送达。卸载会清除本机身份。", Modifier.padding(top = 14.dp), fontSize = 11.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionBanner(s: ChatState, vm: ChatViewModel) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(s.status, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        TextButton(onClick = vm::connect) { Text("重连", fontSize = 12.sp) }
    }
}
@Composable
private fun EmptyState(icon: ImageVector, title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .55f))
        Text(title, Modifier.padding(top = 18.dp, bottom = 8.dp), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text(detail, fontSize = 12.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onCreate: () -> Unit, onJoin: (String) -> Unit, onPair: () -> Unit) {
    var id by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("开启新对话") }, text = {
        Column {
            Text("创建公共房间，或通过房间 ID 加入已有聊天。", fontSize = 13.sp)
            OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth().padding(top = 16.dp), label = { Text("房间 ID") }, singleLine = true)
            TextButton(onClick = onPair) { Text("通过设备连接码聊天") }
        }
    }, confirmButton = { TextButton(onClick = { if (id.isBlank()) onCreate() else onJoin(id) }) { Text(if (id.isBlank()) "创建公共房间" else "加入房间") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable
private fun EntryDialog(title: String, detail: String, label: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Column { Text(detail, fontSize = 13.sp); OutlinedTextField(value, { value = it }, Modifier.padding(top = 16.dp), label = { Text(label) }, singleLine = true) } },
        confirmButton = { TextButton(onClick = { submit(value) }, enabled = value.isNotBlank()) { Text("连接") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
@Composable
private fun RoomInfo(s: ChatState, dismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = dismiss, title = { Text(s.room?.title(s.selfId) ?: "房间信息") }, text = {
        Column { Text("房间 ID", fontSize = 12.sp); Text(s.activeRoomId.orEmpty(), Modifier.padding(vertical = 8.dp), fontSize = 13.sp)
            Text(s.room?.members?.joinToString("、") { it.name }.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("文字长按可复制；自己发送的消息可撤回。附件点按保存，单个上传最大 20 MB。", Modifier.padding(top = 18.dp), fontSize = 12.sp, lineHeight = 20.sp)
        }
    }, confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(s.activeRoomId.orEmpty())); dismiss() }) { Text("复制房间 ID") } }, dismissButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}
private fun formatTime(raw: String): String = runCatching { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(raw)) }.getOrDefault("")
private fun formatBytes(size: Long) = if (size < 1024) "$size B" else if (size >= 1024 * 1024) "%.1f MB".format(size / (1024.0 * 1024)) else "%.0f KB".format(size / 1024.0)
