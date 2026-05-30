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
import androidx.media3.common.MediaItem
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
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
@UnstableApi
class PlaybackService : MediaLibraryService() {

    // ─────────────────────────────────────────────────────────
    // Companion
    // ─────────────────────────────────────────────────────────

    companion object {
        var audioSessionId: Int = 0
        var instance: PlaybackService? = null

        private val _bitPerfectState = MutableStateFlow(false)
        val bitPerfectState: StateFlow<Boolean> = _bitPerfectState.asStateFlow()

        private val _audioSessionIdState = MutableStateFlow(0)
        val audioSessionIdState: StateFlow<Int> = _audioSessionIdState.asStateFlow()

        @Volatile var isCrossfading = false
        @Volatile var targetVolume = 1.0f

        // Android Auto 浏览树节点 ID
        const val ROOT_ID          = "root"
        const val NODE_ALL         = "node_all"
        const val NODE_FAVORITES   = "node_favorites"
        const val NODE_RECENT      = "node_recent"
        const val NODE_PLAYLISTS   = "node_playlists"
    }

    // ─────────────────────────────────────────────────────────
    // 字段
    // ─────────────────────────────────────────────────────────

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var audioManager: AudioManager
    private var isCurrentlyBitPerfect: Boolean = false

    internal var player: ExoPlayer? = null
    internal var player2: ExoPlayer? = null
    internal var isUsingPlayer2 = false

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var isShadowPreloading = false
    private var preloadedNextIndex = -1

    // EQ
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var bassBoost: android.media.audiofx.BassBoost? = null
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

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

    // ─────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────

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

        player  = createPlayer(renderersFactory, audioAttributes, loadControl1)
        player2 = createPlayer(renderersFactory, audioAttributes, loadControl2)

        val builtPlayer = player!!

        builtPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(sessionId: Int) {
                audioSessionId = sessionId
                _audioSessionIdState.value = sessionId
                initEqualizer(sessionId) // ✅ EQ 跟随 session 重绑
            }
        })
        player2?.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(sessionId: Int) {
                audioSessionId = sessionId
                _audioSessionIdState.value = sessionId
            }
        })

        audioSessionId = builtPlayer.audioSessionId
        _audioSessionIdState.value = audioSessionId

        // 初始化 EQ
        initEqualizer(audioSessionId)

        val prefs = getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        applyOffloadPreference(builtPlayer, enableOffload = false)
        applyUsbBitPerfectSetting(prefs.getBoolean("enable_bit_perfect", false))

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_PLAYER_FULLSCREEN"
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ✅ MediaLibrarySession（兼容 Android Auto）
        mediaSession = MediaLibrarySession.Builder(this, builtPlayer, CustomSessionCallback())
            .setSessionActivity(sessionActivityPendingIntent)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        instance = null
        crossfadeHandler.removeCallbacks(crossfadeCheckRunnable)
        tryDisableUsbBitPerfect()
        // 释放 EQ
        equalizer?.release(); equalizer = null
        bassBoost?.release(); bassBoost = null
        loudnessEnhancer?.release(); loudnessEnhancer = null
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player2?.release()
        player = null
        player2 = null
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────
    // Android Auto 浏览树回调
    // ─────────────────────────────────────────────────────────

    private inner class CustomSessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(buildBrowsableItem(ROOT_ID, "Auralis", null), params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                try {
                    val items: List<MediaItem> = when (parentId) {
                        ROOT_ID        -> buildRootChildren()
                        NODE_ALL       -> buildAllSongs()
                        NODE_FAVORITES -> buildFavorites()
                        NODE_RECENT    -> buildRecentSongs()
                        NODE_PLAYLISTS -> buildPlaylistNodes()
                        else -> {
                            if (parentId.startsWith("playlist_")) {
                                val id = parentId.removePrefix("playlist_").toLongOrNull()
                                if (id != null) buildPlaylistSongs(id) else emptyList()
                            } else emptyList()
                        }
                    }
                    LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                } catch (e: Exception) {
                    Log.e("PlaybackService", "Auto 浏览树加载失败: ${e.message}")
                    LibraryResult.ofItemList(ImmutableList.of(), params)
                }
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return serviceScope.future {
                val dao = AppDatabase.getDatabase(this@PlaybackService).songDao()
                val song = dao.getSongByPath(mediaId)
                if (song != null) LibraryResult.ofItem(buildPlayableItem(song), null)
                else LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Auto 浏览树构建
    // ─────────────────────────────────────────────────────────

    private fun buildRootChildren(): List<MediaItem> = listOf(
        buildBrowsableItem(NODE_ALL,       "全部歌曲",    null),
        buildBrowsableItem(NODE_FAVORITES, "❤️ 红心收藏",  null),
        buildBrowsableItem(NODE_RECENT,    "最近常听",    null),
        buildBrowsableItem(NODE_PLAYLISTS, "我的歌单",    null)
    )

    private suspend fun buildAllSongs(): List<MediaItem> =
        AppDatabase.getDatabase(this).songDao().getAllSongs().first().map { buildPlayableItem(it) }

    private suspend fun buildFavorites(): List<MediaItem> =
        AppDatabase.getDatabase(this).songDao().getFavoriteSongs().first().map { buildPlayableItem(it) }

    private suspend fun buildRecentSongs(): List<MediaItem> =
        AppDatabase.getDatabase(this).songDao().getMostPlayedSongs().first().map { buildPlayableItem(it) }

    private suspend fun buildPlaylistNodes(): List<MediaItem> =
        AppDatabase.getDatabase(this).songDao().getAllPlaylists().first().map { pl ->
            buildBrowsableItem("playlist_${pl.id}", pl.name, null)
        }

    private suspend fun buildPlaylistSongs(playlistId: Long): List<MediaItem> =
        AppDatabase.getDatabase(this).songDao().getSongsInPlaylist(playlistId).first().map { buildPlayableItem(it) }

    private fun buildPlayableItem(song: Song): MediaItem {
        val artworkUri = Uri.parse(
            "auralis://cover?path=" + URLEncoder.encode(song.data, "UTF-8")
        )
        return MediaItem.Builder()
            .setMediaId(song.data)
            .setUri(song.data)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            ).build()
    }

    private fun buildBrowsableItem(id: String, title: String, subtitle: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            ).build()

    // ─────────────────────────────────────────────────────────
    // Equalizer
    // ─────────────────────────────────────────────────────────

    fun initEqualizer(sessionId: Int) {
        if (sessionId == 0) return
        try {
            equalizer?.release()
            equalizer = android.media.audiofx.Equalizer(0, sessionId).apply { enabled = true }
            bassBoost?.release()
            bassBoost = android.media.audiofx.BassBoost(0, sessionId).apply { enabled = true }
            loudnessEnhancer?.release()
            loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(sessionId).apply { enabled = true }
            restoreEqSettings()
            Log.d("PlaybackService", "✅ EQ 初始化成功 sessionId=$sessionId")
        } catch (e: Exception) {
            Log.e("PlaybackService", "EQ 初始化失败: ${e.message}")
        }
    }

    private fun restoreEqSettings() {
        val prefs = getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val eq = equalizer ?: return

        val eqEnabled = prefs.getBoolean("eq_enabled", false)
        eq.enabled = eqEnabled
        if (eqEnabled) {
            val saved = prefs.getString("eq_bands", null)
            if (saved != null) {
                val levels = saved.split(",")
                val numBands = eq.numberOfBands.toInt()
                for (i in 0 until minOf(levels.size, numBands)) {
                    try { eq.setBandLevel(i.toShort(), levels[i].trim().toShort()) } catch (_: Exception) {}
                }
            }
        }

        val bbEnabled = prefs.getBoolean("bass_boost_enabled", false)
        bassBoost?.enabled = bbEnabled
        if (bbEnabled) {
            try { bassBoost?.setStrength(prefs.getInt("bass_boost_strength", 500).toShort()) } catch (_: Exception) {}
        }

        val ldEnabled = prefs.getBoolean("loudness_enabled", false)
        loudnessEnhancer?.enabled = ldEnabled
        if (ldEnabled) {
            try { loudnessEnhancer?.setTargetGain(prefs.getInt("loudness_gain", 0)) } catch (_: Exception) {}
        }
    }

    /**
     * 外部（UI 层）调用：应用 EQ 设置并持久化
     * @param bandLevels 各频段增益，单位 milliBel，例如 [0, 300, -200, 100, 0]
     */
    fun applyEqSettings(enabled: Boolean, bandLevels: ShortArray) {
        getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("eq_enabled", enabled)
            .putString("eq_bands", bandLevels.joinToString(","))
            .apply()
        val eq = equalizer ?: return
        eq.enabled = enabled
        if (enabled) {
            val numBands = eq.numberOfBands.toInt()
            for (i in 0 until minOf(bandLevels.size, numBands)) {
                try { eq.setBandLevel(i.toShort(), bandLevels[i]) } catch (_: Exception) {}
            }
        }
    }

    fun applyBassBoost(enabled: Boolean, strength: Short) {
        getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("bass_boost_enabled", enabled)
            .putInt("bass_boost_strength", strength.toInt())
            .apply()
        bassBoost?.enabled = enabled
        if (enabled) try { bassBoost?.setStrength(strength) } catch (_: Exception) {}
    }

    fun applyLoudnessEnhancer(enabled: Boolean, gainMb: Int) {
        getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("loudness_enabled", enabled)
            .putInt("loudness_gain", gainMb)
            .apply()
        loudnessEnhancer?.enabled = enabled
        if (enabled) try { loudnessEnhancer?.setTargetGain(gainMb) } catch (_: Exception) {}
    }

    /** 供 EQ UI 读取：频段数、当前各频段电平、频段增益范围 */
    fun getEqualizerInfo(): Triple<Int, ShortArray, IntArray>? {
        val eq = equalizer ?: return null
        return try {
            val numBands = eq.numberOfBands.toInt()
            val levels = ShortArray(numBands) { eq.getBandLevel(it.toShort()) }
            val range = eq.getBandLevelRange() // [min, max] in milliBel
            Triple(numBands, levels, intArrayOf(range[0].toInt(), range[1].toInt()))
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────
    // Crossfade
    // ─────────────────────────────────────────────────────────

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
        val mainPlayer   = if (isUsingPlayer2) player2 else player
        val shadowPlayer = if (isUsingPlayer2) player  else player2
        if (mainPlayer == null || shadowPlayer == null) return
        if (!mainPlayer.isPlaying) return

        val prefs = getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val crossfadeSecs = prefs.getFloat("crossfade_duration", 0f)
        if (crossfadeSecs <= 0f) return

        val crossfadeMs   = (crossfadeSecs * 1000).toLong()
        val duration      = mainPlayer.duration
        val position      = mainPlayer.currentPosition

        if (isCrossfading) {
            if (duration <= 0 || position < duration - crossfadeMs - 2000) isCrossfading = false
            else return
        }

        val timeRemaining = duration - position

        if (isShadowPreloading && preloadedNextIndex != mainPlayer.nextMediaItemIndex) {
            isShadowPreloading = false
            shadowPlayer.stop()
            shadowPlayer.clearMediaItems()
        }

        val preloadTriggerMs = crossfadeMs + 15000L
        if (timeRemaining <= preloadTriggerMs && timeRemaining > crossfadeMs &&
            mainPlayer.hasNextMediaItem() && !isShadowPreloading) {
            Log.i("Auralis-Fade", "⏳ 提前缓冲，剩余: ${timeRemaining}ms")
            isShadowPreloading = true
            preloadedNextIndex = mainPlayer.nextMediaItemIndex
            val items = (0 until mainPlayer.mediaItemCount).map { mainPlayer.getMediaItemAt(it) }
            shadowPlayer.repeatMode          = mainPlayer.repeatMode
            shadowPlayer.shuffleModeEnabled  = mainPlayer.shuffleModeEnabled
            shadowPlayer.playbackParameters  = mainPlayer.playbackParameters
            shadowPlayer.setMediaItems(items, preloadedNextIndex, 0L)
            shadowPlayer.setAudioAttributes(shadowPlayer.audioAttributes, true)
            shadowPlayer.volume = 0f
            shadowPlayer.prepare()
            shadowPlayer.pause()
            return
        }

        if (timeRemaining > 0 && timeRemaining <= crossfadeMs &&
            mainPlayer.hasNextMediaItem() && !isCrossfading) {
            isCrossfading = true
            isShadowPreloading = false
            val startVolume = mainPlayer.volume

            val startCrossfadeRunnable = Runnable {
                Log.i("Auralis-Fade", "🔀 启动 ${crossfadeSecs}s 淡入淡出")
                mainPlayer.setAudioAttributes(mainPlayer.audioAttributes, false)
                shadowPlayer.setAudioAttributes(shadowPlayer.audioAttributes, true)
                isUsingPlayer2 = !isUsingPlayer2
                shadowPlayer.volume = 0f
                shadowPlayer.play()
                crossfadeHandler.postDelayed({
                    mediaSession?.player = shadowPlayer
                    val idx = mainPlayer.currentMediaItemIndex
                    if (idx + 1 < mainPlayer.mediaItemCount)
                        mainPlayer.removeMediaItems(idx + 1, mainPlayer.mediaItemCount)
                    mainPlayer.repeatMode = Player.REPEAT_MODE_OFF
                    startCrossfade(mainPlayer, shadowPlayer, crossfadeMs, startVolume)
                }, 50)
            }

            if (shadowPlayer.playbackState == Player.STATE_READY) {
                Log.i("Auralis-Fade", "⚡ 预加载命中，完美秒切")
                startCrossfadeRunnable.run()
            } else {
                if (shadowPlayer.playbackState == Player.STATE_IDLE) {
                    val items = (0 until mainPlayer.mediaItemCount).map { mainPlayer.getMediaItemAt(it) }
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
                        isCrossfading = false
                    }
                })
            }
        }
    }

    private fun startCrossfade(
        fadeOutPlayer: ExoPlayer, fadeInPlayer: ExoPlayer,
        durationMs: Long, startVolume: Float
    ) {
        val startTime   = System.currentTimeMillis()
        val fadeHandler = android.os.Handler(android.os.Looper.getMainLooper())
        fadeHandler.post(object : Runnable {
            override fun run() {
                val elapsed  = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                val smooth   = progress * progress * (3 - 2 * progress)
                fadeOutPlayer.volume = startVolume * (1f - smooth)
                fadeInPlayer.volume  = targetVolume * smooth
                if (progress < 1f) {
                    fadeHandler.postDelayed(this, 16)
                } else {
                    Log.i("Auralis-Fade", "🏁 渐变结束")
                    fadeInPlayer.volume = targetVolume
                    fadeOutPlayer.pause()
                    fadeOutPlayer.clearMediaItems()
                    fadeOutPlayer.setAudioAttributes(fadeOutPlayer.audioAttributes, true)
                    fadeOutPlayer.volume = targetVolume
                    isCrossfading = false
                }
            }
        })
    }

    // ─────────────────────────────────────────────────────────
    // USB Bit-Perfect
    // ─────────────────────────────────────────────────────────

    private fun applyOffloadPreference(targetPlayer: ExoPlayer, enableOffload: Boolean) {
        val mode = if (enableOffload)
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        else
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        targetPlayer.trackSelectionParameters = targetPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(mode)
                    .setIsGaplessSupportRequired(enableOffload)
                    .build()
            ).build()
    }

    fun applyUsbBitPerfectSetting(enable: Boolean, sampleRate: Int = 0, bitDepth: Int = 0) {
        if (Build.VERSION.SDK_INT < 34) return
        if (enable) {
            tryEnableUsbBitPerfect(sampleRate, bitDepth)
            player?.playbackParameters  = PlaybackParameters.DEFAULT
            player2?.playbackParameters = PlaybackParameters.DEFAULT
        } else {
            tryDisableUsbBitPerfect()
        }
        player?.let { applyOffloadPreference(it, enableOffload = false) }
    }

    private fun tryEnableUsbBitPerfect(requestedSampleRate: Int, requestedBitDepth: Int) {
        if (isCurrentlyBitPerfect || Build.VERSION.SDK_INT < 34) return
        try {
            val usbDac = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: return

            val targetSampleRate = resolveBestSampleRate(usbDac, requestedSampleRate)
            val targetEncoding   = resolveBestEncoding(usbDac, requestedBitDepth)

            val mixerAttributes = AudioMixerAttributes.Builder(
                android.media.AudioFormat.Builder()
                    .setSampleRate(targetSampleRate)
                    .setEncoding(targetEncoding)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()

            val audioAttr = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()

            val ok = audioManager.setPreferredMixerAttributes(audioAttr, usbDac, mixerAttributes)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (ok) {
                    isCurrentlyBitPerfect = true; _bitPerfectState.value = true
                    android.widget.Toast.makeText(this, "DAC 独占已激活: ${targetSampleRate / 1000.0}kHz", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "DAC 独占失败: 设备不支持该规格", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("Auralis", "激活 USB Bit-perfect 失败: ${e.message}")
        }
    }

    private fun tryDisableUsbBitPerfect() {
        if (!isCurrentlyBitPerfect || Build.VERSION.SDK_INT < 34) return
        try {
            val usbDac = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (usbDac != null) {
                val audioAttr = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
                audioManager.clearPreferredMixerAttributes(audioAttr, usbDac)
            }
            isCurrentlyBitPerfect = false; _bitPerfectState.value = false
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "已恢复 Android 系统混音", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("Auralis", "关闭 USB Bit-perfect 失败: ${e.message}")
        }
    }

    private fun resolveBestSampleRate(usbDac: AudioDeviceInfo, requested: Int): Int {
        val rates = usbDac.sampleRates
        if (rates.isEmpty()) return if (requested > 0) requested else 48000
        if (requested > 0 && rates.contains(requested)) return requested
        return rates.maxOrNull() ?: 48000
    }

    private fun resolveBestEncoding(usbDac: AudioDeviceInfo, requestedBitDepth: Int): Int {
        val encodings = usbDac.encodings
        val candidates = when {
            requestedBitDepth >= 32 -> listOf(
                android.media.AudioFormat.ENCODING_PCM_32BIT,
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                android.media.AudioFormat.ENCODING_PCM_16BIT)
            requestedBitDepth >= 24 -> listOf(
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                android.media.AudioFormat.ENCODING_PCM_16BIT)
            else -> listOf(
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                android.media.AudioFormat.ENCODING_PCM_FLOAT)
        }
        if (encodings.isEmpty()) return candidates.first()
        return candidates.firstOrNull { encodings.contains(it) }
            ?: android.media.AudioFormat.ENCODING_PCM_16BIT
    }

    // ─────────────────────────────────────────────────────────
    // BitmapLoader（通知栏封面）
    // ─────────────────────────────────────────────────────────

    @UnstableApi
    private inner class LocalOnlyBitmapLoader : BitmapLoader {
        private val defaultLoader = androidx.media3.session.SimpleBitmapLoader()

        override fun supportsMimeType(mimeType: String) = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            Futures.immediateFuture(
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
            )

        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
            return when {
                metadata.artworkData != null -> decodeBitmap(metadata.artworkData!!)
                metadata.artworkUri  != null -> loadBitmap(metadata.artworkUri!!)
                else -> null
            }
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            val uriStr = uri.toString()
            val future = com.google.common.util.concurrent.SettableFuture.create<Bitmap>()

            serviceScope.launch {
                var finalBitmap: Bitmap? = null
                try {
                    when {
                        uriStr.startsWith("content://") -> {
                            try {
                                contentResolver.openInputStream(uri).use { stream ->
                                    if (stream != null)
                                        finalBitmap = android.graphics.BitmapFactory.decodeStream(stream)
                                }
                            } catch (e: Exception) {
                                Log.e("PlaybackService", "Content URI 封面读取失败: ${e.message}")
                            }
                        }
                        uriStr.startsWith("auralis://cover") -> {
                            val path = uri.getQueryParameter("path") ?: ""
                            if (path.isNotEmpty()) {
                                // 内嵌封面
                                try {
                                    val retriever = android.media.MediaMetadataRetriever()
                                    retriever.setDataSource(path)
                                    val pic = retriever.embeddedPicture
                                    retriever.release()
                                    if (pic != null) {
                                        finalBitmap = android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.size)
                                        Log.d("PlaybackService", "✅ 内嵌封面解析成功")
                                    }
                                } catch (e: Exception) {
                                    Log.w("PlaybackService", "内嵌封面解析失败: ${e.message}")
                                }
                                // 磁盘缓存
                                if (finalBitmap == null) {
                                    finalBitmap = CoverArtCache.loadBitmapFromDisk(this@PlaybackService, path)
                                    if (finalBitmap != null) Log.d("PlaybackService", "✅ 磁盘缓存封面命中")
                                }
                                // MediaStore
                                if (finalBitmap == null) {
                                    finalBitmap = CoverArtCache.loadAlbumArtFromMediaStore(this@PlaybackService, path)
                                    if (finalBitmap != null) Log.d("PlaybackService", "✅ MediaStore 封面命中")
                                }
                                // 网络（网易云优先）
                                if (finalBitmap == null) {
                                    try {
                                        val song = AppDatabase.getDatabase(this@PlaybackService)
                                            .songDao().getSongByPath(path)
                                        if (song != null) {
                                            val fetched = CoverFetcher.fetchHighResCover(song.title, song.artist)
                                            if (fetched != null) {
                                                finalBitmap = fetched
                                                CoverArtCache.saveBitmap(this@PlaybackService, path, fetched)
                                                Log.d("PlaybackService", "✅ 网络封面拉取成功")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("PlaybackService", "网络封面拉取异常: ${e.message}")
                                    }
                                }
                            }
                        }
                        uriStr.startsWith("http://") || uriStr.startsWith("https://") -> {
                            try {
                                finalBitmap = CoverFetcher.downloadBitmap(uriStr)
                                if (finalBitmap != null) Log.d("PlaybackService", "✅ HTTP 直链封面下载成功")
                            } catch (e: Exception) {
                                Log.w("PlaybackService", "HTTP 直链封面下载失败: ${e.message}")
                            }
                        }
                        else -> {
                            try {
                                finalBitmap = defaultLoader.loadBitmap(uri)
                                    .get(5, java.util.concurrent.TimeUnit.SECONDS)
                            } catch (e: Exception) {
                                Log.w("PlaybackService", "默认加载器回退失败: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PlaybackService", "封面加载链路异常: ${e.message}")
                } finally {
                    if (finalBitmap == null) {
                        finalBitmap = try {
                            android.graphics.BitmapFactory.decodeResource(
                                resources, R.drawable.ic_notification_logo
                            )
                        } catch (_: Exception) {
                            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                        }
                    }
                    future.set(finalBitmap)
                }
            }
            return future
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 音频分析数据类
// ─────────────────────────────────────────────────────────────────────────────

data class AudioQualityAnalysis(
    val detectedCutoffHz: Int,
    val estimatedSource: String,
    val highFreqEnergyRatio: Float,
    val isSuspect: Boolean
)

object VisualizerData {
    @Volatile var amplitude: Float = 0f
    val fftBands = FloatArray(128)
    @Volatile var qualityAnalysis: AudioQualityAnalysis? = null
}

// ─────────────────────────────────────────────────────────────────────────────
// VisualizerInterceptingAudioSink
// ─────────────────────────────────────────────────────────────────────────────

@UnstableApi
class VisualizerInterceptingAudioSink(
    private val delegate: androidx.media3.exoplayer.audio.AudioSink
) : androidx.media3.exoplayer.audio.AudioSink by delegate {

    private var currentEncoding  = C.ENCODING_INVALID
    private var currentChannels  = 2
    private var currentSampleRate = 44100

    private var filterStateL = 0f
    private var filterStateR = 0f
    private val alphaLpf = 0.15f

    private val FFT_SIZE = 4096
    private val fftAccum = FloatArray(FFT_SIZE)
    private var fftAccumPos = 0

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
            var sumSq = 0f; var count = 0
            try {
                when (currentEncoding) {
                    C.ENCODING_PCM_FLOAT -> {
                        val fb = readBuffer.asFloatBuffer()
                        val frames = fb.remaining() / currentChannels
                        for (i in 0 until frames) {
                            var l = fb.get(); var r = if (currentChannels > 1) fb.get() else l
                            if (currentChannels > 2) fb.position(fb.position() + currentChannels - 2)
                            if (l.isNaN() || l.isInfinite()) l = 0f
                            if (r.isNaN() || r.isInfinite()) r = 0f
                            filterStateL += alphaLpf * (l - filterStateL)
                            filterStateR += alphaLpf * (r - filterStateR)
                            val mono = (filterStateL + filterStateR) * 0.5f
                            sumSq += mono * mono; count++; accumulateMono(mono)
                        }
                    }
                    C.ENCODING_PCM_16BIT -> {
                        val sb = readBuffer.asShortBuffer()
                        val frames = sb.remaining() / currentChannels
                        for (i in 0 until frames) {
                            val l = sb.get() / 32768f; val r = if (currentChannels > 1) sb.get() / 32768f else l
                            if (currentChannels > 2) sb.position(sb.position() + currentChannels - 2)
                            filterStateL += alphaLpf * (l - filterStateL)
                            filterStateR += alphaLpf * (r - filterStateR)
                            val mono = (filterStateL + filterStateR) * 0.5f
                            sumSq += mono * mono; count++; accumulateMono(mono)
                        }
                    }
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT -> {
                        val is24 = currentEncoding == C.ENCODING_PCM_24BIT
                        val bps = if (is24) 3 else 4; val maxVal = if (is24) 8388608f else 2147483648f
                        val frames = readBuffer.remaining() / (bps * currentChannels)
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
                                readBuffer.position(readBuffer.position() + (currentChannels - 2) * bps)
                            val sL = intL / maxVal; val sR = intR / maxVal
                            filterStateL += alphaLpf * (sL - filterStateL)
                            filterStateR += alphaLpf * (sR - filterStateR)
                            val mono = (filterStateL + filterStateR) * 0.5f
                            sumSq += mono * mono; count++; accumulateMono(mono)
                        }
                    }
                }
                if (count > 0) {
                    val rms = kotlin.math.sqrt(sumSq / count)
                    if (!rms.isNaN() && !rms.isInfinite()) VisualizerData.amplitude = rms
                }
            } catch (_: Exception) {}
        }
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    private fun accumulateMono(sample: Float) {
        fftAccum[fftAccumPos++] = sample
        if (fftAccumPos >= FFT_SIZE) {
            fftAccumPos = 0
            val snapshot = fftAccum.copyOf(); val sr = currentSampleRate
            fftExecutor.submit {
                try {
                    val spectrum = computeFFT(snapshot)
                    updateFftBands(spectrum, sr)
                    if (shouldRunQualityAnalysis()) VisualizerData.qualityAnalysis = analyzeQuality(spectrum, sr)
                } catch (_: Exception) {}
            }
        }
    }

    private var qualityAnalysisCounter = 0
    private fun shouldRunQualityAnalysis() = (++qualityAnalysisCounter % 20 == 0)

    private fun computeFFT(pcm: FloatArray): FloatArray {
        val n = pcm.size
        val re = FloatArray(n) { i -> pcm[i] * (0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * i / (n - 1)).toFloat()) }
        val im = FloatArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= n) {
            val hl = len / 2; val ang = -2.0 * Math.PI / len
            val wRe = kotlin.math.cos(ang).toFloat(); val wIm = kotlin.math.sin(ang).toFloat()
            var k = 0
            while (k < n) {
                var cRe = 1f; var cIm = 0f
                for (l in 0 until hl) {
                    val uRe = re[k+l]; val uIm = im[k+l]
                    val vRe = re[k+l+hl] * cRe - im[k+l+hl] * cIm
                    val vIm = re[k+l+hl] * cIm + im[k+l+hl] * cRe
                    re[k+l] = uRe+vRe; im[k+l] = uIm+vIm
                    re[k+l+hl] = uRe-vRe; im[k+l+hl] = uIm-vIm
                    val nCRe = cRe*wRe - cIm*wIm; cIm = cRe*wIm + cIm*wRe; cRe = nCRe
                }
                k += len
            }
            len *= 2
        }
        return FloatArray(n / 2) { i -> kotlin.math.sqrt((re[i]*re[i] + im[i]*im[i]).toDouble()).toFloat() / (n / 2) }
    }

    private fun updateFftBands(spectrum: FloatArray, sampleRate: Int) {
        val maxBin = spectrum.size; val nyquist = sampleRate / 2.0
        val minFreq = 20.0; val maxFreq = nyquist
        for (band in 0 until 128) {
            val fLow  = minFreq * (maxFreq / minFreq).pow(band.toDouble() / 128)
            val fHigh = minFreq * (maxFreq / minFreq).pow((band + 1.0) / 128)
            val bLow  = ((fLow  / nyquist) * maxBin).toInt().coerceIn(0, maxBin - 1)
            val bHigh = ((fHigh / nyquist) * maxBin).toInt().coerceIn(bLow + 1, maxBin)
            val avg = spectrum.slice(bLow until bHigh).average().toFloat()
            VisualizerData.fftBands[band] = VisualizerData.fftBands[band] * 0.6f + avg * 0.4f
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
        var cutoffHz = nyquist
        val noiseFloor = totalEnergy * 0.00001f
        for (i in bin20k downTo 0) {
            if (spectrum[i] * spectrum[i] > noiseFloor) { cutoffHz = (i * binHz).toInt(); break }
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