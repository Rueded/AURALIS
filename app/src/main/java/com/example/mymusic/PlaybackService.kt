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

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        var audioSessionId: Int = 0
        var instance: PlaybackService? = null
    }

    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var isCurrentlyBitPerfect: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ── 1. 强开 32-bit Float 高精度输出 (适配最新版 Media3 签名变更) ──
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean // 🚨 完美匹配新版 API 的命名，且去掉了 offload 参数
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true) // 核心：强制开启 32位浮点数输出，保护母带动态范围
                    .build()
            }
        }

        // ── 2. 新增：高码率 FLAC/WAV 解码缓冲优化 ──
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50000,  // 最小缓冲 50 秒
                100000, // 🚨 最大缓冲 100 秒（专为 Hi-Res 大体积无损文件防卡顿准备）
                2500,   // 播放所需最小缓冲
                5000    // 重新缓冲所需时间
            ).build()

        // ── 3. 新增：空间音频 (Spatializer) API 融合 ──
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO) // 🚨 智能多声道路由分配
            .build()

        // ── 4. 构建 ExoPlayer，注入所有超强配置 ──
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(loadControl) // 应用大缓冲控制
            .build()

        // ── 5. 配置 Audio Offload (硬件级别的无缝播放与低功耗直通) ──
        val audioOffloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true)
            .build()

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(audioOffloadPreferences)
            .build()

        // 保存 SessionId 供均衡器全局使用
        audioSessionId = player.audioSessionId

        // ── 6. 点击通知栏跳转回全屏播放器 ──
        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_PLAYER_FULLSCREEN"
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        // ── 7. 定制媒体通知栏 (保持极简) ──
        val notificationProvider = object : DefaultMediaNotificationProvider(this) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showPauseButton: Boolean
            ): ImmutableList<CommandButton> {
                val defaultButtons = super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                val filteredList = defaultButtons.filter {
                    it.playerCommand != Player.COMMAND_SEEK_FORWARD &&
                            it.playerCommand != Player.COMMAND_SEEK_BACK
                }
                return ImmutableList.copyOf(filteredList)
            }
        }
        notificationProvider.setSmallIcon(R.drawable.ic_notification_logo)
        setMediaNotificationProvider(notificationProvider)

        // ── 8. 初始化时读取并应用 USB 独占设置 ──
        val prefs = getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val isBitPerfectEnabled = prefs.getBoolean("enable_bit_perfect", false)
        applyUsbBitPerfectSetting(isBitPerfectEnabled)
    }

    // ==========================================
    // 🚀 Android 14+ 专属：USB Bit-perfect 源码直通控制
    // ==========================================
    fun applyUsbBitPerfectSetting(enable: Boolean) {
        if (Build.VERSION.SDK_INT < 34) return
        if (enable) tryEnableUsbBitPerfect() else tryDisableUsbBitPerfect()
    }

    private fun tryEnableUsbBitPerfect() {
        if (isCurrentlyBitPerfect || Build.VERSION.SDK_INT < 34) return

        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val usbDac = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }

            if (usbDac != null) {
                val mixerAttributes = AudioMixerAttributes.Builder(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(192000)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                    // 绕过 Android AudioFlinger，实现 Bit-perfect
                    .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                    .build()

                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()

                val success = audioManager.setPreferredMixerAttributes(
                    audioAttributes,
                    usbDac,
                    mixerAttributes
                )

                if (success) {
                    isCurrentlyBitPerfect = true
                    Log.d("Auralis", "🎶 USB Bit-perfect 模式已成功激活，独占输出中！")
                }
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
                isCurrentlyBitPerfect = false
                Log.d("Auralis", "🛑 已关闭 USB Bit-perfect，恢复 Android 系统混音。")
            }
        } catch (e: Exception) {
            Log.e("Auralis", "关闭 USB Bit-perfect 失败: ${e.message}")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        instance = null
        tryDisableUsbBitPerfect()
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}