package com.example.mymusic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        var audioSessionId: Int = 0
        var instance: PlaybackService? = null
        private val _bitPerfectState = MutableStateFlow(false)
        val bitPerfectState: StateFlow<Boolean> = _bitPerfectState.asStateFlow()
    }

    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var isCurrentlyBitPerfect: Boolean = false
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager


        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
                // 1. 生成官方原始的主水管（绝对无损的 Float 管道）
                val originalSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true) // 👈 誓死保卫 32-bit 极致音质！
                    .build()
                // 2. 给主水管套上我们的“透明监控外壳”，瞒天过海！
                return VisualizerInterceptingAudioSink(originalSink)
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50000, 100000, 2500, 5000).build()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
            .build()

        val builtPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(loadControl)
            .build()

        // 👇 云糯修复：添加底层监听器，一旦发声瞬间捕获真实的 AudioSessionId！
        builtPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(sessionId: Int) {
                audioSessionId = sessionId
            }
        })

        player = builtPlayer
        audioSessionId = builtPlayer.audioSessionId

        val prefs = getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val isBitPerfectEnabled = prefs.getBoolean("enable_bit_perfect", false)

        applyOffloadPreference(builtPlayer, enableOffload = false)
        applyUsbBitPerfectSetting(isBitPerfectEnabled)

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_PLAYER_FULLSCREEN"
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, builtPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        val notificationProvider = object : DefaultMediaNotificationProvider(this) {
            override fun getMediaButtons(session: MediaSession, playerCommands: Player.Commands, customLayout: ImmutableList<CommandButton>, showPauseButton: Boolean): ImmutableList<CommandButton> {
                return ImmutableList.copyOf(
                    super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                        .filter { it.playerCommand != Player.COMMAND_SEEK_FORWARD && it.playerCommand != Player.COMMAND_SEEK_BACK }
                )
            }
        }
        notificationProvider.setSmallIcon(R.drawable.ic_notification_logo)
        setMediaNotificationProvider(notificationProvider)
    }

    private fun applyOffloadPreference(targetPlayer: ExoPlayer, enableOffload: Boolean) {
        val mode = if (enableOffload) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(mode).setIsGaplessSupportRequired(enableOffload).build()
        targetPlayer.trackSelectionParameters = targetPlayer.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(offloadPreferences).build()
        Log.d("Auralis", "Offload 模式: ${if (enableOffload) "已启用" else "已禁用（Bit-perfect 路径）"}")
    }

    fun applyUsbBitPerfectSetting(enable: Boolean, sampleRate: Int = 0, bitDepth: Int = 0) {
        if (Build.VERSION.SDK_INT < 34) return
        if (enable) tryEnableUsbBitPerfect(sampleRate, bitDepth) else tryDisableUsbBitPerfect()
        player?.let { applyOffloadPreference(it, enableOffload = false) }
    }

    private fun tryEnableUsbBitPerfect(requestedSampleRate: Int, requestedBitDepth: Int) {
        if (isCurrentlyBitPerfect || Build.VERSION.SDK_INT < 34) return
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val usbDac = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } ?: return
            val targetSampleRate = resolveBestSampleRate(usbDac, requestedSampleRate)
            val targetEncoding = resolveBestEncoding(usbDac, requestedBitDepth)

            val mixerAttributes = AudioMixerAttributes.Builder(
                android.media.AudioFormat.Builder().setSampleRate(targetSampleRate).setEncoding(targetEncoding).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build()
            ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()

            val audioAttributes = android.media.AudioAttributes.Builder().setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
            val success = audioManager.setPreferredMixerAttributes(audioAttributes, usbDac, mixerAttributes)
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            if (success) {
                isCurrentlyBitPerfect = true
                _bitPerfectState.value = true
                mainHandler.post { android.widget.Toast.makeText(this, "DAC 独占已激活: ${targetSampleRate / 1000.0}kHz", android.widget.Toast.LENGTH_SHORT).show() }
            } else {
                mainHandler.post { android.widget.Toast.makeText(this, "DAC 独占失败: 设备不支持该规格", android.widget.Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) { Log.e("Auralis", "激活 USB Bit-perfect 失败: ${e.message}") }
    }

    private fun tryDisableUsbBitPerfect() {
        if (!isCurrentlyBitPerfect || Build.VERSION.SDK_INT < 34) return
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val usbDac = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            if (usbDac != null) {
                val audioAttributes = android.media.AudioAttributes.Builder().setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
                audioManager.clearPreferredMixerAttributes(audioAttributes, usbDac)
            }
            isCurrentlyBitPerfect = false
            _bitPerfectState.value = false
            android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(this, "已恢复 Android 系统混音", android.widget.Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { Log.e("Auralis", "关闭 USB Bit-perfect 失败: ${e.message}") }
    }

    private fun resolveBestSampleRate(usbDac: AudioDeviceInfo, requested: Int): Int {
        val dacRates = usbDac.sampleRates
        if (dacRates.isEmpty()) return if (requested > 0) requested else 48000
        if (requested > 0 && dacRates.contains(requested)) return requested
        return dacRates.maxOrNull() ?: 48000
    }

    private fun resolveBestEncoding(usbDac: AudioDeviceInfo, requestedBitDepth: Int): Int {
        val dacEncodings = usbDac.encodings
        val candidates = when {
            requestedBitDepth >= 32 -> listOf(android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED, android.media.AudioFormat.ENCODING_PCM_FLOAT, android.media.AudioFormat.ENCODING_PCM_16BIT)
            requestedBitDepth >= 24 -> listOf(android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED, android.media.AudioFormat.ENCODING_PCM_FLOAT, android.media.AudioFormat.ENCODING_PCM_16BIT)
            else -> listOf(android.media.AudioFormat.ENCODING_PCM_16BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT)
        }
        if (dacEncodings.isEmpty()) return candidates.first()
        return candidates.firstOrNull { dacEncodings.contains(it) } ?: android.media.AudioFormat.ENCODING_PCM_16BIT
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        instance = null
        tryDisableUsbBitPerfect()
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}

object VisualizerData {
    @Volatile var amplitude: Float = 0f
}

// 👇 终极杀招：直接套壳拦截底层 AudioSink！无论 ExoPlayer 怎么屏蔽都不起作用！
@UnstableApi
class VisualizerInterceptingAudioSink(private val delegate: androidx.media3.exoplayer.audio.AudioSink) : androidx.media3.exoplayer.audio.AudioSink by delegate {
    private var currentEncoding = androidx.media3.common.C.ENCODING_INVALID
    private var currentChannels = 2
    private var filterStateL = 0.0f
    private var filterStateR = 0.0f
    private val alphaLpf = 0.15f // 完美的 100Hz 极限低通滤波

    override fun configure(inputFormat: androidx.media3.common.Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentEncoding = inputFormat.pcmEncoding
        currentChannels = inputFormat.channelCount
        delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: java.nio.ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val remaining = buffer.remaining()
        if (remaining > 0 && currentChannels > 0) {
            // 🚨 强制使用 LITTLE_ENDIAN，彻底解决内存读反变 NaN 的死机 Bug！
            val readBuffer = buffer.asReadOnlyBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var sumSq = 0.0f
            var count = 0

            try {
                when (currentEncoding) {
                    androidx.media3.common.C.ENCODING_PCM_FLOAT -> {
                        val floatBuf = readBuffer.asFloatBuffer()
                        val frames = floatBuf.remaining() / currentChannels
                        for (i in 0 until frames) {
                            var l = floatBuf.get()
                            var r = if (currentChannels > 1) floatBuf.get() else l
                            if (currentChannels > 2) floatBuf.position(floatBuf.position() + currentChannels - 2)

                            if (l.isNaN() || l.isInfinite()) l = 0f
                            if (r.isNaN() || r.isInfinite()) r = 0f

                            filterStateL += alphaLpf * (l - filterStateL)
                            filterStateR += alphaLpf * (r - filterStateR)

                            val mono = (filterStateL + filterStateR) * 0.5f
                            sumSq += mono * mono
                            count++
                        }
                    }
                    androidx.media3.common.C.ENCODING_PCM_16BIT -> {
                        val shortBuf = readBuffer.asShortBuffer()
                        val frames = shortBuf.remaining() / currentChannels
                        for (i in 0 until frames) {
                            val l = shortBuf.get() / 32768f
                            val r = if (currentChannels > 1) shortBuf.get() / 32768f else l
                            if (currentChannels > 2) shortBuf.position(shortBuf.position() + currentChannels - 2)

                            filterStateL += alphaLpf * (l - filterStateL)
                            filterStateR += alphaLpf * (r - filterStateR)

                            val mono = (filterStateL + filterStateR) * 0.5f
                            sumSq += mono * mono
                            count++
                        }
                    }
                    androidx.media3.common.C.ENCODING_PCM_24BIT,
                    androidx.media3.common.C.ENCODING_PCM_32BIT -> {
                        val is24 = currentEncoding == androidx.media3.common.C.ENCODING_PCM_24BIT
                        val bytesPerSample = if (is24) 3 else 4
                        val maxVal = if (is24) 8388608f else 2147483648f
                        val frames = readBuffer.remaining() / (bytesPerSample * currentChannels)

                        for (i in 0 until frames) {
                            var intL = 0; var intR = 0
                            if (is24) {
                                val b1 = readBuffer.get().toInt() and 0xFF
                                val b2 = readBuffer.get().toInt() and 0xFF
                                val b3 = readBuffer.get().toInt()
                                intL = b1 or (b2 shl 8) or (b3 shl 16)
                                if (currentChannels > 1) {
                                    val b1R = readBuffer.get().toInt() and 0xFF
                                    val b2R = readBuffer.get().toInt() and 0xFF
                                    val b3R = readBuffer.get().toInt()
                                    intR = b1R or (b2R shl 8) or (b3R shl 16)
                                } else intR = intL
                            } else {
                                intL = readBuffer.getInt()
                                intR = if (currentChannels > 1) readBuffer.getInt() else intL
                            }
                            if (currentChannels > 2) readBuffer.position(readBuffer.position() + (currentChannels - 2) * bytesPerSample)

                            val sampleL = intL / maxVal
                            val sampleR = intR / maxVal

                            filterStateL += alphaLpf * (sampleL - filterStateL)
                            filterStateR += alphaLpf * (sampleR - filterStateR)

                            val mono = (filterStateL + filterStateR) * 0.5f
                            sumSq += mono * mono
                            count++
                        }
                    }
                }

                if (count > 0) {
                    val rms = kotlin.math.sqrt(sumSq / count).toFloat()
                    if (!rms.isNaN() && !rms.isInfinite()) {
                        VisualizerData.amplitude = rms // 完美抓取！
                    }
                }
            } catch (e: Exception) {
                // 静默护航，绝不阻碍音乐播放
            }
        }
        // 👇 数据原封不动交给真正的主水管，100% 绝对无损！
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
}