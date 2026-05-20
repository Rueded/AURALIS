package com.auralis.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import kotlinx.coroutines.isActive // 确保引入了 isActive
import kotlinx.coroutines.flow.first
import kotlin.math.pow
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.DrawScope // 确保绘图作用域被识别
// 如果 drawRect 还是红的，补上这个：
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import android.util.Log
import androidx.compose.material.icons.outlined.DeleteOutline
import com.auralis.app.PlayerStateHolder.dominantColor
import com.auralis.app.ui.theme.AuralisTheme
import kotlin.apply

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun FullScreenPlayer(
    title: String, artist: String, isPlaying: Boolean, artwork: ByteArray?, audioPath: String,
    mediaController: MediaController?, repeatMode: Int, shuffleMode: Boolean,
    sleepTimerSeconds: Long, audioManager: AudioManager,
    isFavorite: Boolean,                // 👇 新增
    onFavoriteClick: () -> Unit,        // 👇 新增
    onSleepTimerClick: () -> Unit, onBackClick: () -> Unit, onArtistClick: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 👇 核心修复：添加拦截盾牌
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null // 去掉点击时的涟漪效果，让背景板看起来是静态的
            ) {
                // 内部留空，目的是拦截并消耗掉点击事件，不让它传给背后的首页列表
            }
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val activity = context as? android.app.Activity
        val fallbackColors = remember(audioPath) { generateElegantColors(audioPath) }

        // 👇 智能断点：判断当前设备是否为“高度极其受限”的手机横屏
        val isCompactLandscape = isLandscape && configuration.screenHeightDp < 500

        val prefs =
            remember { context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE) }
        var isKeepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", false)) }

        DisposableEffect(isKeepScreenOn) {
            val window = activity?.window
            if (isKeepScreenOn) {
                window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        LaunchedEffect(isKeepScreenOn) {
            prefs.edit().putBoolean("keep_screen_on", isKeepScreenOn).apply()
        }

        var currentPosition by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var isDraggingSlider by remember { mutableStateOf(false) }
        var sliderProgress by remember { mutableFloatStateOf(0f) }

        var spec by remember { mutableStateOf<AudioSpec?>(null) }
        var detailedInfo by remember { mutableStateOf("") }

        var lrcLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
        var showLyrics by remember { mutableStateOf(false) }
        var isFullscreenLyrics by remember { mutableStateOf(false) }
        var lyricsSource by remember { mutableStateOf(LyricsSource.LOCAL) }
        val enableOnlineLyrics = remember {
            context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
                .getBoolean("enable_online_lyrics", true)
        }
        val listState = rememberLazyListState()
        val isUserDraggingLyrics by listState.interactionSource.collectIsDraggedAsState()
        var isLyricsPausedForInteraction by remember { mutableStateOf(false) }

        var showPlaylistSheet by remember { mutableStateOf(false) }
        var showInfoDialog by remember { mutableStateOf(false) }
        var showEqDialog by remember { mutableStateOf(false) }
        var coverRefreshNonce by remember { mutableIntStateOf(0) }
        var coverForceNetwork by remember { mutableStateOf(false) }

        var lyricsFontSize by remember { mutableFloatStateOf(prefs.getFloat("lyrics_font_size", 20f)) }

        // 👇 只要哥哥点击了 A- 或 A+ 改变了大小，就立刻写进本地文件里永久保存！
        LaunchedEffect(lyricsFontSize) {
            prefs.edit().putFloat("lyrics_font_size", lyricsFontSize).apply()
        }
        var playbackSpeed by rememberSaveable { mutableFloatStateOf(1.0f) }
        var abLoopStart by remember { mutableLongStateOf(-1L) }
        var abLoopEnd by remember { mutableLongStateOf(-1L) }

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        var currentVolume by remember {
            mutableFloatStateOf(
                audioManager.getStreamVolume(
                    AudioManager.STREAM_MUSIC
                ).toFloat()
            )
        }
        var isDraggingVolume by remember { mutableStateOf(false) }
        var preMuteVolume by remember { mutableFloatStateOf(maxVolume / 3f) }

        LaunchedEffect(Unit) {
            while (true) {
                val v = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                if (!isDraggingVolume) currentVolume = v
                delay(500)
            }
        }

        val rawDominantColor by PlayerStateHolder.dominantColor.collectAsState()
        val albumPalette by PlayerStateHolder.albumPalette.collectAsState()
        val animatedDominantColor by animateColorAsState(
            targetValue = rawDominantColor ?: MaterialTheme.colorScheme.primaryContainer,
            animationSpec = tween(1500, easing = LinearEasing),
            label = "dominantColor"
        )


        val currentLyricIndex = remember(
            currentPosition,
            lrcLines
        ) {
            if (lrcLines.isEmpty()) -1 else lrcLines.indexOfLast { it.timeMs <= currentPosition }
                .coerceAtLeast(0)
        }
        val centerOffset =
            if (isFullscreenLyrics) 3 else if (isLandscape) (if (isCompactLandscape) 1 else 3) else 1
        val lyricsVerticalPadding =
            if (isFullscreenLyrics) 100.dp else if (isLandscape) (if (isCompactLandscape) 20.dp else 60.dp) else 40.dp

        val lrcPickerInPlayer =
            rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val lrcFile = File(
                                File(audioPath).parent,
                                "${File(audioPath).nameWithoutExtension}.lrc"
                            )
                            inputStream?.use { input ->
                                lrcFile.outputStream()
                                    .use { output -> output.write(input.readBytes()) }
                            }
                            val newLines = LrcParser.parse(audioPath)
                            withContext(Dispatchers.Main) {
                                lrcLines = newLines; Toast.makeText(
                                context,
                                "歌词导入成功！",
                                Toast.LENGTH_SHORT
                            ).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "导入失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }

        LaunchedEffect(
            currentLyricIndex,
            isLyricsPausedForInteraction,
            isPlaying,
            isFullscreenLyrics,
            isLandscape,
            isCompactLandscape
        ) {
            if (!isLyricsPausedForInteraction && currentLyricIndex >= 0 && lrcLines.isNotEmpty() && !isDraggingSlider)
                listState.animateScrollToItem(maxOf(0, currentLyricIndex - centerOffset))
        }

        LaunchedEffect(mediaController) {
            while (true) {
                if (!isDraggingSlider) {
                    currentPosition = mediaController?.currentPosition ?: 0L
                    duration = mediaController?.duration?.coerceAtLeast(1L) ?: 1L
                    if (abLoopStart >= 0 && abLoopEnd > abLoopStart && currentPosition >= abLoopEnd) mediaController?.seekTo(
                        abLoopStart
                    )
                }
                delay(300L)
            }
        }

        LaunchedEffect(audioPath) {
            // ── 1. 瞬间清空 UI 状态，绝不拖泥带水 ──
            showLyrics = false
            isFullscreenLyrics = false
            abLoopStart = -1L
            abLoopEnd = -1L
            lrcLines = emptyList()
            lyricsSource = LyricsSource.LOCAL

            // ── 赛道 A：歌词专属处理协程 ──
            launch(Dispatchers.IO) {
                // 1. 毫无延迟地优先读取本地 LRC！
                val localLines = LrcParser.parse(audioPath)
                if (localLines.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        lrcLines = localLines
                        lyricsSource = LyricsSource.LOCAL
                    }
                } else if (enableOnlineLyrics) {
                    // 2. 本地没有，准备联网。🚨 启动 500ms 防抖！
                    // 如果快速切歌，这个 launch 就会被取消，绝对不会发错请求
                    delay(500)

                    // 3. 🚨 核心修复：坚决不用外面传进来的可能过期的 title/artist！
                    // 直接去数据库里通过路径查出这首歌真正的名字！
                    val dbSong = AppDatabase.getDatabase(context).songDao().getSongByPath(audioPath)
                    val safeTitle = dbSong?.title ?: title
                    val safeArtist = dbSong?.artist ?: artist

                    val result = OnlineLyricsRepository.getLyrics(
                        audioPath = audioPath,
                        title = safeTitle,
                        artist = safeArtist,
                        context = context
                    )
                    withContext(Dispatchers.Main) {
                        lrcLines = result.lines
                        lyricsSource = result.source
                    }
                }
            }

            withContext(Dispatchers.IO) {
                try {
                    // 1. 【核心优化】所有的耗时读取全在这里进行，删掉外面多余的查询
                    val songDao = AppDatabase.getDatabase(context).songDao()
                    val dbSong = songDao.getSongByPath(audioPath)

                    // 优先使用数据库里 jaudiotagger 解析出的精准数据
                    val dbBits = dbSong?.bitDepth ?: 16
                    val dbSampleRate = dbSong?.samplingRate ?: 44100
                    val dbGain = dbSong?.replayGain ?: 0f

                    // 👇 提取听歌足迹数据
                    val playCount = dbSong?.playCount ?: 0
                    val lastPlayedMs = dbSong?.lastPlayed ?: 0L

                    val r = MediaMetadataRetriever()
                    r.setDataSource(audioPath)

                    // 现场解析变动参数
                    val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                    val albumStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotBlank() } ?: "未知专辑"
                    val genreStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.trim()?.takeIf { it.isNotBlank() } ?: "未知流派"
                    val yearStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.trim()?.takeIf { it.isNotBlank() } ?: "未知年份"
                    val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                    var ch = 2
                    var isAudioVivid = false

                    // 检测声道和 Audio Vivid
                    try {
                        val extractor = android.media.MediaExtractor()
                        extractor.setDataSource(audioPath)
                        for (i in 0 until extractor.trackCount) {
                            val fmt = extractor.getTrackFormat(i)
                            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                            if (mime.startsWith("audio/")) {
                                if (fmt.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                                    ch = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                                }
                                if (mime.contains("av3a", true)) isAudioVivid = true
                            }
                        }
                        extractor.release()
                    } catch (e: Exception) {}

                    // 2. 更新 Spec 状态
                    spec = AudioSpec(
                        format = if (isAudioVivid) "flac" else audioPath, // 后面展示时我们会截取后缀
                        bitRate = br,
                        sampleRate = dbSampleRate,
                        bitDepth = dbBits,
                        channels = if (isAudioVivid) 12 else ch
                    )

                    // 👇 3. 准备格式化文件数据与时间数据
                    val file = java.io.File(audioPath)
                    val sizeStr = String.format(java.util.Locale.US, "%.2f MB", file.length() / (1024.0 * 1024.0))

                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    val lastModifiedStr = if (file.exists()) sdf.format(java.util.Date(file.lastModified())) else "未知"
                    val lastPlayedStr = if (lastPlayedMs > 0) sdf.format(java.util.Date(lastPlayedMs)) else "从未播放"

                    // 安全提取文件后缀名 (比如 flac, wav, mp3)
                    val extName = if (isAudioVivid) "FLAC-AV3A" else file.extension.uppercase()

                    // 4. 构造专业的高级详情文本
                    detailedInfo = """
                🎵 基础信息 (Basic Info)
                歌名：$title
                歌手：$artist
                专辑：$albumStr
                流派：$genreStr
                年份：$yearStr

                📊 音频规格 (Audio Specs)
                音质：${spec!!.level.label} ($extName)
                采样率：${dbSampleRate / 1000.0} kHz
                位深度：$dbBits bit
                声道：$ch ${if (spec!!.isSpatial) "(Spatial)" else "(Stereo)"}
                码率：${br / 1000} kbps
                动态增益：${if (dbGain != 0f) String.format("%.2f dB", dbGain) else "未检测到 (0.00 dB)"}

                📁 文件与足迹 (File & Stats)
                大小：$sizeStr
                时长：${formatTime(durationMs)}
                累计播放：$playCount 次
                最近播放：$lastPlayedStr
                修改时间：$lastModifiedStr
                
                路径：$audioPath
            """.trimIndent()

                    r.release()
                } catch (e: Exception) {
                    android.util.Log.e("Player", "解析详情失败: ${e.message}")
                }
            }
        }

        val showGoldLogo = spec?.level in listOf(
            AudioLevel.MASTER,
            AudioLevel.HI_RES_PLUS,
            AudioLevel.HI_RES,
            AudioLevel.DSD,
            AudioLevel.DXD
        )
        val gradientBrush = Brush.verticalGradient(
            listOf(
                animatedDominantColor.copy(alpha = 0.55f),
                animatedDominantColor.copy(alpha = 0.15f),
                MaterialTheme.colorScheme.surface
            )
        )

        @Composable
        fun ControlsSection(isLandscapeLayout: Boolean) {
            val actualProgress =
                if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f

            Column(modifier = Modifier.fillMaxWidth()) {
                PremiumProgressSlider(
                    progress = actualProgress,
                    isDragging = isDraggingSlider,
                    dragProgress = sliderProgress,
                    durationMs = duration,
                    accentColor = animatedDominantColor,
                    onDragStart = { isDraggingSlider = true },
                    onDragChange = { sliderProgress = it },
                    onDragEnd = {
                        isDraggingSlider = false
                        mediaController?.seekTo((sliderProgress * duration).toLong())
                        currentPosition = (sliderProgress * duration).toLong()
                        isLyricsPausedForInteraction = false
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(currentPosition), style = MaterialTheme.typography.labelMedium)
                    if (abLoopStart >= 0) Text(
                        if (abLoopEnd >= 0) "A:${formatTime(abLoopStart)} ↔ B:${
                            formatTime(
                                abLoopEnd
                            )
                        }" else "A:${formatTime(abLoopStart)} → ?",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = if (isLandscapeLayout) 0.dp else 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (shuffleMode) {
                        mediaController?.shuffleModeEnabled = false; mediaController?.repeatMode =
                            Player.REPEAT_MODE_OFF
                    } else if (repeatMode == Player.REPEAT_MODE_OFF) mediaController?.repeatMode =
                        Player.REPEAT_MODE_ALL
                    else if (repeatMode == Player.REPEAT_MODE_ALL) mediaController?.repeatMode =
                        Player.REPEAT_MODE_ONE
                    else {
                        mediaController?.repeatMode =
                            Player.REPEAT_MODE_ALL; mediaController?.shuffleModeEnabled = true
                    }
                }) {
                    val modeIcon = when {
                        shuffleMode -> Icons.Filled.Shuffle; repeatMode == Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne; repeatMode == Player.REPEAT_MODE_ALL -> Icons.Filled.Repeat; else -> Icons.Filled.FormatListNumbered
                    }
                    Icon(
                        modeIcon,
                        "Play Mode",
                        tint = if (repeatMode == Player.REPEAT_MODE_OFF && !shuffleMode) MaterialTheme.colorScheme.onSurface.copy(
                            alpha = 0.5f
                        ) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        mediaController?.let { controller ->
                            // 获取当前是第几首
                            val currentIdx = controller.currentMediaItemIndex
                            // 如果不是第一首，就强制切到前一首的 0 秒位置
                            if (currentIdx > 0) {
                                controller.seekTo(currentIdx - 1, 0L)
                                controller.play()
                            } else {
                                // 如果是第一首，就只是回到开头
                                controller.seekTo(0, 0L)
                            }
                        }
                    }) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            null,
                            modifier = Modifier.size(if (isLandscapeLayout) 40.dp else 44.dp)
                        )
                    }
                    GlowPlayButton(
                        isPlaying = isPlaying,
                        onClick = {
                            if (isPlaying) mediaController?.pause() else {
                                mediaController?.play()
                                isLyricsPausedForInteraction = false
                            }
                        },
                        size = if (isLandscapeLayout) 64.dp else 72.dp,
                        iconSize = if (isLandscapeLayout) 32.dp else 36.dp
                    )
                    IconButton(onClick = { mediaController?.seekToNext() }) {
                        Icon(
                            Icons.Filled.SkipNext,
                            null,
                            modifier = Modifier.size(if (isLandscapeLayout) 40.dp else 44.dp)
                        )
                    }
                }
                IconButton(onClick = { showPlaylistSheet = true }) {
                    Icon(
                        Icons.Filled.QueueMusic,
                        "Playlist",
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val abLabel = when {
                abLoopStart < 0 -> "A-B"
                abLoopEnd < 0 -> "B ?"
                else -> "A-B ✓"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isBitPerfectActive by PlaybackService.bitPerfectState.collectAsState()

                Box(
                    modifier = Modifier.graphicsLayer {
                        // 如果开启了独占，让倍速控制栏略微变淡置灰，视觉上提示不可用
                        alpha = if (isBitPerfectActive) 0.5f else 1.0f
                    }
                ) {
                    SpeedControlChip(
                        // 当独占开启时，UI 强制回显 1.0x
                        playbackSpeed = if (isBitPerfectActive) 1.0f else playbackSpeed,
                        onSpeedSelected = { speed ->
                            if (isBitPerfectActive) {
                                // 像 EQ 按钮一样弹出 Toast 警告 ⛔
                                Toast.makeText(context, "Bit-perfect 已开启，硬件直通状态下无法修改倍速哦", Toast.LENGTH_SHORT).show()
                            } else {
                                playbackSpeed = speed
                                mediaController?.playbackParameters = PlaybackParameters(speed, speed)
                            }
                        }
                    )
                }
                    PlayerToolChip(
                        label = abLabel,
                        selected = abLoopEnd >= 0,
                        onClick = {
                            when {
                                abLoopStart < 0 -> {
                                    abLoopStart = currentPosition
                                    Toast.makeText(context, "A 点已设", Toast.LENGTH_SHORT).show()
                                }
                                abLoopEnd < 0 -> {
                                    if (currentPosition > abLoopStart) {
                                        abLoopEnd = currentPosition
                                        Toast.makeText(context, "B 点已设，循环开始", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "B 点必须在 A 点之后", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                else -> {
                                    abLoopStart = -1L
                                    abLoopEnd = -1L
                                    Toast.makeText(context, "A-B 取消", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    PlayerToolChip(
                        label = if (isFavorite) "已收藏" else "收藏",
                        selected = isFavorite,
                        onClick = onFavoriteClick,
                        icon = {
                            Icon(
                                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    PlayerToolChip(label = "EQ", selected = false, onClick = { showEqDialog = true })
                    PlayerToolChip(
                        label = "定时",
                        selected = sleepTimerSeconds > 0,
                        onClick = onSleepTimerClick,
                        icon = {
                            Icon(
                                Icons.Filled.NightsStay,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = if (sleepTimerSeconds > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }


        val bgModePref = remember {
            BackgroundMode.entries.firstOrNull {
                it.name == prefs.getString("bg_mode", BackgroundMode.BREATHING.name)
            } ?: BackgroundMode.BREATHING
        }

        ReactiveBackground(
            dominantColor  = animatedDominantColor,
            palette        = albumPalette,
            mode           = bgModePref,
            isPlaying      = isPlaying,
            audioSessionId = PlaybackService.audioSessionId,
            modifier       = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        ) {

            val volumeBarWidth by animateDpAsState(
                targetValue = if (isDraggingVolume) 8.dp else 4.dp,
                label = "volumeWidth"
            )
            val volumeBarAlpha by animateFloatAsState(
                targetValue = if (isDraggingVolume) 1f else 0.6f,
                label = "volumeAlpha"
            )
            val isMuted = currentVolume == 0f

            val slashProgress by animateFloatAsState(
                targetValue = if (isMuted) 1f else 0f,
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                label = "slashAnim"
            )
            val fillAlpha by animateFloatAsState(
                targetValue = if (isMuted) 0f else 1f,
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                label = "fillAnim"
            )
            val targetRatio =
                if (maxVolume > 0) (currentVolume / maxVolume).coerceIn(0f, 1f) else 0f
            val animatedFillRatio by animateFloatAsState(
                targetValue = targetRatio,
                animationSpec = tween(
                    durationMillis = if (isDraggingVolume) 0 else 350,
                    easing = FastOutSlowInEasing
                ),
                label = "barAnim"
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(48.dp)
                    .align(Alignment.CenterStart)
                    .zIndex(10f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.height(320.dp)
                ) {

                    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    val strokeWidthDp = 1.5.dp

                    androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
                        val strokeW = strokeWidthDp.toPx()
                        val radius = size.width / 2f
                        drawCircle(
                            color = baseColor,
                            radius = radius,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                        )
                        if (fillAlpha > 0f) drawCircle(
                            color = baseColor.copy(alpha = baseColor.alpha * fillAlpha),
                            radius = radius - strokeW / 2f
                        )
                        if (slashProgress > 0f) {
                            val extension = size.width * 0.3f
                            val startPt =
                                androidx.compose.ui.geometry.Offset(-extension, -extension)
                            val endTarget = androidx.compose.ui.geometry.Offset(
                                size.width + extension,
                                size.height + extension
                            )
                            val currentEnd = androidx.compose.ui.geometry.Offset(
                                x = startPt.x + (endTarget.x - startPt.x) * slashProgress,
                                y = startPt.y + (endTarget.y - startPt.y) * slashProgress
                            )
                            drawLine(
                                color = baseColor,
                                start = startPt,
                                end = currentEnd,
                                strokeWidth = strokeW,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .width(32.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = { isDraggingVolume = true },
                                    onDragEnd = { isDraggingVolume = false },
                                    onDragCancel = { isDraggingVolume = false },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        val sensitivity = maxVolume / 600f
                                        currentVolume =
                                            (currentVolume - dragAmount * sensitivity).coerceIn(
                                                0f,
                                                maxVolume
                                            )
                                        audioManager.setStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                            currentVolume.roundToInt(),
                                            0
                                        )
                                    }
                                )
                            },
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight().width(volumeBarWidth).background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                                CircleShape
                            )
                        )
                        Box(
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(animatedFillRatio)
                                .align(Alignment.BottomCenter)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .wrapContentWidth(Alignment.CenterHorizontally)
                                    .width(volumeBarWidth).background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = volumeBarAlpha),
                                    CircleShape
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (currentVolume > 0f) {
                                    preMuteVolume = currentVolume; currentVolume = 0f
                                } else {
                                    currentVolume =
                                        if (preMuteVolume > 0f) preMuteVolume else maxVolume / 3f
                                }
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    currentVolume.roundToInt(),
                                    0
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier.height(2.dp).width(12.dp).background(
                                if (isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.5f
                                ), CircleShape
                            )
                        )
                    }
                }
            }

            if (isLandscape) {
                // ── 横屏布局 (恢复平板优美排版 + 兼容手机高度) ──
                Column(
                    modifier = Modifier.fillMaxSize()
                        .padding(start = 48.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (isFullscreenLyrics) isFullscreenLyrics = false else onBackClick()
                        }) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                "Back",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                lyricsFontSize = (lyricsFontSize - 2f).coerceAtLeast(12f)
                            }) { Text("A-", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = {
                                lyricsFontSize = (lyricsFontSize + 2f).coerceAtMost(36f)
                            }) { Text("A+", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    // 为了防止串歌，云糯帮你从数据库里查最准确的名字哦
                                    val dbSong = AppDatabase.getDatabase(context).songDao().getSongByPath(audioPath)
                                    val safeTitle = dbSong?.title ?: title
                                    val safeArtist = dbSong?.artist ?: artist

                                    val result = OnlineLyricsRepository.refreshFromNetwork(
                                        audioPath = audioPath,
                                        title = safeTitle,
                                        artist = safeArtist,
                                        context = context
                                    )
                                    withContext(Dispatchers.Main) {
                                        lrcLines = result.lines
                                        lyricsSource = result.source
                                        android.widget.Toast.makeText(context, "歌词刷新好啦~", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新歌词", tint = MaterialTheme.colorScheme.primary)
                            }

                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    OnlineLyricsRepository.clearCache(audioPath, context)
                                    CoverArtCache.invalidate(context, audioPath)
                                    withContext(Dispatchers.Main) {
                                        lrcLines = emptyList()
                                        lyricsSource = LyricsSource.LOCAL
                                        coverForceNetwork = true
                                        coverRefreshNonce++
                                        android.widget.Toast.makeText(context, "歌词与封面缓存已清除", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除歌词与封面", tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(8.dp))

                            // 👇 修复 1：把颜色获取放到 Canvas 外面 (Composable 作用域内)
                            val keepScreenOnColor =
                                if (isKeepScreenOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.3f
                                )
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { isKeepScreenOn = !isKeepScreenOn },
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
                                    drawCircle(
                                        color = keepScreenOnColor,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                                    )
                                    if (isKeepScreenOn) drawCircle(
                                        color = keepScreenOnColor,
                                        radius = size.width / 3.5f
                                    )
                                }
                            }

                            IconButton(onClick = {
                                scope.launch {
                                    CoverArtCache.invalidate(context, audioPath)
                                    coverForceNetwork = true
                                    coverRefreshNonce++
                                    android.widget.Toast.makeText(context, "正在重新获取封面…", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Filled.Image, "刷新封面", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val songs = AppDatabase.getDatabase(context).songDao().getAllSongs().first()
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "开始批量刷新网络封面…", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    val count = CoverArtCache.refreshAllOnlineCovers(context, songs)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "已刷新 $count 首无内嵌封面的歌曲", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) {
                                Icon(Icons.Filled.Sync, "刷新全部网络封面", tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = {
                                showInfoDialog = true
                            }) { Icon(Icons.Filled.MoreVert, "Info") }
                        }
                    }

                    if (isFullscreenLyrics && lrcLines.isNotEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { isFullscreenLyrics = !isFullscreenLyrics },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                contentPadding = PaddingValues(vertical = lyricsVerticalPadding)
                            ) {
                                itemsIndexed(lrcLines) { index, line ->
                                    LyricLineItem(
                                        text = line.text,
                                        timeMs = line.timeMs,
                                        isCurrent = index == currentLyricIndex,
                                        isPausedForInteraction = isLyricsPausedForInteraction,
                                        fontSizeSp = lyricsFontSize,
                                        onSeek = {
                                            mediaController?.seekTo(line.timeMs)
                                            currentPosition = line.timeMs
                                            isLyricsPausedForInteraction = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Row(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Top
                            ) {
                                PlayerTitleSection(
                                    title = title,
                                    artist = artist,
                                    spec = null,
                                    onArtistClick = onArtistClick,
                                    compact = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                spec?.let {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AudioSpecBadges(it)
                                        if (showGoldLogo) {
                                            Image(
                                                painterResource(id = R.drawable.hires_logo),
                                                null,
                                                modifier = Modifier.align(Alignment.CenterEnd)
                                                    .height(40.dp)
                                                    .padding(end = if (isCompactLandscape) 0.dp else 80.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                Spacer(Modifier.height(if (isCompactLandscape) 16.dp else 32.dp))

                                // 👇 还原：恢复固定尺寸排版。高度不足的手机强行缩小到 120dp，平板依旧保持完美的 200dp！
                                val imageSize = if (isCompactLandscape) 120.dp else 200.dp
                                val imageModifier = Modifier.playerCoverFrame(
                                    size = imageSize,
                                    corner = 16.dp,
                                    glowColor = animatedDominantColor
                                )

                                // 👇 高清渐进式加载 (无损画质 + 0延迟占位 + 双击红心 + 左右滑切歌)
                                AnimatedContent(
                                    targetState = audioPath,
                                    transitionSpec = {
                                        (fadeIn(tween(500)) + scaleIn(
                                            initialScale = 0.85f,
                                            animationSpec = tween(500)
                                        ))
                                            .togetherWith(
                                                fadeOut(tween(500)) + scaleOut(
                                                    targetScale = 1.15f,
                                                    animationSpec = tween(500)
                                                )
                                            )
                                    },
                                    label = "coverTransitionLandscape"
                                ) { path ->
                                    val lowResPlaceholder =
                                        remember(path) { AudioCache.getFromMemory(path)?.bitmap }
                                    var highResBitmap by remember(path) {
                                        mutableStateOf<ImageBitmap?>(
                                            null
                                        )
                                    }

                                    LaunchedEffect(path, coverRefreshNonce) {
                                        val forceNet = coverForceNetwork
                                        coverForceNetwork = false
                                        highResBitmap = withContext(Dispatchers.IO) {
                                            val dbSong = AppDatabase.getDatabase(context).songDao().getSongByPath(path)
                                            CoverArtCache.loadCover(
                                                context = context,
                                                path = path,
                                                title = dbSong?.title ?: title,
                                                artist = dbSong?.artist ?: artist,
                                                forceNetwork = forceNet,
                                                updateGlobalTheme = path == PlayerStateHolder.currentPath
                                            )
                                        }
                                    }

                                    // 🚨 核心手势：双击红心
                                    val doubleTapGesture = Modifier.pointerInput(path) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                if (!isFavorite) {
                                                    onFavoriteClick()
                                                    Toast.makeText(
                                                        context,
                                                        "已加入红心收藏 ❤️",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        )
                                    }

                                    // 🚨 核心手势：左右滑动切歌
                                    var dragOffset by remember { mutableFloatStateOf(0f) }
                                    val swipeGestureModifier = if (path == audioPath) {
                                        Modifier
                                            .graphicsLayer { translationX = dragOffset }
                                            .pointerInput(Unit) {
                                                detectHorizontalDragGestures(
                                                    onDragEnd = {
                                                        if (dragOffset > 150) mediaController?.seekToPrevious()
                                                        else if (dragOffset < -150) mediaController?.seekToNext()
                                                        dragOffset = 0f
                                                    },
                                                    onHorizontalDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragOffset =
                                                            (dragOffset + dragAmount).coerceIn(
                                                                -300f,
                                                                300f
                                                            )
                                                    }
                                                )
                                            }
                                    } else Modifier

                                    val displayBitmap = highResBitmap ?: lowResPlaceholder
                                    if (displayBitmap != null) {
                                        Image(
                                            bitmap = displayBitmap,
                                            contentDescription = "Cover",
                                            contentScale = ContentScale.Crop,
                                            modifier = imageModifier
                                                .then(doubleTapGesture)
                                                .then(swipeGestureModifier)
                                        )
                                    } else {
                                        AdvancedFluidCover(
                                            seedString = audioPath,
                                            customColors = fallbackColors, // 👈 传入外面 LaunchedEffect 也在用的同一组颜色
                                            iconSize = 64.dp,
                                            modifier = imageModifier
                                                .then(doubleTapGesture)
                                                .then(swipeGestureModifier)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(24.dp))
                            Column(
                                modifier = Modifier.weight(1.5f).fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    if (lrcLines.isNotEmpty()) {
                                        LazyColumn(
                                            state = listState,
                                            modifier = Modifier.fillMaxSize().clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { isFullscreenLyrics = !isFullscreenLyrics },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            contentPadding = PaddingValues(vertical = lyricsVerticalPadding)
                                        ) {
                                            itemsIndexed(lrcLines) { index, line ->
                                                LyricLineItem(
                                                    text = line.text,
                                                    timeMs = line.timeMs,
                                                    isCurrent = index == currentLyricIndex,
                                                    isPausedForInteraction = isLyricsPausedForInteraction,
                                                    fontSizeSp = lyricsFontSize,
                                                    onSeek = {
                                                        mediaController?.seekTo(line.timeMs)
                                                        currentPosition = line.timeMs
                                                        isLyricsPausedForInteraction = false
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        NoLyricsEmptyState(onImport = { lrcPickerInPlayer.launch("*/*") })
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                ControlsSection(isLandscapeLayout = true)
                            }
                        }
                    }
                }

            } else {
                // ── 竖屏布局 ──
                Column(
                    modifier = Modifier.fillMaxSize()
                        .padding(start = 48.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (isFullscreenLyrics) isFullscreenLyrics = false else onBackClick()
                        }) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                "Back",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (showLyrics || isFullscreenLyrics) {
                                TextButton(onClick = {
                                    lyricsFontSize = (lyricsFontSize - 2f).coerceAtLeast(12f)
                                }) {
                                    Text(
                                        "A-",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }; TextButton(onClick = {
                                    lyricsFontSize = (lyricsFontSize + 2f).coerceAtMost(36f)
                                }) {
                                    Text(
                                        "A+",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }; IconButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        // 为了防止串歌，云糯帮你从数据库里查最准确的名字哦
                                        val dbSong = AppDatabase.getDatabase(context).songDao().getSongByPath(audioPath)
                                        val safeTitle = dbSong?.title ?: title
                                        val safeArtist = dbSong?.artist ?: artist

                                        val result = OnlineLyricsRepository.refreshFromNetwork(
                                            audioPath = audioPath,
                                            title = safeTitle,
                                            artist = safeArtist,
                                            context = context
                                        )
                                        withContext(Dispatchers.Main) {
                                            lrcLines = result.lines
                                            lyricsSource = result.source
                                            android.widget.Toast.makeText(context, "歌词刷新好啦~", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "刷新歌词", tint = MaterialTheme.colorScheme.primary)
                                }

                                IconButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        OnlineLyricsRepository.clearCache(audioPath, context)
                                        CoverArtCache.invalidate(context, audioPath)
                                        withContext(Dispatchers.Main) {
                                            lrcLines = emptyList()
                                            lyricsSource = LyricsSource.LOCAL
                                            coverForceNetwork = true
                                            coverRefreshNonce++
                                            android.widget.Toast.makeText(context, "歌词与封面缓存已清除", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除歌词与封面", tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.width(8.dp))
                            }

                            IconButton(onClick = {
                                scope.launch {
                                    CoverArtCache.invalidate(context, audioPath)
                                    coverForceNetwork = true
                                    coverRefreshNonce++
                                    android.widget.Toast.makeText(context, "正在重新获取封面…", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Filled.Image, "刷新封面", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val songs = AppDatabase.getDatabase(context).songDao().getAllSongs().first()
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "开始批量刷新网络封面…", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    val count = CoverArtCache.refreshAllOnlineCovers(context, songs)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "已刷新 $count 首无内嵌封面的歌曲", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) {
                                Icon(Icons.Filled.Sync, "刷新全部网络封面", tint = MaterialTheme.colorScheme.primary)
                            }

                            // 👇 修复 2：竖屏同样提取颜色
                            val keepScreenOnColor =
                                if (isKeepScreenOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.3f
                                )
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { isKeepScreenOn = !isKeepScreenOn },
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
                                    drawCircle(
                                        color = keepScreenOnColor,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                                    )
                                    if (isKeepScreenOn) drawCircle(
                                        color = keepScreenOnColor,
                                        radius = size.width / 3.5f
                                    )
                                }
                            }

                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = {
                                showInfoDialog = true
                            }) { Icon(Icons.Filled.MoreVert, "Info") }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (lrcLines.isNotEmpty() && !isFullscreenLyrics) {
                            CoverLyricsSegmentedControl(
                                showLyrics = showLyrics,
                                onCoverSelect = { showLyrics = false; isFullscreenLyrics = false },
                                onLyricsSelect = { showLyrics = true; isFullscreenLyrics = false }
                            )
                        }
                        if (showGoldLogo && !showLyrics && !isFullscreenLyrics) {
                            Image(
                                painterResource(id = R.drawable.hires_logo),
                                null,
                                modifier = Modifier.align(Alignment.CenterEnd).height(55.dp)
                                    .padding(end = 20.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if ((showLyrics || isFullscreenLyrics) && lrcLines.isNotEmpty()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { isFullscreenLyrics = !isFullscreenLyrics },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                contentPadding = PaddingValues(vertical = lyricsVerticalPadding)
                            ) {
                                itemsIndexed(lrcLines) { index, line ->
                                    LyricLineItem(
                                        text = line.text,
                                        timeMs = line.timeMs,
                                        isCurrent = index == currentLyricIndex,
                                        isPausedForInteraction = isLyricsPausedForInteraction,
                                        fontSizeSp = lyricsFontSize,
                                        onSeek = {
                                            mediaController?.seekTo(line.timeMs)
                                            currentPosition = line.timeMs
                                            isLyricsPausedForInteraction = false
                                        }
                                    )
                                }
                            }
                        } else if (showLyrics && lrcLines.isEmpty()) {
                            NoLyricsEmptyState(onImport = { lrcPickerInPlayer.launch("*/*") })
                        } else {
                            val imageModifier = Modifier.playerCoverFrame(
                                size = 300.dp,
                                corner = 24.dp,
                                glowColor = animatedDominantColor
                            )

                            // 👇 高清渐进式加载 (无损画质 + 0延迟占位 + 双击红心 + 左右滑切歌)
                            AnimatedContent(
                                targetState = audioPath,
                                transitionSpec = {
                                    (fadeIn(tween(500)) + scaleIn(
                                        initialScale = 0.85f,
                                        animationSpec = tween(500)
                                    ))
                                        .togetherWith(
                                            fadeOut(tween(500)) + scaleOut(
                                                targetScale = 1.15f,
                                                animationSpec = tween(500)
                                            )
                                        )
                                },
                                label = "coverTransitionPortrait"
                            ) { path ->
                                val lowResPlaceholder =
                                    remember(path) { AudioCache.getFromMemory(path)?.bitmap }
                                var highResBitmap by remember(path) {
                                    mutableStateOf<ImageBitmap?>(
                                        null
                                    )
                                }

                                LaunchedEffect(path, coverRefreshNonce) {
                                    val forceNet = coverForceNetwork
                                    coverForceNetwork = false
                                    highResBitmap = withContext(Dispatchers.IO) {
                                        val dbSong = AppDatabase.getDatabase(context).songDao().getSongByPath(path)
                                        CoverArtCache.loadCover(
                                            context = context,
                                            path = path,
                                            title = dbSong?.title ?: title,
                                            artist = dbSong?.artist ?: artist,
                                            forceNetwork = forceNet,
                                            updateGlobalTheme = path == PlayerStateHolder.currentPath
                                        )
                                    }
                                }

                                // 🚨 核心手势：双击红心
                                val doubleTapGesture = Modifier.pointerInput(path) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            if (!isFavorite) {
                                                onFavoriteClick()
                                                Toast.makeText(
                                                    context,
                                                    "已加入红心收藏 ❤️",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    )
                                }

                                // 🚨 核心手势：左右滑动切歌
                                var dragOffset by remember { mutableFloatStateOf(0f) }
                                val swipeGestureModifier = if (path == audioPath) {
                                    Modifier
                                        .graphicsLayer { translationX = dragOffset }
                                        .pointerInput(Unit) {
                                            detectHorizontalDragGestures(
                                                onDragEnd = {
                                                    if (dragOffset > 150) mediaController?.seekToPrevious()
                                                    else if (dragOffset < -150) mediaController?.seekToNext()
                                                    dragOffset = 0f
                                                },
                                                onHorizontalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffset = (dragOffset + dragAmount).coerceIn(
                                                        -300f,
                                                        300f
                                                    )
                                                }
                                            )
                                        }
                                } else Modifier

                                val displayBitmap = highResBitmap ?: lowResPlaceholder
                                if (displayBitmap != null) {
                                    Image(
                                        bitmap = displayBitmap,
                                        contentDescription = "Cover",
                                        contentScale = ContentScale.Crop,
                                        modifier = imageModifier
                                            .then(doubleTapGesture)
                                            .then(swipeGestureModifier)
                                    )
                                } else {
                                    AdvancedFluidCover(
                                        seedString = audioPath,
                                        customColors = fallbackColors, // 👈 传入外面 LaunchedEffect 也在用的同一组颜色
                                        iconSize = 64.dp,
                                        modifier = imageModifier
                                            .then(doubleTapGesture)
                                            .then(swipeGestureModifier)
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = !isFullscreenLyrics) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(24.dp))
                            PlayerTitleSection(
                                title = title,
                                artist = artist,
                                spec = spec,
                                onArtistClick = onArtistClick
                            )
                            Spacer(Modifier.height(16.dp))
                            ControlsSection(isLandscapeLayout = false)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }

        if (showInfoDialog) AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("歌曲详情", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    detailedInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = { showInfoDialog = false }) { Text("关闭") }
            }
        )
        if (showEqDialog) EqDialog(onDismiss = { showEqDialog = false })

        if (showPlaylistSheet) {
            val playlistState = rememberLazyListState()
            val currentIndex = mediaController?.currentMediaItemIndex ?: -1
            LaunchedEffect(showPlaylistSheet) {
                if (showPlaylistSheet && currentIndex >= 0) playlistState.scrollToItem(
                    maxOf(0, currentIndex - 1)
                )
            }
            ModalBottomSheet(
                onDismissRequest = { showPlaylistSheet = false },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    PlaylistQueueHeader(
                        songCount = mediaController?.mediaItemCount ?: 0,
                        onDismiss = { showPlaylistSheet = false }
                    )
                    val count = mediaController?.mediaItemCount ?: 0
                    val currentList = remember(showPlaylistSheet) {
                        mutableStateListOf<MediaItem?>().apply {
                            for (i in 0 until count) add(mediaController?.getMediaItemAt(i))
                        }
                    }
                    val playingMediaId = mediaController?.currentMediaItem?.mediaId
                    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
                    var dragOffsetY by remember { mutableFloatStateOf(0f) }
                    LazyColumn(
                        state = playlistState,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    ) {
                        itemsIndexed(
                            items = currentList,
                            key = { index, item ->
                                "${
                                    item?.mediaId ?: java.util.UUID.randomUUID().toString()
                                }_$index"
                            }
                        ) { index, item ->
                            val isCurrentPlaying = playingMediaId == item?.mediaId;
                            val isBeingDragged = draggedItemIndex == index;
                            val itemHeightPx = with(LocalDensity.current) { 48.dp.toPx() };
                            val hoveredIndex = draggedItemIndex?.let {
                                (it + (dragOffsetY / itemHeightPx).roundToInt()).coerceIn(
                                    0,
                                    count - 1
                                )
                            }
                            val targetTranslationY =
                                if (isBeingDragged) dragOffsetY else if (draggedItemIndex != null && hoveredIndex != null) {
                                    if (draggedItemIndex!! < hoveredIndex && index in (draggedItemIndex!! + 1)..hoveredIndex) -itemHeightPx else if (draggedItemIndex!! > hoveredIndex && index in hoveredIndex until draggedItemIndex!!) itemHeightPx else 0f
                                } else 0f
                            val animatedTranslationY by animateFloatAsState(
                                targetValue = targetTranslationY,
                                label = "drag"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .zIndex(if (isBeingDragged) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = animatedTranslationY
                                        scaleX = if (isBeingDragged) 1.03f else 1f
                                        scaleY = if (isBeingDragged) 1.03f else 1f
                                        shadowElevation = if (isBeingDragged) 8f else 0f
                                        alpha = if (isBeingDragged) 0.92f else 1f
                                    }
                                    .pointerInput(item?.mediaId) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedItemIndex = index; dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            val from = draggedItemIndex;
                                            val offset = dragOffsetY; draggedItemIndex =
                                            null; dragOffsetY = 0f; if (from != null) {
                                            val to =
                                                (from + (offset / itemHeightPx).roundToInt()).coerceIn(
                                                    0,
                                                    count - 1
                                                ); if (from != to) {
                                                val movedItem =
                                                    currentList.removeAt(from); currentList.add(
                                                    to,
                                                    movedItem
                                                ); mediaController?.moveMediaItem(from, to)
                                            }
                                        }
                                        },
                                        onDragCancel = {
                                            draggedItemIndex = null; dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount -> change.consume(); dragOffsetY += dragAmount.y })
                                }.clickable {
                                    val realIdx =
                                        (0 until count).find { mediaController?.getMediaItemAt(it)?.mediaId == item?.mediaId }; if (realIdx != null) {
                                    mediaController?.seekTo(realIdx, 0L); mediaController?.play()
                                }; showPlaylistSheet = false
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlaylistQueueRow(
                                    index = index + 1,
                                    title = item?.mediaMetadata?.title?.toString() ?: "未知",
                                    artist = item?.mediaMetadata?.artist?.toString(),
                                    isPlaying = isCurrentPlaying,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Filled.DragHandle,
                                    "拖动排序",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f),
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val ts = ms / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", ts / 60, ts % 60)
}

@Composable
fun AdvancedFluidCover(
    seedString: String, // 仍然保留 seedString 用于计算随机偏移
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    customColors: List<Color>? = null // 👈 新增：允许传入外部算好的颜色
) {
    val seed = remember(seedString) { seedString.hashCode().toLong() }
    val random = remember(seed) { java.util.Random(seed) }

    // 如果外部没给颜色（比如在列表页），就自己生一个
    val colors = customColors ?: remember(seedString) { generateElegantColors(seedString) }

    val offsetX1 = remember(seed) { random.nextFloat() }
    val offsetY1 = remember(seed) { random.nextFloat() }
    val offsetX2 = remember(seed) { random.nextFloat() }
    val offsetY2 = remember(seed) { random.nextFloat() }

    Box(
        modifier = modifier
            .background(colors[0]) // 使用主色
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(colors[1].copy(alpha = 0.7f), Color.Transparent),
                        center = Offset(size.width * offsetX1, size.height * offsetY1),
                        radius = size.maxDimension * 0.8f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(colors[2].copy(alpha = 0.5f), Color.Transparent),
                        center = Offset(size.width * offsetX2, size.height * offsetY2),
                        radius = size.maxDimension * 0.9f
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = Color.White.copy(alpha = 0.3f)
        )
    }
}

fun generateElegantColors(seedString: String): List<Color> {
    val random = java.util.Random(seedString.hashCode().toLong())

    fun randomElegantColor(isDark: Boolean): Color {
        val h = random.nextFloat() * 360f // 随机色相
        val s = 0.5f + random.nextFloat() * 0.2f // 饱和度固定在 50%-70%，保证色彩鲜艳但不俗气
        val l = if (isDark) 0.2f + random.nextFloat() * 0.2f else 0.7f + random.nextFloat() * 0.1f // 亮度控制
        return Color.hsl(h, s, l)
    }

    // 返回三个颜色：主色（深点作为背景），辅助色1，辅助色2
    return listOf(
        randomElegantColor(true),
        randomElegantColor(false),
        randomElegantColor(true)
    )
}

@Composable
fun AnimatedEqIcon(isPlaying: Boolean, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    // 独立控制的 3 根弹簧动画
    val h1 = remember { androidx.compose.animation.core.Animatable(0.3f) }
    val h2 = remember { androidx.compose.animation.core.Animatable(0.4f) }
    val h3 = remember { androidx.compose.animation.core.Animatable(0.3f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // 播放时，无限循环跳动
            launch { while (isActive) { h1.animateTo(1f, tween(400, easing = LinearEasing)); h1.animateTo(0.3f, tween(400, easing = LinearEasing)) } }
            launch { while (isActive) { h2.animateTo(1f, tween(300, easing = LinearEasing)); h2.animateTo(0.4f, tween(300, easing = LinearEasing)) } }
            launch { while (isActive) { h3.animateTo(1f, tween(500, easing = LinearEasing)); h3.animateTo(0.3f, tween(500, easing = LinearEasing)) } }
        } else {
            // 暂停时，瞬间平滑降到底部并停止！
            launch { h1.animateTo(0.2f, tween(300)) }
            launch { h2.animateTo(0.2f, tween(300)) }
            launch { h3.animateTo(0.2f, tween(300)) }
        }
    }

    Row(modifier = modifier.height(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(h1.value).background(tint, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(h2.value).background(tint, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(h3.value).background(tint, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
    }
}
