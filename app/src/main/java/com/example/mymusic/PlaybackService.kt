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

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        var audioSessionId: Int = 0
        var instance: PlaybackService? = null

        // ── [修复3] 对外暴露 Bit-perfect 状态，UI 可直接 collect 观察 ──
        private val _bitPerfectState = MutableStateFlow(false)
        val bitPerfectState: StateFlow<Boolean> = _bitPerfectState.asStateFlow()
    }

    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var isCurrentlyBitPerfect: Boolean = false

    // ── [修复2] 持有 player 引用，以便 Bit-perfect 状态变化时动态切换 Offload ──
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ── 1. 强开 32-bit Float 高精度输出 ──
        // [修复2] Float 输出和 Offload 互斥：Float 输出路径作为默认，
        //         Offload 仅在 Bit-perfect 关闭时启用（见 applyOffloadPreference）
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true) // 32bit 浮点，保护动态范围
                    .build()
            }
        }

        // ── 2. 高码率 FLAC/WAV 解码缓冲优化 ──
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50000,
                100000,
                2500,
                5000
            ).build()

        // ── 3. 空间音频 Spatializer ──
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
            .build()

        // ── 4. 构建 ExoPlayer ──
        val builtPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(loadControl)
            .build()

        player = builtPlayer
        audioSessionId = builtPlayer.audioSessionId

        // ── 5. 读取持久化设置，决定初始是否启用 Bit-perfect ──
        val prefs = getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val isBitPerfectEnabled = prefs.getBoolean("enable_bit_perfect", false)

        // [修复2] 先根据 Bit-perfect 开关决定 Offload 策略，再激活 Bit-perfect
        applyOffloadPreference(builtPlayer, enableOffload = !isBitPerfectEnabled)
        applyUsbBitPerfectSetting(isBitPerfectEnabled)

        // ── 6. 通知栏跳转 ──
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

        // ── 7. 定制媒体通知栏 ──
        val notificationProvider = object : DefaultMediaNotificationProvider(this) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showPauseButton: Boolean
            ): ImmutableList<CommandButton> {
                return ImmutableList.copyOf(
                    super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                        .filter {
                            it.playerCommand != Player.COMMAND_SEEK_FORWARD &&
                                    it.playerCommand != Player.COMMAND_SEEK_BACK
                        }
                )
            }
        }
        notificationProvider.setSmallIcon(R.drawable.ic_notification_logo)
        setMediaNotificationProvider(notificationProvider)
    }

    // ==========================================
    // [修复2] Float 输出与 Offload 互斥控制
    // Bit-perfect 开启时禁用 Offload（Offload 路径不支持 float PCM 直通）
    // Bit-perfect 关闭时恢复 Offload（节省功耗）
    // ==========================================
    private fun applyOffloadPreference(targetPlayer: ExoPlayer, enableOffload: Boolean) {
        val mode = if (enableOffload)
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        else
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED

        val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(mode)
            .setIsGaplessSupportRequired(enableOffload) // 无缝播放仅在 Offload 启用时有意义
            .build()

        targetPlayer.trackSelectionParameters = targetPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(offloadPreferences)
            .build()

        Log.d("Auralis", "Offload 模式: ${if (enableOffload) "已启用" else "已禁用（Bit-perfect 路径）"}")
    }

    // ==========================================
    // Android 14+ 专属：USB Bit-perfect 控制
    // [修复1] 新增 sampleRate / bitDepth 参数，动态匹配实际曲目规格
    //         而非硬编码 192kHz / 24bit
    // ==========================================

    /**
     * @param enable       是否启用 Bit-perfect 模式
     * @param sampleRate   当前曲目实际采样率（Hz），0 表示自动从 DAC 获取最优值
     * @param bitDepth     当前曲目实际位深，0 表示自动选择
     */
    fun applyUsbBitPerfectSetting(enable: Boolean, sampleRate: Int = 0, bitDepth: Int = 0) {
        if (Build.VERSION.SDK_INT < 34) {
            Log.w("Auralis", "USB Bit-perfect 需要 Android 14+，当前系统不支持")
            return
        }
        if (enable) {
            tryEnableUsbBitPerfect(sampleRate, bitDepth)
        } else {
            tryDisableUsbBitPerfect()
        }

        // [修复2] Bit-perfect 状态变化时同步调整 Offload
        player?.let { applyOffloadPreference(it, enableOffload = !isCurrentlyBitPerfect) }
    }

    private fun tryEnableUsbBitPerfect(requestedSampleRate: Int, requestedBitDepth: Int) {
        if (isCurrentlyBitPerfect || Build.VERSION.SDK_INT < 34) return

        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val usbDac = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: run {
                Log.w("Auralis", "未检测到 USB DAC，Bit-perfect 模式未激活")
                return
            }

            // ── [修复1] 动态选择采样率：优先用曲目实际值，其次用 DAC 支持的最高值 ──
            val targetSampleRate = resolveBestSampleRate(usbDac, requestedSampleRate)
            val targetEncoding = resolveBestEncoding(usbDac, requestedBitDepth)

            Log.d("Auralis", "Bit-perfect 目标规格：${targetSampleRate}Hz / ${encodingLabel(targetEncoding)}")

            val mixerAttributes = AudioMixerAttributes.Builder(
                android.media.AudioFormat.Builder()
                    .setSampleRate(targetSampleRate)
                    .setEncoding(targetEncoding)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()

            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build()

            val success = audioManager.setPreferredMixerAttributes(
                audioAttributes, usbDac, mixerAttributes
            )

            if (success) {
                isCurrentlyBitPerfect = true
                _bitPerfectState.value = true  // [修复3] 通知 UI
                Log.d("Auralis", "✅ USB Bit-perfect 已激活：${targetSampleRate}Hz / ${encodingLabel(targetEncoding)}")
            } else {
                Log.w("Auralis", "⚠️ setPreferredMixerAttributes 返回 false，DAC 可能不支持该规格")
            }

        } catch (e: Exception) {
            Log.e("Auralis", "激活 USB Bit-perfect 失败: ${e.message}")
        }
    }

    private fun tryDisableUsbBitPerfect() {
        if (!isCurrentlyBitPerfect || Build.VERSION.SDK_INT < 34) return

        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val usbDac = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }

            if (usbDac != null) {
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()

                audioManager.clearPreferredMixerAttributes(audioAttributes, usbDac)
            }

            isCurrentlyBitPerfect = false
            _bitPerfectState.value = false  // [修复3] 通知 UI
            Log.d("Auralis", "🛑 USB Bit-perfect 已关闭，恢复系统混音")

        } catch (e: Exception) {
            Log.e("Auralis", "关闭 USB Bit-perfect 失败: ${e.message}")
        }
    }

    // ── [修复1] 采样率决策：曲目实际值 → DAC 最高支持值 → 安全回退 48kHz ──
    private fun resolveBestSampleRate(usbDac: AudioDeviceInfo, requested: Int): Int {
        val dacRates = usbDac.sampleRates  // DAC 硬件支持的所有采样率
        if (dacRates.isEmpty()) {
            // DAC 没有上报支持列表，直接用请求值或 48kHz 回退
            return if (requested > 0) requested else 48000
        }

        // 如果曲目采样率 DAC 支持，直接用
        if (requested > 0 && dacRates.contains(requested)) return requested

        // 否则用 DAC 支持的最高采样率（最大化 Bit-perfect 质量）
        return dacRates.max()
    }

    // ── [修复1] 位深决策：曲目实际值映射到 AudioFormat Encoding ──
    private fun resolveBestEncoding(usbDac: AudioDeviceInfo, requestedBitDepth: Int): Int {
        val dacEncodings = usbDac.encodings

        // 按质量从高到低的候选列表
        val candidates = when {
            requestedBitDepth >= 32 -> listOf(
                android.media.AudioFormat.ENCODING_PCM_32BIT,
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            requestedBitDepth >= 24 -> listOf(
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            else -> listOf(
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                android.media.AudioFormat.ENCODING_PCM_FLOAT
            )
        }

        if (dacEncodings.isEmpty()) return candidates.first()

        // 找 DAC 支持的第一个候选
        return candidates.firstOrNull { dacEncodings.contains(it) } ?: android.media.AudioFormat.ENCODING_PCM_16BIT
    }

    private fun encodingLabel(encoding: Int): String = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_32BIT       -> "32bit"
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
        android.media.AudioFormat.ENCODING_PCM_FLOAT       -> "32bit Float"
        android.media.AudioFormat.ENCODING_PCM_16BIT       -> "16bit"
        else -> "unknown"
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