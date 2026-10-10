package com.ddzhilian.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

data class VoiceState(val recording: Boolean = false, val seconds: Int = 0, val playingId: String? = null, val preparingId: String? = null, val position: Int = 0, val duration: Int = 0, val speaker: Boolean = true)

class VoiceController(private val context: Context, private val chat: ChatViewModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(VoiceState())
    val state = mutable.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingRoom: String? = null
    private var startedAt = 0L
    private var ticker: Job? = null
    private var loading: Job? = null
    private var player: MediaPlayer? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var focused = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop() }.build()
    private fun acquire() {
        previousMode = audio.mode; previousSpeaker = audio.isSpeakerphoneOn
        check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "无法取得音频焦点" }; focused = true
    }
    fun startRecording(roomId: String): Boolean {
        if (roomId in chat.state.value.archivedRoomIds) { chat.showError("房间已结束，不能发送语音"); return false }
        if (!chat.uiVisible || chat.calls.state.value != null) { chat.showError("通话期间不能录制语音消息"); return false }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        stop()
        return try {
            acquire()
            val file = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a"); recordingFile = file; recordingRoom = roomId
            @Suppress("DEPRECATION") val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = next
            next.setAudioSource(MediaRecorder.AudioSource.MIC); next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); next.setAudioSamplingRate(44100); next.setAudioEncodingBitRate(64000)
            next.setOutputFile(file.path); next.prepare(); next.start()
            startedAt = SystemClock.elapsedRealtime(); mutable.value = VoiceState(recording = true)
            ticker = scope.launch { while (isActive) {
                val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                mutable.update { it.copy(seconds = seconds) }
                if (seconds >= 60) { finishRecording(true); break }
                delay(150)
            } }
            true
        } catch (e: Exception) { stop(); chat.showError(e.message ?: "无法录音"); false }
    }
    fun finishRecording(send: Boolean) {
        val rec = recorder ?: return
        recorder = null; ticker?.cancel(); ticker = null
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        val file = recordingFile; val room = recordingRoom
        val valid = runCatching { rec.stop() }.isSuccess; rec.release()
        recordingFile = null; recordingRoom = null; mutable.value = VoiceState(); releaseAudio()
        if (send && valid && seconds >= 1 && file != null && room != null) chat.sendVoice(file, room, seconds.coerceAtMost(60))
        else { file?.delete(); if (send) chat.showError("录音太短，请至少说一秒") }
    }
    fun toggle(message: Message) {
        if (mutable.value.playingId == message.id || mutable.value.preparingId == message.id) { stop(); return }
        if (chat.calls.state.value != null) { chat.showError("请先结束通话再播放语音"); return }
        stop(); mutable.update { it.copy(preparingId = message.id) }
        loading = scope.launch {
            try {
                val file = chat.attachmentFile(message)
                ensureActive(); acquire()
                val next = MediaPlayer(); player = next
                next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                next.setDataSource(file.path)
                next.setOnPreparedListener { mp ->
                    if (player !== mp) return@setOnPreparedListener
                    mutable.value = VoiceState(playingId = message.id, duration = mp.duration)
                    route(true); mp.start()
                    ticker = scope.launch { while (isActive && player === mp) { mutable.update { it.copy(position = mp.currentPosition) }; delay(200) } }
                }
                next.setOnCompletionListener { stop() }
                next.setOnErrorListener { _, _, _ -> stop(); chat.showError("语音播放失败"); true }
                next.prepareAsync()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { stop(); chat.showError(e.message ?: "无法加载语音") }
        }
    }
    fun seek(position: Int) { player?.seekTo(position.coerceIn(0, mutable.value.duration)); mutable.update { it.copy(position = position) } }
    @Suppress("DEPRECATION")
    fun route(speaker: Boolean) {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val type = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audio.availableCommunicationDevices.firstOrNull { it.type == type }
            if (device == null || !audio.setCommunicationDevice(device)) { chat.showError("此设备暂无可用听筒／扬声器路由"); return }
        } else audio.isSpeakerphoneOn = speaker
        mutable.update { it.copy(speaker = speaker) }
    }
    @Suppress("DEPRECATION")
    private fun releaseAudio() { if (focused) {
        audio.abandonAudioFocusRequest(focus)
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else audio.isSpeakerphoneOn = previousSpeaker
        audio.mode = previousMode; focused = false
    } }
    fun stop() {
        loading?.cancel(); loading = null; ticker?.cancel(); ticker = null
        recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null
        recordingFile?.delete(); recordingFile = null; recordingRoom = null
        player?.release(); player = null; releaseAudio(); mutable.value = VoiceState()
    }
}
