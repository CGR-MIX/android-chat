@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.ddzhilian.chat

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kyant.backdrop.Backdrop
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import java.time.Instant
import com.ddzhilian.chat.liquid.LiquidButton
import com.ddzhilian.chat.liquid.LiquidToggle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

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
      CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalGlassOpacity provides s.glassOpacity,
      ) {
        val backdrop = rememberLayerBackdrop()
        val menus = remember { GlassMenuController() }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var dialog by remember { mutableStateOf<String?>(null) }
        var pendingDownload by remember { mutableStateOf<Message?>(null) }
        var selectionRoom by remember { mutableStateOf<String?>(null) }
        var imagePreview by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var plusOpen by remember { mutableStateOf(false) }
        var voiceMode by rememberSaveable { mutableStateOf(false) }
        // 草稿用 TextFieldValue 受控：长按头像插入、发送后清空这类「外部改动」要把光标放到末尾，
        // 否则 String 版本会保留原 selection（空输入框就是 0），光标停在插入内容的前面。
        // 切房间时把光标放到草稿末尾，和「继续往下写」的预期一致。
        var draftField by remember(s.activeRoomId) { mutableStateOf(TextFieldValue(s.draft, selection = TextRange(s.draft.length))) }
        LaunchedEffect(s.activeRoomId, s.draft) {
            // 自己打字时 field 已经是新值，这里不会打断输入法；只有外部改动才重置光标。
            if (draftField.text != s.draft) draftField = TextFieldValue(s.draft, selection = TextRange(s.draft.length))
        }
        var choosingCall by remember { mutableStateOf<Boolean?>(null) }
        var pendingCall by remember { mutableStateOf<Pair<Peer, Boolean>?>(null) }
        val context = LocalContext.current
        val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = pendingDownload
            pendingDownload = null
            if (granted && message != null) vm.saveAttachment(message)
            else if (!granted) vm.showError("未获得存储权限，无法保存到下载目录")
        }
        // Images come from the system media picker; everything else from the document picker.
        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
            val picked = uris.filterNotNull()
            if (picked.isNotEmpty()) imagePreview = picked
        }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val picked = uris.filterNotNull()
            val room = selectionRoom
            if (picked.isNotEmpty() && room != null) vm.uploadAll(picked, room)
        }
        val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            pendingCall?.let { (peer, video) ->
                if (callPermissionList(video).all { granted[it] == true }) vm.calls.start(peer, video)
                else vm.showError("未获得通话权限，未开启麦克风或摄像头")
            }
            pendingCall = null
        }
        val callPeers = s.room?.members.orEmpty().filter { it.id != s.selfId && it.online }
        val attachable = s.localReady && s.activeRoomId !in s.archivedRoomIds && s.room?.joined(s.selfId) == true
        val canCall = s.connected && s.room?.joined(s.selfId) == true && callPeers.isNotEmpty()
        fun startCall(video: Boolean) {
            if (callPeers.isEmpty()) { vm.showError("房间内暂无其他在线设备"); return }
            if (callPeers.size == 1) { pendingCall = callPeers.single() to video; callPermissions.launch(callPermissionList(video)) }
            else choosingCall = video
        }
        fun saveAttachment(message: Message) {
            if (Build.VERSION.SDK_INT < 29 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingDownload = message; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else vm.saveAttachment(message)
        }
        fun viewAttachment(uri: Uri, mimeType: String) {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType.ifBlank { "application/octet-stream" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(intent) }
                .onFailure { vm.showError("无法打开附件，可长按后重新保存") }
        }
        fun openAttachment(message: Message) {
            val uri = vm.savedUriFor(message)
            if (uri != null) { viewAttachment(uri, message.mimeType); return }
            // 还没下载：记下「接收完就打开」，再走原来的接收流程（含 API 26–28 的存储权限）。
            vm.expectOpenAfterDownload(message)
            saveAttachment(message)
        }

        // 长按助手头像会补 `@名称 `，这里统一控制输入框焦点与键盘。
        val inputFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val inputScope = rememberCoroutineScope()
        fun mentionAi() {
            inputScope.launch {
                // 语音模式下先把录音收掉并切回输入框，否则插入的草稿看不见。
                if (voiceMode) { voiceMode = false; vm.voice.finishRecording(false) }
                vm.mentionAi()
                // 等一帧让输入框真正挂载，再要焦点；失败也不影响草稿已经插入。
                withFrameNanos { }
                runCatching { inputFocus.requestFocus() }
                keyboard?.show()
            }
        }

        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(s.error, s.notice) {
            val text = s.error ?: s.notice
            if (text != null) { snackbar.showSnackbar(text); vm.clearNotice() }
        }
        // 点开还没下载的附件：接收完成后由 ViewModel 发一次性请求，这里拉起系统默认应用。
        LaunchedEffect(s.openRequest) {
            val request = s.openRequest ?: return@LaunchedEffect
            viewAttachment(request.uri, request.mimeType)
            vm.consumeOpenRequest()
        }
        BackHandler(s.activeRoomId != null) { vm.back() }
        // 管理员模式：返回键先退出面板，密码验证对话框浮在任意界面之上。
        BackHandler(s.admin is AdminMode.Open) { vm.adminClose() }
        if (s.admin is AdminMode.Gate) AdminGateDialog(s, vm)
        if (s.admin is AdminMode.Open) {
            // 管理员面板接管整屏：不渲染聊天输入栏与 Dock，避免触摸穿透。
            AdminScreen(s, vm)
        } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Wallpaper(dark)
                if (s.activeRoomId == null) {
                    when (tab) {
                        0 -> RoomList(s, vm, menus, onNew = { dialog = "new" })
                        1 -> DeviceList(s, vm, onPair = { dialog = "pair" })
                        else -> Settings(s, vm, dark)
                    }
                } else {
                    Conversation(s, vm, menus, onDownload = ::openAttachment, onMentionAi = ::mentionAi)
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
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp).widthIn(max = 760.dp).fillMaxWidth()) {
                    // 命中 @名称 / 唤醒词时提前说明这条消息会交给助手；规则与服务端一致。
                    if (parseAiMention(s.draft, s.ai) != null) {
                        Text(
                            "将由 " + s.ai.name + " 回复",
                            Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    // Round attach button, deliberately separate from the input capsule.
                    Box(Modifier.size(ControlHeight).glass(backdrop, s.reduceTransparency, shape = CircleShape)
                        .clickable(role = Role.Button, enabled = s.localReady) { plusOpen = !plusOpen }, contentAlignment = Alignment.Center) {
                        if (s.transferring) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        if (s.transferring) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(if (plusOpen) Icons.Rounded.Close else Icons.Rounded.Add, "添加图片、文件或发起通话")
                    }
                    Spacer(Modifier.width(10.dp))
                    // Only horizontal padding: vertical padding would sit INSIDE heightIn, so the
                    // capsule height would become content+padding and drift. heightIn(min) alone
                    // gives exactly ControlHeight for one line and still grows for multiple lines.
                    Row(Modifier.weight(1f).glass(backdrop, s.reduceTransparency).heightIn(min = ControlHeight).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { voiceMode = !voiceMode; vm.voice.finishRecording(false) }) { Icon(if (voiceMode) Icons.Rounded.Keyboard else Icons.Rounded.Mic, if (voiceMode) "切换打字" else "切换语音消息") }
                        if (voiceMode) VoiceRecordButton(s.activeRoomId!!, vm, Modifier.weight(1f).align(Alignment.CenterVertically))
                        else BasicTextField(draftField, { value -> draftField = value; vm.draft(value.text) }, Modifier.weight(1f).heightIn(min = 48.dp, max = 144.dp).padding(vertical = 13.dp, horizontal = 3.dp).focusRequester(inputFocus),
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 22.sp), maxLines = 5,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner -> Box { if (draftField.text.isEmpty()) Text(if (s.connected) "聊点什么…" else "离线，发送后联网自动重试", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp); inner() } })
                        FilledIconButton(onClick = vm::sendText, enabled = !voiceMode && attachable && s.draft.isNotBlank(), modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.ArrowUpward, "发送消息")
                        }
                    }
                    }
                }
            }
            if (s.uploadProgress != null || s.downloadProgress != null) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp, start = 18.dp, end = 18.dp)) { TransferStatus(s, vm) }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 16.dp, end = 16.dp))
            // Both overlays must stay outside the recorded layer, otherwise they would refract themselves.
            GlassMenuOverlay(menus, backdrop, s.reduceTransparency)
            if (plusOpen) {
                GlassPlusPanel(backdrop, s.reduceTransparency, actions = listOf(
                    PlusAction("图片", Icons.Rounded.PhotoLibrary, enabled = attachable) {
                        selectionRoom = s.activeRoomId
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    PlusAction("文件", Icons.Rounded.Folder, enabled = attachable) {
                        selectionRoom = s.activeRoomId
                        filePicker.launch(arrayOf("*/*"))
                    },
                    PlusAction("音频通话", Icons.Rounded.Call, enabled = canCall) { startCall(false) },
                    PlusAction("视频通话", Icons.Rounded.Videocam, enabled = canCall) { startCall(true) },
                ), onDismiss = { plusOpen = false })
            }
        }
        imagePreview.takeIf { it.isNotEmpty() }?.let { uris -> ImageSendPreview(uris, onDismiss = { imagePreview = emptyList() }, onSend = { original ->
            selectionRoom?.let { vm.uploadAll(uris, it, original) }; imagePreview = emptyList()
        }) }
        choosingCall?.let { video ->
            AlertDialog(onDismissRequest = { choosingCall = null }, title = { Text("选择通话对象") }, text = {
                Column { callPeers.forEach { peer -> TextButton(onClick = {
                    choosingCall = null; pendingCall = peer to video; callPermissions.launch(callPermissionList(video))
                }, modifier = Modifier.fillMaxWidth()) { Text(peer.name) } } }
            }, confirmButton = { TextButton(onClick = { choosingCall = null }) { Text("取消") } })
        }
        NativeCallOverlay(vm)
        when (dialog) {
            "new" -> NewChatDialog(onDismiss = { dialog = null }, onCreate = { dialog = null; vm.createRoom() }, onJoin = { dialog = null; vm.join(it) }, onPair = { dialog = "pair" })
            "new" -> NewChatDialog(onDismiss = { dialog = null }, onCreate = { dialog = null; vm.createRoom() }, onJoin = { dialog = null; vm.join(it) }, onPair = { dialog = "pair" })
            "pair" -> EntryDialog("通过连接码聊天", "输入另一台设备显示的连接码", "连接码", { dialog = null }) { vm.pair(it); dialog = null }
            "search" -> MessageSearchDialog(s, vm) { dialog = null }
            "info" -> RoomInfo(s) { dialog = null }
        }
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
    val alpha = LocalGlassOpacity.current
    // Frosting has to fall off with the veil. A fixed blur radius smears whatever is behind the
    // surface into flat grey, so the material keeps reading as milky even when the veil is thin and
    // no text behind it is ever legible. Reaching zero blur at MIN_GLASS_OPACITY is what keeps the
    // fixed 40% veil honest.
    val frosted = ((alpha - MIN_GLASS_OPACITY) / (1f - MIN_GLASS_OPACITY)).coerceIn(0f, 1f)
    val blurRadius = 8.dp * (frosted * frosted)
    return if (reduce || Build.VERSION.SDK_INT < 33) this.background(surface, shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    else this.drawBackdrop(backdrop = backdrop, shape = { shape }, effects = {
        vibrancy()
        // createBlurEffect rejects a zero radius, so skip the effect entirely at full clarity.
        if (blurRadius > 0.dp) blur(blurRadius.toPx())
        lens(18.dp.toPx(), 28.dp.toPx())
    }, onDrawSurface = { drawRect(surface.copy(alpha = alpha)) })
}

