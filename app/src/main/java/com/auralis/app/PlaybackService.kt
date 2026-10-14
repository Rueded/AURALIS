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
import androidx.compose.ui.graphics.asAndroidBitmap
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

    // Service 不受 MainActivity.attachBaseContext 影响（它是独立的组件，
    // 不会因为 Activity.recreate() 就跟着重建）——Android Auto 浏览树标签、
    // USB Bit-perfect/声学鉴定这些从 Service 里直接 getString(...) 出来的文案，
    // 如果不在这里也做一次同样的 Context 包装，就会一直显示系统默认语言。
    override fun attachBaseContext(newBase: Context) {
        val lang = LocalizationManager.readSavedLanguage(newBase)
        super.attachBaseContext(LocalizationManager.wrapContext(newBase, lang))
    }

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

        // 播放队列/进度记忆（关 App 后恢复上次播放）
        private const val PLAYBACK_STATE_PREFS = "PlaybackStatePrefs"
        private const val KEY_QUEUE_PATHS = "queue_paths"       // JSON array of song.data 路径，按队列顺序
        private const val KEY_QUEUE_INDEX = "queue_index"       // 当前播放到第几首（对应上面数组的下标）
        private const val KEY_QUEUE_POSITION_MS = "queue_position_ms"
        private const val KEY_SHUFFLE_ENABLED = "queue_shuffle_enabled"
        private const val KEY_REPEAT_MODE = "queue_repeat_mode"
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
    private var systemLyricsOverlay: SystemLyricsOverlay? = null
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
                return VisualizerInterceptingAudioSink(originalSink, context)
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

            // 暂停、切歌时顺手保存一次现场：onDestroy/onTaskRemoved 不一定总会被系统调用到，
            // 这里多存几次是廉价操作（SharedPreferences 小 JSON），换来更保险的"记忆"体验。
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) savePlaybackState()
                updateWidgetState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                savePlaybackState()
                updateWidgetState()
            }

            // 标题/艺术家有时会在同一个 mediaItem 内部更新（比如封面/歌词元数据异步补全），
            // 不属于"切歌"，但小组件上显示的文字也得跟着刷新
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateWidgetState()
            }
        })
        player2?.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(sessionId: Int) {
                audioSessionId = sessionId
                _audioSessionIdState.value = sessionId
            }
            // 淡入淡出之后会话的播放器会换成 player2，之后的播放/切歌事件都来自它，
            // 小组件也要跟着刷新（updateWidgetState 读取的是当前会话的播放器）
            override fun onIsPlayingChanged(isPlaying: Boolean) { updateWidgetState() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateWidgetState() }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { updateWidgetState() }
        })

        // 封面异步加载完成后刷新小组件（修复切歌时小组件一直显示空封面的竞态）。
        // 必须在主线程 collect：updateWidgetState 要读 ExoPlayer，ExoPlayer 只能在创建它的
        // 主线程访问，放在 serviceScope（IO 线程）里读会直接抛"wrong thread"、刷新全部失败。
        serviceScope.launch(Dispatchers.Main) {
            PlayerStateHolder.coverBitmap.collect { bitmap ->
                if (bitmap != null) updateWidgetState()
            }
        }

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

        // 系统级悬浮歌词（切到别的应用也能看）。每次都取会话当前的播放器，
        // 这样淡入淡出换了播放器实例之后进度也不会错
        PlayerStateHolder.setLyricsOverlayEnabled(prefs.getBoolean("lyrics_overlay_enabled", true))
        systemLyricsOverlay = SystemLyricsOverlay(this) { mediaSession?.player }.also { it.start() }

        // 恢复上次关闭前的播放队列/进度
        restorePlaybackState()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务里划掉 App 时，onDestroy 不一定会被系统立刻调用，
        // 这里兜底先把当前队列/进度存一份，防止真被系统回收时丢状态。
        savePlaybackState()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePlaybackState()
        instance = null
        crossfadeHandler.removeCallbacksAndMessages(null) // 轮询 + 还没执行的延迟换播放器/渐变，一起取消
        systemLyricsOverlay?.stop(); systemLyricsOverlay = null
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
        buildBrowsableItem(NODE_ALL,       getString(R.string.tab_all_songs),    null),
        buildBrowsableItem(NODE_FAVORITES, getString(R.string.browse_favorites),  null),
        buildBrowsableItem(NODE_RECENT,    getString(R.string.tab_recent),    null),
        buildBrowsableItem(NODE_PLAYLISTS, getString(R.string.tab_playlists),    null)
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

    // ─────────────────────────────────────────────────────────
    // 播放队列 / 进度 记忆（关 App 后恢复上次播放）
    // ─────────────────────────────────────────────────────────

    /** 把当前播放队列（歌曲路径列表 + 播放到第几首 + 播放到第几毫秒）存进 SharedPreferences。 */
    /**
     * 把当前播放状态同步给桌面小组件：标题/艺术家/播放中与否/封面。
     * 封面直接复用 PlayerStateHolder 里已经加载好的那份（Compose ImageBitmap 转普通 Bitmap），
     * 不重新发起一次网络/本地封面加载——同一份数据，没必要有第二条加载路径。
     */
    private fun updateWidgetState() {
        try {
            // 淡入淡出之后真正在播的是会话当前的播放器，不一定还是 player（第一个）
            val p = mediaSession?.player ?: player ?: return
            val metadata = p.mediaMetadata
            val hasActiveSession = p.mediaItemCount > 0
            val title = metadata.title?.toString() ?: ""
            val artist = metadata.artist?.toString() ?: ""
            val isPlaying = p.isPlaying
            // 封面走内存单例（Preferences 存不了 Bitmap），其余字段直接当参数传给
            // requestUpdate，由它写进 Glance 官方的 Preferences 状态容器——
            // 不再往 AuralisWidgetState 上塞普通 var 了，那正是之前界面死活不刷新的根源
            // 封面缩到 300px 以内再给小组件：RemoteViews 对 Bitmap 总内存有上限，大封面会让小组件整个渲染失败
            val rawBmp = PlayerStateHolder.coverBitmap.value?.asAndroidBitmap()
            AuralisWidgetState.coverBitmap = if (rawBmp != null && (rawBmp.width > 300 || rawBmp.height > 300)) {
                Bitmap.createScaledBitmap(rawBmp, 300, 300, true)
            } else rawBmp
            Log.d("Auralis-Playback", "updateWidgetState: isPlaying=$isPlaying title=$title hasSession=$hasActiveSession")
            AuralisWidgetUpdater.requestUpdate(this, title, artist, isPlaying, hasActiveSession)
        } catch (e: Exception) {
            Log.e("Auralis-Playback", "小组件状态更新失败", e)
        }
    }

    private fun savePlaybackState() {
        try {
            val p = player ?: return
            val count = p.mediaItemCount
            if (count == 0) return // 没有队列（比如从没放过歌），不覆盖上次保存的状态

            val pathsArray = org.json.JSONArray()
            for (i in 0 until count) {
                pathsArray.put(p.getMediaItemAt(i).mediaId)
            }

            getSharedPreferences(PLAYBACK_STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_QUEUE_PATHS, pathsArray.toString())
                .putInt(KEY_QUEUE_INDEX, p.currentMediaItemIndex)
                .putLong(KEY_QUEUE_POSITION_MS, p.currentPosition.coerceAtLeast(0L))
                .putBoolean(KEY_SHUFFLE_ENABLED, p.shuffleModeEnabled)
                .putInt(KEY_REPEAT_MODE, p.repeatMode)
                .apply()
        } catch (e: Exception) {
            Log.e("Auralis-Playback", "保存播放队列失败", e)
        }
    }

    /**
     * App 重新启动、Service 重建时调用：把上次保存的队列恢复出来。
     * 只 setMediaItems + prepare，不自动 play()——恢复现场，但不要吓到用户突然自己放起歌来。
     */
    private fun restorePlaybackState() {
        val prefs = getSharedPreferences(PLAYBACK_STATE_PREFS, Context.MODE_PRIVATE)
        val pathsJson = prefs.getString(KEY_QUEUE_PATHS, null) ?: return
        val savedIndex = prefs.getInt(KEY_QUEUE_INDEX, 0)
        val savedPosition = prefs.getLong(KEY_QUEUE_POSITION_MS, 0L)
        val savedShuffle = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
        val savedRepeat = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)

        serviceScope.launch {
            try {
                val pathsArray = org.json.JSONArray(pathsJson)
                val paths = (0 until pathsArray.length()).map { pathsArray.getString(it) }
                if (paths.isEmpty()) return@launch

                val dao = AppDatabase.getDatabase(this@PlaybackService).songDao()
                // 按原顺序逐个查库；歌曲若已被删除/移动就跳过，同时记得同步修正下标
                val mediaItems = mutableListOf<MediaItem>()
                var adjustedIndex = savedIndex
                paths.forEachIndexed { i, path ->
                    val song = dao.getSongByPath(path)
                    if (song != null) {
                        mediaItems.add(buildPlayableItem(song))
                    } else if (i < savedIndex) {
                        adjustedIndex-- // 前面的歌被删了，下标要往前挪
                    }
                }
                if (mediaItems.isEmpty()) return@launch
                val safeIndex = adjustedIndex.coerceIn(0, mediaItems.size - 1)

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext
                    p.shuffleModeEnabled = savedShuffle
                    p.repeatMode = savedRepeat
                    p.setMediaItems(mediaItems, safeIndex, savedPosition)
                    p.prepare()
                    // 特意不调用 p.play()：只恢复现场，播放与否交给用户自己按
                }
            } catch (e: Exception) {
                Log.e("Auralis-Playback", "恢复播放队列失败", e)
            }
        }
    }

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

    // ── 淡入淡出状态机 ───────────────────────────────────────
    // IDLE    : 没有淡入淡出
    // PENDING : 已决定要淡入，正在等影子播放器缓冲好。这个阶段随时可以取消（比如用户又拖回前面了）
    // RUNNING : 已经换了播放器、音量渐变进行中。这个阶段不允许任何新的淡入淡出插进来
    private enum class FadePhase { IDLE, PENDING, RUNNING }
    private var fadePhase = FadePhase.IDLE
    private var fadeGeneration = 0
    private var pendingFadeListener: Player.Listener? = null
    private var pendingFadeShadow: ExoPlayer? = null
    private var pendingFadeFromId: String? = null
    private var lastFadeLogAt = 0L
    private val fadeTag = "Auralis-Fade"

    /** 日志里用 P1/P2 代替一长串对象地址，一眼看出是哪个播放器。 */
    private fun pid(p: Player?): String = when {
        p == null -> "null"
        p === player -> "P1"
        p === player2 -> "P2"
        else -> "?"
    }

    private fun setFadePhase(newPhase: FadePhase, why: String) {
        if (fadePhase != newPhase) {
            Log.i(fadeTag, "phase $fadePhase -> $newPhase ($why) usingP2=$isUsingPlayer2 session=${pid(mediaSession?.player)}")
        }
        fadePhase = newPhase
        isCrossfading = newPhase != FadePhase.IDLE
    }

    /** 取消一次还没真正开始的淡入淡出：摘掉等待缓冲的监听器、清空影子播放器、让过期回调作废。 */
    private fun cancelPendingCrossfade(shadow: ExoPlayer, why: String) {
        pendingFadeListener?.let { pendingFadeShadow?.removeListener(it) }
        pendingFadeListener = null; pendingFadeShadow = null; pendingFadeFromId = null
        fadeGeneration++
        isShadowPreloading = false
        try { shadow.pause(); shadow.stop(); shadow.clearMediaItems() } catch (e: Exception) {
            Log.e(fadeTag, "清理影子播放器失败: ${e.message}")
        }
        Log.w(fadeTag, "🚫 取消待定淡入淡出: $why")
        setFadePhase(FadePhase.IDLE, "cancel")
    }

    private fun checkCrossfadeTransition() {
        if (isCurrentlyBitPerfect) return
        val mainPlayer   = if (isUsingPlayer2) player2 else player
        val shadowPlayer = if (isUsingPlayer2) player  else player2
        if (mainPlayer == null || shadowPlayer == null) return

        // 渐变进行中：什么都不碰（旧代码在这里会因为"新歌位置很靠前"把标志位误清掉，
        // 导致渐变没结束就能再触发一次淡入淡出，把播放器来回换乱）
        if (fadePhase == FadePhase.RUNNING) return

        // 会话里的播放器才是"真正在播的那个"，以它为准校正 isUsingPlayer2，不让两边对不上
        val sessionPlayer = mediaSession?.player
        if (sessionPlayer != null && sessionPlayer !== mainPlayer) {
            Log.e(fadeTag, "⚠️ 主播放器与会话播放器不一致: main=${pid(mainPlayer)} session=${pid(sessionPlayer)} usingP2=$isUsingPlayer2，按会话校正")
            isUsingPlayer2 = (sessionPlayer === player2)
            return
        }

        if (!mainPlayer.isPlaying) {
            if (fadePhase == FadePhase.PENDING) cancelPendingCrossfade(shadowPlayer, "主播放器已暂停")
            return
        }

        // 单曲循环：把控制权交还 ExoPlayer 原生的 REPEAT_MODE_ONE
        if (mainPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
            if (fadePhase == FadePhase.PENDING) cancelPendingCrossfade(shadowPlayer, "切到了单曲循环")
            return
        }

        val prefs = getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val crossfadeSecs = prefs.getFloat("crossfade_duration", 0f)
        if (crossfadeSecs <= 0f) {
            if (fadePhase == FadePhase.PENDING) cancelPendingCrossfade(shadowPlayer, "淡入淡出被关闭")
            return
        }

        val crossfadeMs   = (crossfadeSecs * 1000).toLong()
        val duration      = mainPlayer.duration
        val position      = mainPlayer.currentPosition
        if (duration <= 0) {
            if (fadePhase == FadePhase.PENDING) cancelPendingCrossfade(shadowPlayer, "duration 未知")
            return
        }
        val timeRemaining = duration - position

        val now = System.currentTimeMillis()
        if (timeRemaining <= crossfadeMs + 15000L && now - lastFadeLogAt > 1000L) {
            lastFadeLogAt = now
            Log.d(fadeTag, "poll phase=$fadePhase main=${pid(mainPlayer)} pos=$position dur=$duration remain=$timeRemaining crossfade=${crossfadeMs}ms preloading=$isShadowPreloading shadowState=${shadowPlayer.playbackState}")
        }

        // 待定阶段：用户如果又拖回前面 / 切了歌，就取消，别让过期的"缓冲好了"回调再把播放器换掉
        if (fadePhase == FadePhase.PENDING) {
            val sameTrack = mainPlayer.currentMediaItem?.mediaId == pendingFadeFromId
            val nearEnd = timeRemaining in 1..(crossfadeMs + 1500L)
            if (!sameTrack || !nearEnd) {
                cancelPendingCrossfade(shadowPlayer, "sameTrack=$sameTrack nearEnd=$nearEnd remain=${timeRemaining}ms")
            }
            return
        }

        if (isShadowPreloading && preloadedNextIndex != mainPlayer.nextMediaItemIndex) {
            isShadowPreloading = false
            shadowPlayer.stop()
            shadowPlayer.clearMediaItems()
        }

        val preloadTriggerMs = crossfadeMs + 15000L
        if (timeRemaining <= preloadTriggerMs && timeRemaining > crossfadeMs &&
            mainPlayer.hasNextMediaItem() && !isShadowPreloading) {
            Log.i(fadeTag, "⏳ 提前缓冲，剩余: ${timeRemaining}ms")
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

        if (timeRemaining > 0 && timeRemaining <= crossfadeMs && mainPlayer.hasNextMediaItem()) {
            isShadowPreloading = false
            val gen = ++fadeGeneration
            pendingFadeFromId = mainPlayer.currentMediaItem?.mediaId
            setFadePhase(FadePhase.PENDING, "remain=${timeRemaining}ms main=${pid(mainPlayer)} shadowState=${shadowPlayer.playbackState}")
            val startVolume = mainPlayer.volume

            // 真正"换播放器"之前再校验一遍：缓冲期间用户可能暂停了/拖走了/切歌了
            val commit = Runnable {
                val remainNow = mainPlayer.duration - mainPlayer.currentPosition
                val stillOk = fadePhase == FadePhase.PENDING && gen == fadeGeneration &&
                        mediaSession?.player === mainPlayer && mainPlayer.isPlaying &&
                        mainPlayer.duration > 0 && remainNow <= crossfadeMs + 1500L
                if (!stillOk) {
                    cancelPendingCrossfade(shadowPlayer, "提交前校验失败 phase=$fadePhase gen=$gen/$fadeGeneration session=${pid(mediaSession?.player)} playing=${mainPlayer.isPlaying} remain=$remainNow")
                    return@Runnable
                }
                pendingFadeListener = null; pendingFadeShadow = null
                setFadePhase(FadePhase.RUNNING, "commit")
                Log.i(fadeTag, "🔀 启动 ${crossfadeSecs}s 淡入淡出 out=${pid(mainPlayer)} in=${pid(shadowPlayer)}")
                mainPlayer.setAudioAttributes(mainPlayer.audioAttributes, false)
                shadowPlayer.setAudioAttributes(shadowPlayer.audioAttributes, true)
                isUsingPlayer2 = !isUsingPlayer2
                shadowPlayer.volume = 0f
                shadowPlayer.play()
                crossfadeHandler.postDelayed({
                    try {
                        mediaSession?.player = shadowPlayer
                        Log.i(fadeTag, "🔁 会话播放器已切到 ${pid(shadowPlayer)}，新歌=${shadowPlayer.currentMediaItem?.mediaId}")
                        val idx = mainPlayer.currentMediaItemIndex
                        if (idx + 1 < mainPlayer.mediaItemCount)
                            mainPlayer.removeMediaItems(idx + 1, mainPlayer.mediaItemCount)
                        mainPlayer.repeatMode = Player.REPEAT_MODE_OFF
                        startCrossfade(mainPlayer, shadowPlayer, crossfadeMs, startVolume)
                    } catch (e: Exception) {
                        Log.e(fadeTag, "🔥 换播放器过程出错: ${e.message}", e)
                        setFadePhase(FadePhase.IDLE, "swap failed")
                    }
                }, 50)
            }

            if (shadowPlayer.playbackState == Player.STATE_READY) {
                Log.i(fadeTag, "⚡ 预加载命中，完美秒切")
                commit.run()
            } else {
                if (shadowPlayer.playbackState == Player.STATE_IDLE) {
                    val items = (0 until mainPlayer.mediaItemCount).map { mainPlayer.getMediaItemAt(it) }
                    shadowPlayer.setMediaItems(items, mainPlayer.nextMediaItemIndex, 0L)
                    shadowPlayer.prepare()
                }
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            shadowPlayer.removeListener(this)
                            if (pendingFadeListener === this) { pendingFadeListener = null; pendingFadeShadow = null }
                            if (gen == fadeGeneration && fadePhase == FadePhase.PENDING) commit.run()
                            else Log.w(fadeTag, "忽略过期的 READY 回调 gen=$gen/$fadeGeneration phase=$fadePhase")
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        shadowPlayer.removeListener(this)
                        if (pendingFadeListener === this) { pendingFadeListener = null; pendingFadeShadow = null }
                        if (gen == fadeGeneration && fadePhase == FadePhase.PENDING) {
                            cancelPendingCrossfade(shadowPlayer, "影子播放器出错 ${error.errorCodeName}")
                        }
                    }
                }
                pendingFadeListener = listener; pendingFadeShadow = shadowPlayer
                shadowPlayer.addListener(listener)
            }
        }
    }

    private fun startCrossfade(
        fadeOutPlayer: ExoPlayer, fadeInPlayer: ExoPlayer,
        durationMs: Long, startVolume: Float
    ) {
        val startTime = System.currentTimeMillis()
        Log.i(fadeTag, "🎚 渐变开始 out=${pid(fadeOutPlayer)} in=${pid(fadeInPlayer)} session=${pid(mediaSession?.player)} dur=${durationMs}ms")

        fun finishFade() {
            Log.i(fadeTag, "🏁 渐变结束 out=${pid(fadeOutPlayer)} in=${pid(fadeInPlayer)} session=${pid(mediaSession?.player)}")
            try {
                fadeInPlayer.volume = targetVolume
                fadeOutPlayer.pause()
                // 绝不清空"会话当前的播放器"——清空它界面就会收到 title=null
                if (fadeOutPlayer === mediaSession?.player) {
                    Log.e(fadeTag, "❌ 淡出的播放器就是会话当前播放器，已跳过 clearMediaItems（旧版这里会让界面标题变 null 然后闪退）")
                } else {
                    fadeOutPlayer.clearMediaItems()
                }
                fadeOutPlayer.setAudioAttributes(fadeOutPlayer.audioAttributes, true)
                fadeOutPlayer.volume = targetVolume
            } catch (e: Exception) {
                Log.e(fadeTag, "收尾出错: ${e.message}", e)
            } finally {
                setFadePhase(FadePhase.IDLE, "fade finished")
            }
        }

        // 用 crossfadeHandler 而不是新建 Handler：onDestroy 里 removeCallbacksAndMessages 能一并取消
        crossfadeHandler.post(object : Runnable {
            override fun run() {
                try {
                    val elapsed  = System.currentTimeMillis() - startTime
                    val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                    val smooth   = progress * progress * (3 - 2 * progress)
                    fadeOutPlayer.volume = startVolume * (1f - smooth)
                    fadeInPlayer.volume  = targetVolume * smooth
                    if (progress < 1f) crossfadeHandler.postDelayed(this, 16) else finishFade()
                } catch (e: Exception) {
                    Log.e(fadeTag, "🔥 渐变循环出错: ${e.message}", e)
                    setFadePhase(FadePhase.IDLE, "fade loop crashed")
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
                    android.widget.Toast.makeText(this, getString(R.string.dac_exclusive_active, "${targetSampleRate / 1000.0}"), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, getString(R.string.dac_exclusive_failed_unsupported), android.widget.Toast.LENGTH_SHORT).show()
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
                android.widget.Toast.makeText(this, getString(R.string.android_mixing_restored), android.widget.Toast.LENGTH_SHORT).show()
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
                                            val fetched = CoverFetcher.fetchHighResCover(this@PlaybackService, song.title, song.artist)
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
    private val delegate: androidx.media3.exoplayer.audio.AudioSink,
    private val context: Context
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
            cutoffHz >= 20000 -> context.getString(R.string.audio_quality_hires_verified)
            cutoffHz >= 19500 -> context.getString(R.string.audio_quality_maybe_mp3_320)
            cutoffHz >= 18000 -> context.getString(R.string.audio_quality_suspect_mp3_256)
            cutoffHz >= 16000 -> context.getString(R.string.audio_quality_suspect_mp3_128_192)
            cutoffHz >= 14000 -> context.getString(R.string.audio_quality_suspect_aac_low_bitrate)
            else              -> context.getString(R.string.audio_quality_suspect_very_low_bitrate)
        }
        return AudioQualityAnalysis(cutoffHz, estimatedSource, ratio, isSuspect)
    }
}