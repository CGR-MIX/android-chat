package com.ddzhilian.chat

import android.Manifest
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private fun callPermissions(video: Boolean) = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)

@Composable
fun BackgroundNotificationSetting(s: ChatState, vm: ChatViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.backgroundNotifications(true) else vm.showError("通知未开启，可在系统设置中重新允许")
    }
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("后台消息与来电通知", fontWeight = FontWeight.Medium)
            Text("保持连接并显示常驻通知；可能增加耗电。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(s.backgroundNotifications, { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.backgroundNotifications(enabled)
        })
    }
    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("系统通知设置") }
}

@Composable
fun CallActions(s: ChatState, vm: ChatViewModel) {
    var choosing by remember { mutableStateOf<Boolean?>(null) }
    var pending by remember { mutableStateOf<Pair<Peer, Boolean>?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        pending?.let { (peer, video) ->
            if (callPermissions(video).all { granted[it] == true }) vm.calls.start(peer, video) else vm.showError("未获得通话权限，未开启麦克风或摄像头")
        }; pending = null
    }
    val peers = s.room?.members.orEmpty().filter { it.id != s.selfId && it.online }
    fun start(video: Boolean) {
        if (peers.size == 1) { pending = peers.single() to video; permissions.launch(callPermissions(video)) }
        else choosing = video
    }
    IconButton(onClick = { start(false) }, enabled = s.connected && peers.isNotEmpty()) { Icon(Icons.Rounded.Call, "语音通话") }
    IconButton(onClick = { start(true) }, enabled = s.connected && peers.isNotEmpty()) { Icon(Icons.Rounded.Videocam, "视频通话") }
    choosing?.let { video ->
        AlertDialog(onDismissRequest = { choosing = null }, title = { Text("选择通话对象") }, text = {
            Column { peers.forEach { peer -> TextButton(onClick = {
                choosing = null; pending = peer to video; permissions.launch(callPermissions(video))
            }, modifier = Modifier.fillMaxWidth()) { Text(peer.name) } } }
        }, confirmButton = { TextButton(onClick = { choosing = null }) { Text("取消") } })
    }
}

@Composable
fun NativeCallOverlay(vm: ChatViewModel) {
    val call by vm.calls.state.collectAsStateWithLifecycle()
    val current = call ?: return
    var permissionCallId by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember(current.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val latest = vm.calls.state.value
        if (latest?.id == permissionCallId && latest?.phase == CallPhase.Incoming) {
            if (callPermissions(latest.video).all { granted[it] == true }) vm.calls.accept() else permissionDenied = true
        }
        permissionCallId = null
    }
    var seconds by remember(current.id) { mutableLongStateOf(0) }
    LaunchedEffect(current.connectedAt) {
        current.connectedAt?.let { since -> while (true) { seconds = (SystemClock.elapsedRealtime() - since) / 1000; delay(1000) } }
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF14251E))) {
            val engine = vm.calls.engine
            if (engine != null && current.remoteVideo) VideoSurface(engine, false, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .62f), .35f to Color.Transparent, .6f to Color.Transparent, 1f to Color.Black.copy(alpha = .8f))))
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(current.peerName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text(when (current.phase) {
                    CallPhase.Incoming -> if (current.video) "邀请你视频通话" else "邀请你语音通话"
                    CallPhase.Outgoing -> "正在呼叫…"
                    CallPhase.Connecting -> "正在连接…"
                    CallPhase.Active -> "%02d:%02d".format(seconds / 60, seconds % 60)
                }, color = Color(0xFFB6CFC0), fontSize = 16.sp)
            }
            if (current.video && engine != null && !current.cameraOff) {
                VideoSurface(engine, true, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 145.dp, end = 20.dp).size(108.dp, 160.dp).clip(RoundedCornerShape(24.dp)))
            }
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (permissionDenied) {
                    Text("需要麦克风${if (current.video) "和摄像头" else ""}权限才能接听", color = Color.White)
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))) }) { Text("打开系统权限设置", color = Color.White) }
                }
                if (current.phase != CallPhase.Incoming) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        CallButton(if (current.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (current.muted) "取消静音" else "静音", vm.calls::mute)
                        CallButton(Icons.Rounded.VolumeUp, if (current.speaker) "关闭免提" else "免提", vm.calls::speaker)
                        if (current.video) CallButton(if (current.cameraOff) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam, if (current.cameraOff) "打开摄像头" else "关闭摄像头", vm.calls::camera)
                    }
                    if (current.video) TextButton(onClick = vm.calls::switchCamera) { Text("切换摄像头", color = Color.White) }
                    Spacer(Modifier.height(28.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(68.dp)) {
                    CallButton(Icons.Rounded.CallEnd, if (current.phase == CallPhase.Incoming) "拒绝" else "挂断", { vm.calls.end() }, Color(0xFFB83D4B))
                    if (current.phase == CallPhase.Incoming) CallButton(Icons.Rounded.Call, "接听", {
                        permissionCallId = current.id; permission.launch(callPermissions(current.video))
                    }, Color(0xFF347A59))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun CallButton(icon: ImageVector, label: String, click: () -> Unit, color: Color = Color(0xFF344D40)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = click, modifier = Modifier.size(60.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)) {
            Icon(icon, label, Modifier.size(26.dp))
        }
        Text(label, Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Color.White)
    }
}

@Composable
private fun VideoSurface(engine: NativeCallEngine, local: Boolean, modifier: Modifier) {
    val sink = if (local) engine.localSink else engine.remoteSink
    key(engine, local) {
        AndroidView(factory = { context -> SurfaceViewRenderer(context).apply {
            init(engine.eglContext, null); setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(local); setEnableHardwareScaler(true)
            if (local) {
                setZOrderMediaOverlay(true); clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 24 * view.resources.displayMetrics.density) }
                }
            }
            sink.attach(this)
        } }, modifier = modifier, onRelease = { sink.attach(null); it.release() })
    }
}
