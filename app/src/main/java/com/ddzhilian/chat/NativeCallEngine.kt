package com.ddzhilian.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class CallVideoSink : VideoSink {
    private var target: VideoSink? = null
    @Synchronized fun attach(sink: VideoSink?) { target = sink }
    @Synchronized override fun onFrame(frame: VideoFrame) { target?.onFrame(frame) }
}

class RtcRuntime(context: Context) {
    val egl: EglBase = EglBase.create()
    val factory: PeerConnectionFactory
    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        audio.release()
    }
}

class NativeCallEngine(
    private val context: Context, private val runtime: RtcRuntime,
    private val send: (JSONObject) -> Unit, private val connected: () -> Unit,
    private val remoteVideo: () -> Unit, private val failed: (String) -> Unit,
) {
    val localSink = CallVideoSink()
    val remoteSink = CallVideoSink()
    val eglContext get() = runtime.egl.eglBaseContext
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var texture: SurfaceTextureHelper? = null
    private var capturing = false
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteReady = false
    private var disconnectCheck: Runnable? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var audioConfigured = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) dispatch { failed("音频被其他通话占用") } }.build()
    private fun dispatch(block: () -> Unit) { main.post { if (!closed) runCatching(block).onFailure { failed(it.message ?: "通话处理失败") } } }

    fun start(video: Boolean, ice: JSONArray) {
        val servers = ice.objects().mapNotNull { entry ->
            val urls = when (val value = entry.opt("urls")) {
                is String -> listOf(value)
                is JSONArray -> (0 until value.length()).map { value.getString(it) }
                else -> emptyList()
            }
            if (urls.isEmpty()) null else PeerConnection.IceServer.builder(urls)
                .setUsername(entry.optString("username")).setPassword(entry.optString("credential")).createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = runtime.factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { dispatch {
                disconnectCheck?.let(main::removeCallbacks); disconnectCheck = null
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> connected()
                    PeerConnection.IceConnectionState.FAILED -> failed("通话连接失败，请检查网络或重拨")
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        disconnectCheck = Runnable { if (!closed) failed("通话连接已中断") }.also { main.postDelayed(it, 15_000) }
                    }
                    else -> Unit
                }
            } }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) { dispatch {
                send(JSONObject().put("kind", "ice-candidate").put("candidate", JSONObject().put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)))
            } }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) { channel.close(); channel.dispose() }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) { dispatch {
                (receiver.track() as? VideoTrack)?.let { track ->
                    remoteTrack?.removeSink(remoteSink); remoteTrack = track; track.addSink(remoteSink); remoteVideo()
                }
            } }
        }) ?: error("无法初始化 WebRTC")
        previousMode = audio.mode; previousSpeaker = audio.isSpeakerphoneOn
        check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "无法取得通话音频权限" }
        audioConfigured = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        speaker(video)
        audioSource = runtime.factory.createAudioSource(MediaConstraints())
        audioTrack = runtime.factory.createAudioTrack("audio", audioSource).also { pc!!.addTrack(it, listOf("dd-chat")) }
        if (video) {
            val cameras = Camera2Enumerator(context)
            val camera = cameras.deviceNames.firstOrNull(cameras::isFrontFacing) ?: cameras.deviceNames.firstOrNull() ?: error("没有可用摄像头")
            val capture = cameras.createCapturer(camera, null) ?: error("无法打开摄像头")
            capturer = capture
            texture = SurfaceTextureHelper.create("DdChatCapture", runtime.egl.eglBaseContext)
            videoSource = runtime.factory.createVideoSource(false)
            capture.initialize(texture, context, videoSource!!.capturerObserver)
            videoTrack = runtime.factory.createVideoTrack("video", videoSource).also { it.addSink(localSink); pc!!.addTrack(it, listOf("dd-chat")) }
            capture.startCapture(960, 540, 24); capturing = true
        }
    }
    private fun observer(done: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() { dispatch(done) }
        override fun onCreateFailure(reason: String) { dispatch { failed("音视频协商失败：$reason") } }
        override fun onSetFailure(reason: String) { dispatch { failed("音视频协商失败：$reason") } }
    }
    fun offer() = createDescription(true)
    private fun createDescription(offer: Boolean) {
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { dispatch {
                pc?.setLocalDescription(observer {
                    send(JSONObject().put("kind", sdp.type.canonicalForm()).put("description", JSONObject()
                        .put("type", sdp.type.canonicalForm()).put("sdp", sdp.description)))
                }, sdp)
            } }
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String) { dispatch { failed("音视频协商失败：$reason") } }
            override fun onSetFailure(reason: String) { dispatch { failed("音视频协商失败：$reason") } }
        }
        if (offer) pc?.createOffer(observer, MediaConstraints()) else pc?.createAnswer(observer, MediaConstraints())
    }
    fun signal(signal: JSONObject) {
        when (signal.optString("kind")) {
            "offer", "answer" -> {
                val type = signal.getString("kind")
                val description = signal.getJSONObject("description")
                require(description.getString("type") == type)
                pc?.setRemoteDescription(observer {
                    remoteReady = true; pendingIce.forEach { pc?.addIceCandidate(it) }; pendingIce.clear()
                    if (type == "offer") createDescription(false)
                }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), description.getString("sdp")))
            }
            "ice-candidate" -> signal.optJSONObject("candidate")?.let { c ->
                val candidate = IceCandidate(c.optString("sdpMid").takeIf { it.isNotBlank() }, c.optInt("sdpMLineIndex"), c.getString("candidate"))
                if (remoteReady) pc?.addIceCandidate(candidate) else if (pendingIce.size < 256) pendingIce.add(candidate)
            }
        }
    }
    fun mute(muted: Boolean) { audioTrack?.setEnabled(!muted) }
    fun camera(off: Boolean) {
        if (off && capturing) { capturer?.stopCapture(); capturing = false }
        else if (!off && !capturing && capturer != null) { capturer?.startCapture(960, 540, 24); capturing = true }
        videoTrack?.setEnabled(!off)
    }
    fun switchCamera() { capturer?.switchCamera(null) }
    @Suppress("DEPRECATION")
    fun speaker(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (enabled) audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let(audio::setCommunicationDevice)
            else audio.clearCommunicationDevice()
        } else audio.isSpeakerphoneOn = enabled
    }
    @Suppress("DEPRECATION")
    fun close() {
        if (closed) return
        closed = true; main.removeCallbacksAndMessages(null)
        localSink.attach(null); remoteSink.attach(null)
        runCatching { capturer?.stopCapture() }; capturer?.dispose(); capturer = null
        remoteTrack?.removeSink(remoteSink); remoteTrack = null
        pc?.close(); pc?.dispose(); pc = null
        videoTrack?.dispose(); videoSource?.dispose(); texture?.dispose()
        audioTrack?.dispose(); audioSource?.dispose()
        if (audioConfigured) {
            audio.abandonAudioFocusRequest(focus)
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else audio.isSpeakerphoneOn = previousSpeaker
            audio.mode = previousMode
        }
    }
}