/** Wallpaper drawing, shared by the on-screen canvas and the backdrop the liquid controls sample. */
private fun DrawScope.drawWallpaper(dark: Boolean) {
    drawRect(if (dark) Color(0xFF121C17) else Paper)
    drawCircle(Brush.radialGradient(listOf(if (dark) Color(0xFF324D3B) else Color(0xFFD8E4D1), Color.Transparent),
        center = Offset(size.width * .95f, size.height * .25f), radius = size.width * .95f), size.width * .95f, Offset(size.width * .95f, size.height * .25f))
    drawCircle(Brush.radialGradient(listOf(if (dark) Color(0xFF25372C) else Color(0xFFE1E8D9), Color.Transparent),
        center = Offset(0f, size.height * .85f), radius = size.width * .8f), size.width * .8f, Offset(0f, size.height * .85f))
}

@Composable
private fun Wallpaper(dark: Boolean) {
    Canvas(Modifier.fillMaxSize()) { drawWallpaper(dark) }
}

@Composable
private fun PageHeading(kicker: String, title: String, detail: String) {
    Text(kicker, fontSize = 10.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    Text(title, fontSize = 36.sp, letterSpacing = (-1).sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    Text(detail, fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RoomList(s: ChatState, vm: ChatViewModel, menus: GlassMenuController, onNew: () -> Unit) {
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
                    Text(message.fileName ?: plainChatText(message.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                } }
            }
        }
        items(rooms, key = { it.id }) { room ->
            var anchor by remember(room.id) { mutableStateOf(Rect.Zero) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .onGloballyPositioned { anchor = it.boundsInWindow() }
                .combinedClickable(onClick = { vm.openRoom(room.id) }, onLongClick = {
                    val pinned = s.preferences[room.id]?.pinned == true
                    val muted = s.preferences[room.id]?.muted == true
                    menus.show(anchor, listOf(
                        GlassMenuItem(if (pinned) "取消置顶" else "置顶会话", if (pinned) Icons.Rounded.PushPin else Icons.Rounded.PushPin) { vm.roomPreference(room.id, pinned = !pinned) },
                        GlassMenuItem(if (muted) "关闭免打扰" else "消息免打扰", if (muted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff) { vm.roomPreference(room.id, muted = !muted) },
                    ))
                }).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(room.title(s.selfId), room.isPublic || room.isLan)
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(room.title(s.selfId), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 服务端预览是原始富文本，先收成一行纯文字，避免会话列表里露出标签和 Markdown 标记。
                    val preview = remember(room.preview) { plainChatText(room.preview) }
                    Text(preview.ifBlank { if (room.isPublic) "进来坐坐，打个招呼吧" else "开始你们的对话" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** One rendered message row: a single message, or a stacked run of consecutive images. */
private class MessageRow(val key: String, val messages: List<Message>)

/** Consecutive images from the same sender collapse into one stack, newest on top. */
private fun groupImages(newestFirst: List<Message>): List<MessageRow> = buildList {
    var index = 0
    while (index < newestFirst.size) {
        val message = newestFirst[index]
        if (message.isImage) {
            val run = mutableListOf(message)
            var next = index + 1
            while (next < newestFirst.size && newestFirst[next].isImage && newestFirst[next].senderId == message.senderId) {
                run.add(newestFirst[next]); next++
            }
            add(MessageRow("images-" + message.id, run)); index = next
        } else {
            add(MessageRow(message.id, listOf(message))); index++
        }
    }
}

@Composable
private fun Conversation(s: ChatState, vm: ChatViewModel, menus: GlassMenuController, onDownload: (Message) -> Unit, onMentionAi: () -> Unit) {
    val listState = rememberLazyListState()
    val newestFirst = s.messages.asReversed()
    val rows = remember(newestFirst) { groupImages(newestFirst) }
    val lastId = newestFirst.firstOrNull()?.id
    var galleryId by remember(s.activeRoomId) { mutableStateOf<String?>(null) }
    var positioned by remember(s.activeRoomId) { mutableStateOf(false) }
    LaunchedEffect(s.activeRoomId, s.jumpMessageId, s.firstUnreadId, lastId, rows) {
        val target = s.jumpMessageId ?: if (!positioned) s.firstUnreadId else null
        val index = rows.indexOfFirst { row -> row.messages.any { it.id == target } }
        if (index >= 0) { listState.scrollToItem(index); positioned = true }
        else if (!positioned && rows.isNotEmpty()) { listState.scrollToItem(0); positioned = true }
        else if (s.jumpMessageId == null && listState.firstVisibleItemIndex < 2) listState.scrollToItem(0)
    }
    LaunchedEffect(s.activeRoomId, lastId, listState.firstVisibleItemIndex, positioned) {
        if (positioned && listState.firstVisibleItemIndex == 0 && s.jumpMessageId == null) s.activeRoomId?.let { vm.markVisibleRead(it, s.messages.lastOrNull { it.delivery == Delivery.Sent }?.createdAt.orEmpty()) }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = listState, reverseLayout = true,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 100.dp, bottom = 126.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        items(rows, key = { it.key }) { row ->
            Column {
                if (row.messages.any { it.id == s.firstUnreadId }) TextButton(onClick = vm::locateFirstUnread, modifier = Modifier.fillMaxWidth()) { Text("未读消息 · 定位最早未读") }
                val single = row.messages.singleOrNull()
                if (row.messages.first().isImage) ImageStackBubble(row.messages, s, vm, menus, onDownload) { galleryId = it }
                else if (single != null) MessageBubble(s, single, single.senderId == s.selfId, vm, menus, onDownload, onMentionAi) { galleryId = single.id }
            }
        }
        if (s.aiThinkingRoom != null && s.aiThinkingRoom == s.activeRoomId) item {
            Text(
                "AI 正在思考…",
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.loading) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } }
        if (s.hasMore) item { TextButton(onClick = vm::loadOlder, enabled = !s.loading && s.connected, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") } }
        if (!s.connected) item { ConnectionBanner(s, vm) }
        if (s.activeRoomId in s.archivedRoomIds) item { Text("房间已结束 · 仅查看本地记录", Modifier.padding(16.dp), fontSize = 12.sp) }
    }
    galleryId?.let { id -> ImageGallery(s.messages.filter { it.isImage }, id, vm, onDownload) { galleryId = null } }
}

/** A run of images shown as one stacked card. Dragging the top card in any direction switches image. */
@Composable
private fun ImageStackBubble(images: List<Message>, s: ChatState, vm: ChatViewModel, menus: GlassMenuController, onDownload: (Message) -> Unit, onOpen: (String) -> Unit) {
    val own = images.first().senderId == s.selfId
    val top = images.first()
    var anchor by remember(top.id) { mutableStateOf(Rect.Zero) }
    Column(Modifier.fillMaxWidth().onGloballyPositioned { anchor = it.boundsInWindow() }.padding(start = 8.dp, end = 8.dp), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
        if (!own) Text(top.senderName, Modifier.padding(start = 5.dp, bottom = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImageStackCard(images, vm,
            onOpen = { message -> onOpen(message.id) },
            onLongPress = { message -> menus.show(anchor, messageMenu(message, own, vm, onDownload) { onOpen(message.id) }) })
        Text(deliveryLabel(top) + if (images.size > 1) " · ${images.size} 张图片 · 左右滑动 · 点展开" else "",
            Modifier.padding(horizontal = 4.dp, vertical = 5.dp), fontSize = 10.sp,
            color = if (top.delivery == Delivery.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessageBubble(s: ChatState, message: Message, own: Boolean, vm: ChatViewModel, menus: GlassMenuController, onDownload: (Message) -> Unit, onMentionAi: () -> Unit, onImage: () -> Unit) {
    var anchor by remember(message.id) { mutableStateOf(Rect.Zero) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
        if (!own) {
            val bot = message.senderId == AI_BOT_DEVICE_ID
            // 头像同时是「@助手」入口：长按把 `@名称 ` 补进输入框。触摸区 40dp，头像本体 28dp。
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (bot) {
                    // 后台配了头像就画头像，否则退回「AI」小圆标；两处都跟主站同一份配置。
                    val avatar = remember(s.aiAvatarRevision) { vm.aiAvatar() }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).combinedClickable(
                            onLongClickLabel = "提及 " + s.ai.name,
                            onLongClick = onMentionAi,
                            onClick = {},
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (avatar != null) {
                                // Crop 而不是默认的 Fit：源图很少是正方形（线上这张是 320×426），
                                // Fit 会左右留白，圆裁切后只剩中间一条，看着就不像圆头像。
                                Image(avatar.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Text("AI", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.width(1.dp))
                }
                Text(
                    if (bot) s.ai.name else message.senderName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
            Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = if (own) 6.dp else 22.dp, bottomStart = if (own) 22.dp else 6.dp))
                .background(if (own) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = .94f))
                .combinedClickable(onClick = { if (message.delivery == Delivery.Failed) vm.retry(message) else if (message.isVoice) vm.voice.toggle(message) else if (message.fileName != null) onDownload(message) }, onLongClick = { menus.show(anchor, messageMenu(message, own, vm, onDownload) { onImage() }) })
                .padding(horizontal = 17.dp, vertical = 13.dp)) {
                val color = if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                if (message.isVoice) VoiceBubble(message, vm, color)
                else if (message.fileName != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (message.mimeType.startsWith("image/")) Icons.Rounded.Image else Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = color, modifier = Modifier.size(30.dp))
                    Column(Modifier.padding(start = 10.dp).weight(1f, fill = false)) {
                        Text(message.fileName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(message.size) + " · 点击打开", fontSize = 10.sp, color = color.copy(alpha = .78f))
                    }
                } else MarkdownMessage(message.text, color)
            }
        }
        Text(deliveryLabel(message), Modifier.padding(horizontal = 4.dp, vertical = 5.dp), fontSize = 10.sp,
            color = if (message.delivery == Delivery.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun deliveryLabel(message: Message) = when (message.delivery) {
    Delivery.Queued -> "等待发送 · 已保存在本机"; Delivery.Sending -> "发送中…"; Delivery.Failed -> "发送失败 · 点击重试"; else -> formatTime(message.createdAt)
}

/** Shared long-press actions, drawn in the liquid-glass overlay instead of a platform dropdown. */
private fun messageMenu(message: Message, own: Boolean, vm: ChatViewModel, onDownload: (Message) -> Unit, onOpen: (() -> Unit)? = null) = buildList {
    add(GlassMenuItem("复制", Icons.Rounded.ContentCopy) { vm.copyMessage(message) })
    if (own && message.delivery != Delivery.Sent) add(GlassMenuItem("移除待发送消息", Icons.Rounded.DeleteOutline, danger = true) { vm.cancelPending(message) })
    if (own && message.delivery == Delivery.Sent) add(GlassMenuItem("撤回消息", Icons.AutoMirrored.Rounded.Undo, danger = true) { vm.recall(message) })
    if (message.fileName != null) {
        // 未下载时也走同一路径：接收完成即打开，文件同时留在 Downloads/点点直连。
        add(GlassMenuItem("打开附件", Icons.Rounded.OpenInNew) { onDownload(message) })
    }
    if (message.isImage) onOpen?.let { open -> add(GlassMenuItem("全屏查看", Icons.Rounded.OpenInFull) { open() }) }
}

@Composable
private fun Settings(s: ChatState, vm: ChatViewModel, dark: Boolean) {
    // A self-contained backdrop painted from the same wallpaper recipe as the page behind it. It must
    // NOT be the page's own recorded layer: a glass control drawn inside the layer it samples recurses
    // through RenderNode and takes the process down with a native stack overflow.
    val backdrop = rememberCanvasBackdrop { drawWallpaper(dark) }
    var name by rememberSaveable(s.name) { mutableStateOf(s.name) }
    var reduce by rememberSaveable(s.reduceTransparency) { mutableStateOf(s.reduceTransparency) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 108.dp, bottom = 150.dp)) {
        PageHeading("MAKE YOURSELF AT HOME.", "偏好设置", "熟悉的连接，更自在的方式。")
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("设备名称") }, singleLine = true, shape = RoundedCornerShape(18.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("减少透明效果", fontWeight = FontWeight.Medium); Text("使用实色界面，提升可读性", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // Always the liquid switch, in both appearances. Solid mode only repaints its surface
            // (see LiquidToggle's `reduce`), so the control does not change shape or size when toggled.
            LiquidToggle(selected = { reduce }, onSelect = { reduce = it }, backdrop = backdrop, reduce = reduce)
        }
        // Real liquid-glass button, kept in both appearances: the capsule stretches toward the touch
        // point while pressed, and in solid mode only its surface is repainted.
        LiquidButton(
            onClick = { vm.settings(name, reduce) },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            reduce = reduce,
        ) {
            Text("保存设置", fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(24.dp))
        UpdateSetting(s, vm, backdrop, reduce)
        BackgroundNotificationSetting(s, vm, backdrop, reduce)
        CacheAndNotificationSettings(vm)
        if (!s.connected) TextButton(onClick = vm::connect, modifier = Modifier.fillMaxWidth()) { Text("重新连接") }
        HorizontalDivider(Modifier.padding(vertical = 28.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        Text("原生 Android · Liquid Glass", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("界面由 Kyant0 / AndroidLiquidGlass 驱动。Android 13 及以上启用折射与模糊，更早版本使用实色外观。", Modifier.padding(top = 10.dp), fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Kyant0/AndroidLiquidGlass"))) }, contentPadding = PaddingValues(0.dp)) { Text("查看开源项目  ↗", fontSize = 12.sp) }
        // 隐秘入口：连续点 7 次「版本」才弹出管理员验证，界面上不留任何开关。
        Text("版本 ${BuildConfig.VERSION_NAME} · 无广告 · 无通讯录权限", Modifier.clickable { vm.adminVersionTap() }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("支持文字、图片、文件及一对一音视频通话。图片按原始比例显示，多张图片叠成一张卡片且每张都显示缩略内容，上下左右滑动可切换。后台通知需要保持连接；被系统终止或强制停止后无法保证送达。卸载会清除本机身份。", Modifier.padding(top = 14.dp), fontSize = 11.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("长按消息打开玻璃操作框；自己发送的消息可撤回。附件点按即用系统默认应用打开，并同时保存到 Downloads/点点直连，单个上传最大 $MAX_ATTACHMENT_LABEL。", Modifier.padding(top = 18.dp), fontSize = 12.sp, lineHeight = 20.sp)
        }
    }, confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(s.activeRoomId.orEmpty())); dismiss() }) { Text("复制房间 ID") } }, dismissButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}
private fun formatTime(raw: String): String = runCatching { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(raw)) }.getOrDefault("")
private fun formatBytes(size: Long) = if (size < 1024) "$size B" else if (size >= 1024 * 1024) "%.1f MB".format(size / (1024.0 * 1024)) else "%.0f KB".format(size / 1024.0)
