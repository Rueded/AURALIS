package com.auralis.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.BitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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

        @Volatile
        var isCrossfading = false

        @Volatile
        var targetVolume = 1.0f
    }

    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var isCurrentlyBitPerfect: Boolean = false
    private var player: ExoPlayer? = null
    private var player2: ExoPlayer? = null
    private var isUsingPlayer2 = false
    private var isShadowPreloading = false
    private var preloadedNextIndex = -1

    private val crossfadeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val crossfadeCheckRunnable = object : Runnable {
        override fun run() {
            try {
                checkCrossfadeTransition()
            } catch (e: Exception) {
                Log.e("Auralis-Fade", "🔥 轮询引擎崩溃: ${e.message}", e)
            } finally {
                crossfadeHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isCrossfading = false
        isUsingPlayer2 = false
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                val originalSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    // ✨ 修复 1：开启系统底层变速代理，全面兼容 32-bit Float 高清输出
                    .setEnableAudioTrackPlaybackParams(true)
                    .build()
                return VisualizerInterceptingAudioSink(originalSink)
            }
        }

        val loadControl1 = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50000, 100000, 2500, 5000).build()

        val loadControl2 = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50000, 100000, 2500, 5000).build()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
            .build()

        player = createPlayer(renderersFactory, audioAttributes, loadControl1)
        player2 = createPlayer(renderersFactory, audioAttributes, loadControl2)

        val builtPlayer = player!!

        builtPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(sessionId: Int) {
                audioSessionId = sessionId
            }
        })
        player2?.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(sessionId: Int) {
                audioSessionId = sessionId
            }
        })

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
            .setCallback(CustomSessionCallback())
            .setBitmapLoader(LocalOnlyBitmapLoader())
            .build()

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

        crossfadeHandler.post(crossfadeCheckRunnable)
    }

    private fun createPlayer(
        renderersFactory: DefaultRenderersFactory,
        audioAttributes: AudioAttributes,
        loadControl: DefaultLoadControl
    ): ExoPlayer {
        return ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(loadControl)
            .build()
    }

    private fun checkCrossfadeTransition() {
        if (isCurrentlyBitPerfect) return

        val mainPlayer = if (isUsingPlayer2) player2 else player
        val shadowPlayer = if (isUsingPlayer2) player else player2

        if (mainPlayer == null || shadowPlayer == null) return
        if (!mainPlayer.isPlaying) return

        val prefs = getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val crossfadeSecs = prefs.getFloat("crossfade_duration", 0f)
        if (crossfadeSecs <= 0f) return

        val crossfadeMs = (crossfadeSecs * 1000).toLong()
        val duration = mainPlayer.duration
        val position = mainPlayer.currentPosition

        if (PlaybackService.isCrossfading) {
            if (duration <= 0 || position < duration - crossfadeMs - 2000) {
                PlaybackService.isCrossfading = false
            } else {
                return
            }
        }

        val timeRemaining = duration - position

        if (isShadowPreloading && preloadedNextIndex != mainPlayer.nextMediaItemIndex) {
            isShadowPreloading = false
            shadowPlayer.stop()
            shadowPlayer.clearMediaItems()
        }

        val preloadTriggerMs = crossfadeMs + 15000L
        if (timeRemaining <= preloadTriggerMs && timeRemaining > crossfadeMs && mainPlayer.hasNextMediaItem() && !isShadowPreloading) {
            Log.i("Auralis-Fade", "⏳ 提前缓冲！剩余时间: ${timeRemaining}ms，开始加载下一首")
            isShadowPreloading = true
            preloadedNextIndex = mainPlayer.nextMediaItemIndex

            val items = mutableListOf<androidx.media3.common.MediaItem>()
            for (i in 0 until mainPlayer.mediaItemCount) {
                items.add(mainPlayer.getMediaItemAt(i))
            }

            shadowPlayer.repeatMode = mainPlayer.repeatMode
            shadowPlayer.shuffleModeEnabled = mainPlayer.shuffleModeEnabled

            // ✨ 修复 2：让淡入淡出的影子播放器无缝继承哥哥当前的倍速/音调
            shadowPlayer.playbackParameters = mainPlayer.playbackParameters

            shadowPlayer.setMediaItems(items, preloadedNextIndex, 0L)
            shadowPlayer.setAudioAttributes(shadowPlayer.audioAttributes, true)
            shadowPlayer.volume = 0f
            shadowPlayer.prepare()
            shadowPlayer.pause()
            return
        }

        if (timeRemaining > 0 && timeRemaining <= crossfadeMs && mainPlayer.hasNextMediaItem() && !PlaybackService.isCrossfading) {
            PlaybackService.isCrossfading = true
            isShadowPreloading = false
            val startVolume = mainPlayer.volume

            val startCrossfadeRunnable = Runnable {
                Log.i("Auralis-Fade", "🔀 准时启动 ${crossfadeSecs}s 淡入淡出！")
                isUsingPlayer2 = !isUsingPlayer2

                // Start shadow player first to avoid isPlaying flicker in UI
                shadowPlayer.volume = 0f
                shadowPlayer.play()

                crossfadeHandler.postDelayed({
                    mediaSession?.player = shadowPlayer

                    val currentIndex = mainPlayer.currentMediaItemIndex
                    if (currentIndex + 1 < mainPlayer.mediaItemCount) {
                        mainPlayer.removeMediaItems(currentIndex + 1, mainPlayer.mediaItemCount)
                    }
                    mainPlayer.repeatMode = Player.REPEAT_MODE_OFF
                    mainPlayer.setAudioAttributes(mainPlayer.audioAttributes, false)

                    startCrossfade(mainPlayer, shadowPlayer, crossfadeMs, startVolume)
                }, 50)
            }

            if (shadowPlayer.playbackState == Player.STATE_READY) {
                Log.i("Auralis-Fade", "⚡ 预加载成功，完美秒切！")
                startCrossfadeRunnable.run()
            } else {
                if (shadowPlayer.playbackState == Player.STATE_IDLE) {
                    val items = mutableListOf<androidx.media3.common.MediaItem>()
                    for (i in 0 until mainPlayer.mediaItemCount) {
                        items.add(mainPlayer.getMediaItemAt(i))
                    }
                    shadowPlayer.setMediaItems(items, mainPlayer.nextMediaItemIndex, 0L)
                    shadowPlayer.prepare()
                }
                shadowPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            shadowPlayer.removeListener(this)
                            startCrossfadeRunnable.run()
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        shadowPlayer.removeListener(this)
                        PlaybackService.isCrossfading = false
                    }
                })
            }
        }
    }

    private fun startCrossfade(fadeOutPlayer: ExoPlayer, fadeInPlayer: ExoPlayer, durationMs: Long, startVolume: Float) {
        val startTime = System.currentTimeMillis()
        val fadeHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fadeHandler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)

                val smoothProgress = progress * progress * (3 - 2 * progress)

                fadeOutPlayer.volume = startVolume * (1f - smoothProgress)
                fadeInPlayer.volume = targetVolume * smoothProgress

                if (progress < 1f) {
                    fadeHandler.postDelayed(this, 16)
                } else {
                    Log.i("Auralis-Fade", "🏁 渐变完美结束，打扫战场")
                    fadeInPlayer.volume = targetVolume

                    fadeOutPlayer.pause()
                    fadeOutPlayer.clearMediaItems()
                    fadeOutPlayer.setAudioAttributes(fadeOutPlayer.audioAttributes, true)
                    fadeOutPlayer.volume = targetVolume

                    PlaybackService.isCrossfading = false
                }
            }
        })
    }

    private inner class CustomSessionCallback : MediaSession.Callback

    private fun applyOffloadPreference(targetPlayer: ExoPlayer, enableOffload: Boolean) {
        val mode = if (enableOffload)
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        else
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED

        val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(mode)
            .setIsGaplessSupportRequired(enableOffload)
            .build()

        targetPlayer.trackSelectionParameters = targetPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(offloadPreferences)
            .build()

        Log.d("Auralis", "Offload 模式: ${if (enableOffload) "已启用" else "已禁用（Bit-perfect 路径）"}")
    }

    fun applyUsbBitPerfectSetting(enable: Boolean, sampleRate: Int = 0, bitDepth: Int = 0) {
        if (Build.VERSION.SDK_INT < 34) return
        if (enable) {
            tryEnableUsbBitPerfect(sampleRate, bitDepth)
            // ✨ 强刷防护：一旦独占开启，强制将主副播放器的速率拉回原生 1.0x，杜绝硬件直通下的音频冲突
            player?.playbackParameters = PlaybackParameters.DEFAULT
            player2?.playbackParameters = PlaybackParameters.DEFAULT
        } else {
            tryDisableUsbBitPerfect()
        }
        player?.let { applyOffloadPreference(it, enableOffload = false) }
    }

    private fun tryEnableUsbBitPerfect(requestedSampleRate: Int, requestedBitDepth: Int) {
        if (isCurrentlyBitPerfect || Build.VERSION.SDK_INT < 34) return
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val usbDac = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: return

            val targetSampleRate = resolveBestSampleRate(usbDac, requestedSampleRate)
            val targetEncoding = resolveBestEncoding(usbDac, requestedBitDepth)

            val mixerAttributes = AudioMixerAttributes.Builder(
                android.media.AudioFormat.Builder()
                    .setSampleRate(targetSampleRate)
                    .setEncoding(targetEncoding)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()

            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build()

            val success = audioManager.setPreferredMixerAttributes(audioAttributes, usbDac, mixerAttributes)
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

            if (success) {
                isCurrentlyBitPerfect = true
                _bitPerfectState.value = true
                mainHandler.post {
                    android.widget.Toast.makeText(
                        this,
                        "DAC 独占已激活: ${targetSampleRate / 1000.0}kHz",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                mainHandler.post {
                    android.widget.Toast.makeText(
                        this,
                        "DAC 独占失败: 设备不支持该规格",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
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
            }
            isCurrentlyBitPerfect = false
            _bitPerfectState.value = false
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "已恢复 Android 系统混音", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("Auralis", "关闭 USB Bit-perfect 失败: ${e.message}")
        }
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
        return candidates.firstOrNull { dacEncodings.contains(it) }
            ?: android.media.AudioFormat.ENCODING_PCM_16BIT
    }

    /**
     * BitmapLoader that only loads embedded artwork bytes.
     * Blocks all URI/network requests so the notification never fires a network call.
     */
    @UnstableApi
    private inner class LocalOnlyBitmapLoader : BitmapLoader {
        override fun supportsMimeType(mimeType: String) = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            Futures.immediateFuture(
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
            )

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
            Futures.immediateFailedFuture(
                UnsupportedOperationException("Auralis: network artwork blocked in notification")
            )

        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
            val data = metadata.artworkData ?: return null
            return decodeBitmap(data)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        instance = null
        crossfadeHandler.removeCallbacks(crossfadeCheckRunnable)
        tryDisableUsbBitPerfect()
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player2?.release()
        player = null
        player2 = null
        super.onDestroy()
    }
}

data class AudioQualityAnalysis(
    val detectedCutoffHz: Int,
    val estimatedSource: String,
    val highFreqEnergyRatio: Float,
    val isSuspect: Boolean
)

object VisualizerData {
    @Volatile var amplitude: Float = 0f

    /** 128-band FFT magnitude spectrum, updated ~every 100ms */
    val fftBands = FloatArray(128)

    /** Null until first analysis completes */
    @Volatile var qualityAnalysis: AudioQualityAnalysis? = null
}

@UnstableApi
class VisualizerInterceptingAudioSink(
    private val delegate: androidx.media3.exoplayer.audio.AudioSink
) : androidx.media3.exoplayer.audio.AudioSink by delegate {

    private var currentEncoding = androidx.media3.common.C.ENCODING_INVALID
    private var currentChannels = 2
    private var currentSampleRate = 44100

    // Low-pass filter state (for RMS amplitude)
    private var filterStateL = 0.0f
    private var filterStateR = 0.0f
    private val alphaLpf = 0.15f

    // FFT accumulation buffer (4096 samples, power of 2)
    private val FFT_SIZE = 4096
    private val fftAccum = FloatArray(FFT_SIZE)
    private var fftAccumPos = 0

    // Background executor for FFT (single thread, non-blocking)
    private val fftExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Auralis-FFT").also { it.isDaemon = true }
    }

    override fun configure(
        inputFormat: androidx.media3.common.Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        currentEncoding   = inputFormat.pcmEncoding
        currentChannels   = inputFormat.channelCount
        currentSampleRate = if (inputFormat.sampleRate > 0) inputFormat.sampleRate else 44100
        delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: java.nio.ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val remaining = buffer.remaining()
        if (remaining > 0 && currentChannels > 0) {
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
                            accumulateMono(mono)
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
                            accumulateMono(mono)
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
                            if (currentChannels > 2)
                                readBuffer.position(readBuffer.position() + (currentChannels - 2) * bytesPerSample)
                            val sampleL = intL / maxVal
                            val sampleR = intR / maxVal
                            filterStateL += alphaLpf * (sampleL - filterStateL)
                            filterStateR += alphaLpf * (sampleR - filterStateR)
                            val mono = (filterStateL + filterStateR) * 0.5f
                            sumSq += mono * mono
                            count++
                            accumulateMono(mono)
                        }
                    }
                }

                if (count > 0) {
                    val rms = kotlin.math.sqrt(sumSq / count).toFloat()
                    if (!rms.isNaN() && !rms.isInfinite()) {
                        VisualizerData.amplitude = rms
                    }
                }
            } catch (e: Exception) { /* silent */ }
        }
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    /** Accumulate mono samples; trigger async FFT when buffer is full */
    private fun accumulateMono(sample: Float) {
        fftAccum[fftAccumPos++] = sample
        if (fftAccumPos >= FFT_SIZE) {
            fftAccumPos = 0
            val snapshot = fftAccum.copyOf()
            val sr = currentSampleRate
            fftExecutor.submit {
                try {
                    val spectrum = computeFFT(snapshot)
                    updateFftBands(spectrum, sr)
                    // Run quality analysis only occasionally (not every buffer)
                    if (shouldRunQualityAnalysis()) {
                        VisualizerData.qualityAnalysis = analyzeQuality(spectrum, sr)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private var qualityAnalysisCounter = 0
    private fun shouldRunQualityAnalysis(): Boolean {
        qualityAnalysisCounter++
        // Run every 20th FFT (~every 2 seconds at 44.1kHz)
        return qualityAnalysisCounter % 20 == 0
    }

    /**
     * Cooley-Tukey radix-2 in-place FFT.
     * Returns magnitude spectrum of length n/2.
     */
    private fun computeFFT(pcm: FloatArray): FloatArray {
        val n = pcm.size  // must be power of 2
        // Apply Hann window
        val re = FloatArray(n) { i ->
            pcm[i] * (0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * i / (n - 1)).toFloat())
        }
        val im = FloatArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }

        // FFT butterfly
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val ang = -2.0 * Math.PI / len
            val wRe = kotlin.math.cos(ang).toFloat()
            val wIm = kotlin.math.sin(ang).toFloat()
            var k = 0
            while (k < n) {
                var curRe = 1f; var curIm = 0f
                for (l in 0 until halfLen) {
                    val uRe = re[k + l]; val uIm = im[k + l]
                    val vRe = re[k + l + halfLen] * curRe - im[k + l + halfLen] * curIm
                    val vIm = re[k + l + halfLen] * curIm + im[k + l + halfLen] * curRe
                    re[k + l] = uRe + vRe; im[k + l] = uIm + vIm
                    re[k + l + halfLen] = uRe - vRe; im[k + l + halfLen] = uIm - vIm
                    val newCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe; curRe = newCurRe
                }
                k += len
            }
            len *= 2
        }

        // Magnitude
        return FloatArray(n / 2) { i ->
            kotlin.math.sqrt((re[i] * re[i] + im[i] * im[i]).toDouble()).toFloat() / (n / 2)
        }
    }

    /**
     * Compress the full spectrum into 128 logarithmically-spaced bands
     * and write into VisualizerData.fftBands[].
     */
    private fun updateFftBands(spectrum: FloatArray, sampleRate: Int) {
        val numBands = 128
        val maxBin = spectrum.size
        val nyquist = sampleRate / 2.0
        val minFreq = 20.0
        val maxFreq = nyquist

        for (band in 0 until numBands) {
            val freqLow  = minFreq * (maxFreq / minFreq).pow(band.toDouble() / numBands)
            val freqHigh = minFreq * (maxFreq / minFreq).pow((band + 1.0) / numBands)
            val binLow  = ((freqLow  / nyquist) * maxBin).toInt().coerceIn(0, maxBin - 1)
            val binHigh = ((freqHigh / nyquist) * maxBin).toInt().coerceIn(binLow + 1, maxBin)
            val avg = spectrum.slice(binLow until binHigh).average().toFloat()
            // Smooth with previous value
            VisualizerData.fftBands[band] =
                VisualizerData.fftBands[band] * 0.6f + avg * 0.4f
        }
    }

    private fun Double.pow(exp: Double) = Math.pow(this, exp)

    private fun analyzeQuality(spectrum: FloatArray, sampleRate: Int): AudioQualityAnalysis {
        val nyquist = sampleRate / 2
        val binHz = nyquist.toFloat() / spectrum.size

        val totalEnergy = spectrum.sumOf { (it * it).toDouble() }.toFloat().coerceAtLeast(1e-10f)

        val bin16k = (16000 / binHz).toInt().coerceIn(0, spectrum.size - 1)
        val bin20k = (20000 / binHz).toInt().coerceIn(0, spectrum.size - 1)
        val highFreqEnergy = spectrum.slice(bin16k..bin20k).sumOf { (it * it).toDouble() }.toFloat()
        val ratio = highFreqEnergy / totalEnergy

        // Find cutoff: scan from high freq down to first bin with significant energy
        var cutoffHz = nyquist
        val noiseFloor = totalEnergy * 0.00001f
        for (i in bin20k downTo 0) {
            if (spectrum[i] * spectrum[i] > noiseFloor) {
                cutoffHz = (i * binHz).toInt()
                break
            }
        }

        val isSuspect = cutoffHz < 19000 && ratio < 0.0005f
        val estimatedSource = when {
            cutoffHz >= 20000 -> "真实高清音频 ✓"
            cutoffHz >= 19500 -> "可能是 MP3 320kbps 转制"
            cutoffHz >= 18000 -> "疑似 MP3 256kbps 转制"
            cutoffHz >= 16000 -> "疑似 MP3 128~192kbps 转制"
            cutoffHz >= 14000 -> "疑似 AAC / 低码率转制"
            else              -> "疑似极低码率来源"
        }

        return AudioQualityAnalysis(cutoffHz, estimatedSource, ratio, isSuspect)
    }
}
