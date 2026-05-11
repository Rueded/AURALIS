package com.example.mymusic

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

// ==========================================
// LRU 音频元数据缓存 (解决快速滑动卡顿)
// ==========================================
data class CachedAudioInfo(val spec: AudioSpec, val bitmap: ImageBitmap?)

// ==========================================
// 核心缓存与解析引擎
// ==========================================
object AudioCache {
    // 容量设为 1000 首，确保列表滚动的绝对丝滑
    private val memoryCache = LruCache<String, CachedAudioInfo>(1000)
    private val loadingMutexes = ConcurrentHashMap<String, Mutex>()

    fun getFromMemory(path: String): CachedAudioInfo? = memoryCache.get(path)

    suspend fun loadFromDisk(context: Context, song: Song): CachedAudioInfo? {
        return withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "audio_meta_cache")
            val safeKey = song.data.hashCode().toString()
            val txtFile = File(cacheDir, "$safeKey.txt")
            val imgFile = File(cacheDir, "$safeKey.webp")

            if (txtFile.exists()) {
                try {
                    val parts = txtFile.readText().split("|")
                    if (parts.size >= 5) {
                        val spec = AudioSpec(parts[0], parts[1].toLong(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt())
                        var bmp: ImageBitmap? = null
                        if (imgFile.exists()) {
                            // 读取硬盘时使用 RGB_565，内存占用直接减半
                            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                            bmp = BitmapFactory.decodeFile(imgFile.absolutePath, options)?.asImageBitmap()
                        }
                        val info = CachedAudioInfo(spec, bmp)
                        memoryCache.put(song.data, info)
                        return@withContext info
                    }
                } catch (e: Exception) {
                    Log.e("AudioCache", "读取磁盘缓存失败", e)
                }
            }
            null
        }
    }

    suspend fun extractAndSave(context: Context, song: Song): CachedAudioInfo {
        val mutex = loadingMutexes.getOrPut(song.data) { Mutex() }
        return mutex.withLock {
            memoryCache.get(song.data)?.let { return@withLock it }

            val info = withContext(Dispatchers.IO) {
                // 👇 【防爆红核心：把这三个兄弟全部放在最顶层定义，后面随便怎么用都不会找不到】
                var resultSpec = AudioSpec()
                var resultBitmap: Bitmap? = null
                var isDatabaseReady = false

                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(song.data)
                    val bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                    var rawSampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 44100
                    val albumStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim() ?: ""

                    var channels = 2
                    var isAudioVivid = false
                    try {
                        val extractor = android.media.MediaExtractor()
                        extractor.setDataSource(song.data)
                        for (i in 0 until extractor.trackCount) {
                            val fmt = extractor.getTrackFormat(i)
                            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                            if (mime.startsWith("audio/")) {
                                if (fmt.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                                    val c = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                                    if (c > channels) channels = c
                                }
                                if (mime.contains("av3a", true)) isAudioVivid = true
                            }
                        }
                        extractor.release()
                    } catch (e: Exception) {}

                    if (isAudioVivid || song.data.lowercase().contains("av3a")) channels = 12

                    var rawBitDepth = 16
                    try {
                        val extractor2 = android.media.MediaExtractor()
                        extractor2.setDataSource(song.data)
                        for (i in 0 until extractor2.trackCount) {
                            val fmt = extractor2.getTrackFormat(i)
                            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                            if (mime.startsWith("audio/")) {
                                if (fmt.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                                    rawBitDepth = when (fmt.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                                        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                                        android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                                        else -> 24
                                    }
                                }
                                break
                            }
                        }
                        extractor2.release()
                    } catch (e: Exception) {}

                    // 🚨 【数据库真值抢救行动】
                    try {
                        val db = AppDatabase.getDatabase(context.applicationContext)
                        val dbSong = db.songDao().getSongByPath(song.data)
                        if (dbSong != null) {
                            if (dbSong.bitDepth > 0) {
                                rawBitDepth = dbSong.bitDepth
                                isDatabaseReady = true // 👈 数据库有值了，亮起绿灯！
                            }
                            if (dbSong.samplingRate > 0) {
                                rawSampleRate = dbSong.samplingRate
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AudioCache", "读取数据库真值失败", e)
                    }

                    // 组装最终准确的 Spec
                    resultSpec = AudioSpec(
                        format = if (isAudioVivid) "flac-av3a" else song.data,
                        bitRate = bitRate,
                        sampleRate = rawSampleRate,
                        bitDepth = rawBitDepth,
                        channels = channels
                    )

                    // 提取封面
                    val picData = try { retriever.embeddedPicture } catch (e: Exception) { null }
                    retriever.release()

                    try {
                        if (picData != null) {
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = 2
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                            resultBitmap = BitmapFactory.decodeByteArray(picData, 0, picData.size, options)
                        } else {
                            // 🚨 终极防毒过滤：判断是否为系统虚构的未知专辑
                            val isUnknown = albumStr.isEmpty() ||
                                    albumStr.equals("Unknown", ignoreCase = true) ||
                                    albumStr.equals("Unknown album", ignoreCase = true) ||
                                    albumStr.equals("Music", ignoreCase = true)

                            // 只有名字正常的专辑，才允许向系统 MediaStore 要图片！
                            if (!isUnknown) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                                    val bmp = context.contentResolver.loadThumbnail(uri, android.util.Size(400, 400), null)
                                    resultBitmap = bmp.copy(Bitmap.Config.RGB_565, false)
                                } else {
                                    val artUri = android.net.Uri.parse("content://media/external/audio/albumart/${song.albumId}")
                                    context.contentResolver.openInputStream(artUri)?.use { stream ->
                                        val options = BitmapFactory.Options().apply {
                                            inSampleSize = 2
                                            inPreferredConfig = Bitmap.Config.RGB_565
                                        }
                                        resultBitmap = BitmapFactory.decodeStream(stream, null, options)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}

                } catch (e: Exception) {
                    Log.e("AudioCache", "提取元数据失败", e)
                }

                // =================写入缓存阶段=================
                val cacheDir = File(context.cacheDir, "audio_meta_cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val safeKey = song.data.hashCode().toString()

                try {
                    // 👇 刚才最上面定义的 isDatabaseReady 和 resultSpec 在这里直接拿来用
                    val isPotentiallyWrong = resultSpec.isLossless && resultSpec.bitDepth <= 16 && !isDatabaseReady
                    val shouldCache = !isPotentiallyWrong

                    if (shouldCache) {
                        File(cacheDir, "$safeKey.txt").writeText(
                            "${resultSpec.format}|${resultSpec.bitRate}|${resultSpec.sampleRate}|${resultSpec.bitDepth}|${resultSpec.channels}"
                        )
                        if (resultBitmap != null) {
                            File(cacheDir, "$safeKey.webp").outputStream().use {
                                resultBitmap!!.compress(Bitmap.CompressFormat.WEBP, 90, it)
                            }
                        }
                    } else {
                        Log.w("AudioCache", "⏳ [${File(song.data).nameWithoutExtension}] 真实位深未就绪，跳过缓存写入等待刷新")
                    }
                } catch (e: Exception) {
                    Log.e("AudioCache", "写入磁盘缓存失败: ${e.message}")
                }

                CachedAudioInfo(resultSpec, resultBitmap?.asImageBitmap())
            }

            memoryCache.put(song.data, info)
            loadingMutexes.remove(song.data)
            info
        }
    }
}

// ==========================================
// 音质分类引擎
// ==========================================
data class AudioSpec(
    val format: String = "",
    val bitRate: Long = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 16,
    val channels: Int = 2
) {
    val isLossless: Boolean get() =
        format.contains("flac", true) || format.contains("wav", true) ||
                format.contains("alac", true) || format.contains("ape", true)

    val isSpatial: Boolean get() = channels > 2

    val spatialLabel: String get() = when (channels) {
        3 -> "2.1"; 4 -> "4.0"; 5 -> "5.0"; 6 -> "5.1"
        7 -> "6.1"; 8 -> "7.1"; 10 -> "9.1"; 12 -> "7.1.4"
        else -> "${channels}CH"
    }

    val spatialColor: Color get() = when (channels) {
        3, 4 -> Color(0xFF0097A7); 5, 6 -> Color(0xFF3F51B5)
        7, 8 -> Color(0xFF7B1FA2); 10, 12 -> Color(0xFFF50057)
        else -> Color(0xFF00838F)
    }

    val level: AudioLevel get() = when {
        format.contains("dsf", true) || format.contains("dff", true) -> AudioLevel.DSD
        bitDepth >= 24 && sampleRate >= 352800 -> AudioLevel.DXD
        bitDepth >= 24 && sampleRate >= 192000 -> AudioLevel.MASTER
        bitDepth >= 24 && sampleRate >= 96000 -> AudioLevel.HI_RES_PLUS
        (bitDepth >= 24 && sampleRate >= 44100) || (bitDepth == 16 && sampleRate >= 96000) -> AudioLevel.HI_RES
        isLossless && sampleRate == 44100 && bitDepth == 16 -> AudioLevel.CD
        isLossless && bitDepth == 16 && sampleRate <= 48000 -> AudioLevel.SQ
        isLossless -> AudioLevel.LOSSLESS
        bitRate >= 320000 -> AudioLevel.HQ
        bitRate in 1..127999 -> AudioLevel.LQ
        else -> AudioLevel.STANDARD
    }

    val specText: String get() = "${level.label}  ${bitDepth}bit / ${sampleRate / 1000.0}kHz"
}

enum class AudioLevel(val label: String, val color: Color) {
    LQ("LQ", Color(0xFF795548)),
    STANDARD("标准", Color(0xFF888888)),
    HQ("HQ", Color(0xFF388E3C)),
    CD("CD", Color(0xFF00ACC1)),
    LOSSLESS("Lossless", Color(0xFF8E24AA)),
    SQ("Studio", Color(0xFF8E24AA)),
    HI_RES("Hi-Res", Color(0xFFFFA000)),
    HI_RES_PLUS("Hi-Res+", Color(0xFFC78B00)),
    MASTER("Master", Color(0xFFD84315)),
    DXD("DXD", Color(0xFF0097A7)),
    DSD("DSD", Color(0xFFE64A19))
}

// ==========================================
// MainActivity
// ==========================================
class MainActivity : ComponentActivity() {
    val shouldOpenPlayer = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MusicAppScreen(shouldOpenPlayer = shouldOpenPlayer)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent, caller: android.app.ComponentCaller) {
        super.onNewIntent(intent, caller)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == "OPEN_PLAYER_FULLSCREEN") shouldOpenPlayer.value = true
    }
}

// ==========================================
// 均衡器对话框
// ==========================================
@Composable
fun EqDialog(onDismiss: () -> Unit) {
    val eq = remember {
        try {
            if (PlaybackService.audioSessionId != 0)
                Equalizer(0, PlaybackService.audioSessionId).also { it.enabled = true }
            else null
        } catch (e: Exception) { null }
    }

    if (eq == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("均衡器") },
            text = { Text("当前设备不支持均衡器，或尚未开始播放任何歌曲") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
        return
    }

    val numBands = eq.numberOfBands.toInt()
    val bandRange = eq.bandLevelRange
    val minLevel = bandRange[0].toFloat()
    val maxLevel = bandRange[1].toFloat()
    val bandLevels = remember { mutableStateListOf<Int>().apply { repeat(numBands) { i -> add(eq.getBandLevel(i.toShort()).toInt()) } } }
    val numPresets = eq.numberOfPresets.toInt()
    val presetNames = remember { (0 until numPresets).map { eq.getPresetName(it.toShort()) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("均衡器", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (presetNames.isNotEmpty()) {
                    Text("预设方案", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(presetNames) { idx, name ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    eq.usePreset(idx.toShort())
                                    repeat(numBands) { i -> bandLevels[i] = eq.getBandLevel(i.toShort()).toInt() }
                                },
                                label = { Text(name, fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                repeat(numBands) { bandIdx ->
                    val freqRange = eq.getBandFreqRange(bandIdx.toShort())
                    val centerFreq = (freqRange[0] + freqRange[1]) / 2
                    val freqLabel = if (centerFreq >= 1000000) "${centerFreq / 1000000}k" else "${centerFreq / 1000}"
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("${freqLabel}Hz", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp), fontSize = 10.sp)
                        Slider(
                            value = bandLevels[bandIdx].toFloat(),
                            onValueChange = { newVal ->
                                val level = newVal.roundToInt().toShort()
                                bandLevels[bandIdx] = level.toInt()
                                eq.setBandLevel(bandIdx.toShort(), level)
                            },
                            valueRange = minLevel..maxLevel,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${bandLevels[bandIdx] / 100}dB",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.End,
                            fontSize = 10.sp,
                            color = when {
                                bandLevels[bandIdx] > 0 -> MaterialTheme.colorScheme.primary
                                bandLevels[bandIdx] < 0 -> MaterialTheme.colorScheme.error
                                else -> Color.Gray
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { repeat(numBands) { i -> eq.setBandLevel(i.toShort(), 0); bandLevels[i] = 0 } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("重置全部") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

// ==========================================
// MusicAppScreen
// ==========================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun MusicAppScreen(shouldOpenPlayer: MutableState<Boolean>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE) }

    val db = remember { AppDatabase.getDatabase(context) }
    val dao = remember { db.songDao() }

    val allSongs by dao.getAllSongs().collectAsState(initial = emptyList())
    val favSongs by dao.getFavoriteSongs().collectAsState(initial = emptyList())
    val topSongs by dao.getMostPlayedSongs().collectAsState(initial = emptyList())
    // 👇 新增：获取所有歌单数据
    val allPlaylists by dao.getAllPlaylists().collectAsState(initial = emptyList())
    var enableReplayGain by remember { mutableStateOf(prefs.getBoolean("enable_replay_gain", false)) }

    var pcServerIp by remember { mutableStateOf(prefs.getString("server_ip", "192.168.") ?: "192.168.") }
    var savedFolderUriStr by remember { mutableStateOf(prefs.getString("sync_folder", null)) }
    var allowedFolders by remember { mutableStateOf(prefs.getStringSet("allowed_folders", setOf()) ?: setOf()) }

    var hasPermission by remember { mutableStateOf(false) }
    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    var currentTitle by remember { mutableStateOf<String?>(null) }
    var currentArtist by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentArtwork by remember { mutableStateOf<ByteArray?>(null) }

    var showFullScreenPlayer by rememberSaveable { mutableStateOf(false) }
    var currentAudioPath by rememberSaveable { mutableStateOf("") }

    var searchQuery by remember { mutableStateOf("") }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleMode by remember { mutableStateOf(false) }

    var sortType by remember { mutableStateOf(prefs.getString("sort_type", "Name") ?: "Name") }
    var isAscending by remember { mutableStateOf(prefs.getBoolean("is_ascending", true)) }
    var expandedSortMenu by remember { mutableStateOf(false) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var sleepTimerSeconds by remember { mutableLongStateOf(0L) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    val mainListState = rememberLazyListState()
    var allowMarquee by remember { mutableStateOf(true) }
    val isScrolling = mainListState.isScrollInProgress

    var isPcMode by remember { mutableStateOf(PcAudioReceiver.isReceiving) }

    var songToAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }

    LaunchedEffect(isScrolling) {
        if (isScrolling) {
            allowMarquee = false
        } else {
            delay(1500)
            allowMarquee = true
        }
    }

    LaunchedEffect(sleepTimerSeconds) {
        if (sleepTimerSeconds > 0) {
            delay(1000)
            sleepTimerSeconds--
            if (sleepTimerSeconds == 0L) {
                mediaController?.pause()
                Toast.makeText(context, "睡眠定时器：已暂停播放 💤", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(sortType, isAscending) {
        prefs.edit().putString("sort_type", sortType).putBoolean("is_ascending", isAscending).apply()
    }

    LaunchedEffect(shouldOpenPlayer.value) {
        if (shouldOpenPlayer.value && currentTitle != null) {
            showFullScreenPlayer = true
            shouldOpenPlayer.value = false
        }
    }

    // 👇 修改 1：引入 PagerState，废弃原本的 selectedTab
    val tabs = listOf("全部歌曲", "红心收藏", "最近常听", "歌手聚合", "我的歌单")
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    val qualityKeywordMap = mapOf(
        "flac" to listOf(".flac"), "wav" to listOf(".wav"), "mp3" to listOf(".mp3"),
        "aac" to listOf(".aac", ".m4a"), "dsf" to listOf(".dsf"), "dff" to listOf(".dff"),
        "lossless" to listOf(".flac", ".wav", ".ape", ".alac"),
        "hires" to listOf(".flac", ".wav"), "hi-res" to listOf(".flac", ".wav"),
        "spatial" to listOf("5.1", "7.1", "atmos", "spatial"),
        "5.1" to listOf("5.1"), "7.1" to listOf("7.1"),
        "atmos" to listOf("atmos"), "hq" to listOf(".mp3"),
        "dsd" to listOf(".dsf", ".dff")
    )

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSelectionDialog by remember { mutableStateOf(false) }
    var showDownloadingDialog by remember { mutableStateOf(false) }
    var missingSongsList by remember { mutableStateOf<List<SyncItem>>(emptyList()) }
    var syncLog by remember { mutableStateOf("") }
    var syncProgress by remember { mutableFloatStateOf(0f) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicatesList by remember { mutableStateOf<List<List<Song>>>(emptyList()) }

    var pendingDeleteSong by remember { mutableStateOf<Song?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingDeleteSong?.let { song ->
                scope.launch(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data)) }
                Toast.makeText(context, "授权删除成功", Toast.LENGTH_SHORT).show()
                // 👇 加上这行：如果是在查重页面触发了系统授权删除，删完要立刻把这首歌从查重列表里拿掉
                duplicatesList = duplicatesList.map { g -> g.filter { it.id != song.id } }.filter { it.size > 1 }
            }
        }
        pendingDeleteSong = null
    }

    // 👇 核心重构：将点击播放、插队播放、删除原封不动提取成通用 Lambda，保证在 Pager 中一点代码都不丢失
    val onSongClickAction: (Song, Int, List<Song>) -> Unit = { song, index, currentList ->
        // 👇 1. 核心防御：在播放前检查物理文件是否还存活！
        val file = java.io.File(song.data)
        if (!file.exists()) {
            // 发现幽灵文件！提示用户并自动清理数据库
            android.widget.Toast.makeText(context, "文件已在外部被删除，正在清理列表...", android.widget.Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.IO) {
                // 从总库中删除
                db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data))
                // 顺手从关联的歌单里也清掉
                db.openHelper.writableDatabase.execSQL("DELETE FROM playlist_songs WHERE songPath = ?", arrayOf(song.data))
            }
        } else {
            // 👇 2. 文件存在，走正常的播放逻辑
            if (currentAudioPath == song.data) showFullScreenPlayer = true
            else {
                mediaController?.let { controller ->
                    val mediaItems = currentList.map { s ->
                        val metadata = androidx.media3.common.MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).build()
                        androidx.media3.common.MediaItem.Builder().setMediaId(s.data).setUri(s.data).setMediaMetadata(metadata).build()
                    }
                    controller.setMediaItems(mediaItems, index, 0L); controller.prepare(); controller.play()
                }
            }
        }
    }

    val onSongPlayNextAction: (Song) -> Unit = { song ->
        mediaController?.let { controller ->
            val currentIdx = controller.currentMediaItemIndex

            if (currentIdx != androidx.media3.common.C.INDEX_UNSET) {
                val nextIdx = currentIdx + 1

                // 1. 检查：如果这首歌已经是紧挨着的下一首了，直接拦截！
                if (nextIdx < controller.mediaItemCount && controller.getMediaItemAt(nextIdx).mediaId == song.data) {
                    Toast.makeText(context, "已经是下一首啦", Toast.LENGTH_SHORT).show()
                    return@let // 退出，坚决不重复添加
                }

                // 2. 检查：这首歌是不是在播放队列的更后面？
                var existingIndex = -1
                for (i in nextIdx until controller.mediaItemCount) {
                    if (controller.getMediaItemAt(i).mediaId == song.data) {
                        existingIndex = i
                        break
                    }
                }

                if (existingIndex != -1) {
                    // 场景 A：已经在后面的队列里了，我们就把它“提拔”上来，不产生重复！
                    controller.moveMediaItem(existingIndex, nextIdx)
                    Toast.makeText(context, "已移至下一首播放", Toast.LENGTH_SHORT).show()
                } else {
                    // 场景 B：完全是一首新歌，正常插入
                    val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build()
                    val mediaItem = MediaItem.Builder().setMediaId(song.data).setUri(song.data).setMediaMetadata(metadata).build()
                    controller.addMediaItem(nextIdx, mediaItem)
                    Toast.makeText(context, "已添加到下一首", Toast.LENGTH_SHORT).show()
                }
            } else {
                // 如果当前什么都没在播放，就直接播放这首歌
                val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build()
                val mediaItem = MediaItem.Builder().setMediaId(song.data).setUri(song.data).setMediaMetadata(metadata).build()
                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.play()
            }
        }
    }

    val onSongDeleteAction: (Song) -> Unit = { song ->
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
            if (context.contentResolver.delete(uri, null, null) > 0) {
                scope.launch(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data)) }
                Toast.makeText(context, "彻底删除成功", Toast.LENGTH_SHORT).show()
            } else {
                if (File(song.data).delete()) {
                    scope.launch(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data)) }
                    Toast.makeText(context, "彻底删除成功", Toast.LENGTH_SHORT).show()
                } else { Toast.makeText(context, "删除失败，文件可能被占用", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                pendingDeleteSong = song
                deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val recoverable = e as? android.app.RecoverableSecurityException
                if (recoverable != null) {
                    pendingDeleteSong = song
                    deleteLauncher.launch(IntentSenderRequest.Builder(recoverable.userAction.actionIntent.intentSender).build())
                } else Toast.makeText(context, "删除受限", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, "删除受限", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(context, "删除出错", Toast.LENGTH_SHORT).show() }
    }

    val fetchSongsList = {
        syncLog = "正在连接电脑获取清单..."
        showDownloadingDialog = true
        scope.launch {
            try {
                val folderUri = savedFolderUriStr?.let { android.net.Uri.parse(it) }
                missingSongsList = SyncManager.fetchMissingSongs(context, pcServerIp, allSongs, folderUri)
                showDownloadingDialog = false
                if (missingSongsList.isEmpty()) {
                    syncLog = "手机已经是最新，没有缺少的歌曲！"
                    showDownloadingDialog = true; delay(2000); showDownloadingDialog = false
                } else showSelectionDialog = true
            } catch (e: Exception) {
                syncLog = "连接失败，请检查电脑 IP 是否正确、且在同一 Wi-Fi"
                delay(3000); showDownloadingDialog = false
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            savedFolderUriStr = uri.toString()
            prefs.edit().putString("sync_folder", uri.toString()).apply()
            showSettingsDialog = false; fetchSongsList()
        }
    }

    val scanWhitelistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val folderName = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: return@rememberLauncherForActivityResult
            val newSet = allowedFolders.toMutableSet().apply { add(folderName) }
            allowedFolders = newSet
            prefs.edit().putStringSet("allowed_folders", newSet).apply()
            scope.launch { MusicUtils.syncLocalMusicToDatabase(context, dao, newSet) }
        }
    }

    val batchLrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                var successCount = 0; var failCount = 0
                uris.forEach { uri ->
                    try {
                        val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        val fileName = cursor?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@forEach
                        val songName = fileName.substringBeforeLast(".")
                        val matchedSong = allSongs.find {
                            it.title.equals(songName, true) || File(it.data).nameWithoutExtension.equals(songName, true) ||
                                    songName.contains(it.title, true) || it.title.contains(songName, true)
                        }
                        if (matchedSong != null) {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val destFile = File(File(matchedSong.data).parent, "${File(matchedSong.data).nameWithoutExtension}.lrc")
                            inputStream?.use { input -> destFile.outputStream().use { output -> output.write(input.readBytes()) } }
                            successCount++
                        } else failCount++
                    } catch (e: Exception) { failCount++ }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "批量导入完成：成功 $successCount 首，未匹配 $failCount 首", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        hasPermission = isGranted
        if (isGranted) { scope.launch { MusicUtils.syncLocalMusicToDatabase(context, dao, allowedFolders) } }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            mediaController = controller
            repeatMode = controller.repeatMode
            shuffleMode = controller.shuffleModeEnabled
            controller.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    currentTitle = mediaMetadata.title?.toString()
                    currentArtist = mediaMetadata.artist?.toString()
                    currentArtwork = mediaMetadata.artworkData
                }
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentAudioPath = mediaItem?.mediaId ?: ""

                    // 👇 修复后的 ReplayGain 核心智能调音逻辑
                    scope.launch(Dispatchers.IO) {
                        val path = currentAudioPath
                        if (path.isNotEmpty() && enableReplayGain) {
                            // 1. 获取所有歌曲流并拿到最新的一次快照
                            val allSongsList = dao.getAllSongs().first()

                            // 2. 找到当前正在播放的这首歌
                            val currentSongDb = allSongsList.find { it.data == path }

                            // 3. 拿出它的增益值
                            val gainDb = currentSongDb?.replayGain ?: 0f

                            // 4. 增益换算公式 (需要 import kotlin.math.pow)
                            val linearVolume = (10.0).pow(gainDb / 20.0).toFloat().coerceIn(0.1f, 1.0f)

                            withContext(Dispatchers.Main) {
                                // 偷偷调整 ExoPlayer 的内部音量
                                mediaController?.setVolume(linearVolume)
                            }
                        } else {
                            // 没开开关，或者路径为空，保持 100% 原始输出
                            withContext(Dispatchers.Main) {
                                mediaController?.setVolume(1.0f)
                            }
                        }
                    }
                }
                override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
                override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffleMode = enabled }
            })
        }, ContextCompat.getMainExecutor(context))
    }

    val currentSong = remember(currentAudioPath, allSongs) { allSongs.find { it.data == currentAudioPath } }
    val isFavorite = currentSong?.isFavorite == true
    var isCurrentSongCounted by remember { mutableStateOf(false) }

    LaunchedEffect(currentAudioPath) {
        isCurrentSongCounted = false
    }

    LaunchedEffect(currentAudioPath, isPlaying) {
        if (isPlaying && currentAudioPath.isNotEmpty() && !isCurrentSongCounted) {
            while(isActive) {
                val pos = mediaController?.currentPosition ?: 0L
                if (pos >= 30_000L) {
                    dao.incrementPlayCount(currentAudioPath, System.currentTimeMillis())
                    // 👇 新增历史记录写入，记录下精确听歌时间，方便以后做年度报表
                    dao.insertHistory(PlayHistory(songPath = currentAudioPath, timestamp = System.currentTimeMillis(), durationListened = 30000L))
                    isCurrentSongCounted = true
                    break
                }
                delay(1000)
            }
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.example.mymusic.SYNC_COMPLETED") {
                    android.util.Log.d("MusicApp", "📢 收到同步完成广播！开始入库...") // 👈 加这行
                    scope.launch {
                        ctx?.let {
                            // 因为上面已经定义了 dao 和 allowedFolders，这里就不会再报错了！
                            MusicUtils.syncLocalMusicToDatabase(it, dao, allowedFolders)
                            android.widget.Toast.makeText(it, "新歌已自动入库！", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        val filter = android.content.IntentFilter("com.example.mymusic.SYNC_COMPLETED")
        // 兼容 Android 14+ 的广播注册安全要求
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            // 当离开页面时，销毁监听器，防止内存泄漏
            context.unregisterReceiver(receiver)
        }
    }

    // ── Scaffold 移到最外层，永远不销毁 ──
    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = currentTitle != null && !showFullScreenPlayer,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(300)),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(200))
            ) {
                if (currentTitle != null) {
                    MiniPlayerBar(
                        title = currentTitle!!, artist = currentArtist ?: "未知歌手", isPlaying = isPlaying,
                        onPreviousClick = {
                            mediaController?.let { controller ->
                                val currentIdx = controller.currentMediaItemIndex
                                if (currentIdx > 0) { controller.seekTo(currentIdx - 1, 0L); controller.play() }
                                else controller.seekTo(0, 0L)
                            }
                        },
                        onPlayPauseClick = { if (isPlaying) mediaController?.pause() else mediaController?.play() },
                        onNextClick = { mediaController?.seekToNext() },
                        onBarClick = { showFullScreenPlayer = true }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).windowInsetsPadding(WindowInsets.safeDrawing)) {

            AnimatedVisibility(visible = sleepTimerSeconds > 0) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DarkMode, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(6.dp))
                        Text("睡眠定时器：${sleepTimerSeconds / 60}:${String.format("%02d", sleepTimerSeconds % 60)} 后暂停", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = { sleepTimerSeconds = 0L }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("取消", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索歌名/歌手/格式") },
                    leadingIcon = { Icon(Icons.Filled.Search, "Search") },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), singleLine = true
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { expandedSortMenu = true }) { Icon(Icons.Filled.Sort, "Sort", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(expanded = expandedSortMenu, onDismissRequest = { expandedSortMenu = false }) {
                        listOf("Name" to "按名称", "Date" to "按日期", "Size" to "按大小").forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label + if (sortType == type) (if (isAscending) " ↑" else " ↓") else "", fontWeight = if (sortType == type) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { if (sortType == type) isAscending = !isAscending else { sortType = type; isAscending = true }; expandedSortMenu = false }
                            )
                        }
                    }
                }
                IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                FilledTonalIconButton(onClick = { if (pcServerIp.endsWith(".") || savedFolderUriStr == null) showSettingsDialog = true else fetchSongsList() }, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Sync, "Sync") }
            }

            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                edgePadding = 16.dp,
                containerColor = Color.Transparent,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title, fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            if (hasPermission) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.Top
                ) { pageIndex ->
                    when (pageIndex) {
                        0, 1 -> {
                            val currentBaseList = if (pageIndex == 0) allSongs else favSongs
                            val currentProcessedSongs = remember(currentBaseList, searchQuery, sortType, isAscending) {
                                val lowerQuery = searchQuery.lowercase().trim()
                                val filtered = if (lowerQuery.isEmpty()) currentBaseList else {
                                    val extraKeywords = qualityKeywordMap[lowerQuery] ?: listOf(lowerQuery)
                                    currentBaseList.filter { song ->
                                        song.title.contains(lowerQuery, true) || song.artist.contains(lowerQuery, true) || extraKeywords.any { kw -> song.data.lowercase().contains(kw) }
                                    }
                                }
                                val sorted = when (sortType) { "Date" -> filtered.sortedBy { it.dateModified }; "Size" -> filtered.sortedBy { it.size }; else -> filtered.sortedBy { it.title } }
                                if (isAscending) sorted else sorted.reversed()
                            }

                            // 1. 定义刷新状态
                            var isRefreshing by remember { mutableStateOf(false) }
                            val pullRefreshState = rememberPullToRefreshState()

                            // 2. 使用 PullToRefreshBox 包裹
                            PullToRefreshBox(
                                isRefreshing = isRefreshing,
                                onRefresh = {
                                    if (pageIndex == 0) {
                                        isRefreshing = true
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                MusicUtils.syncLocalMusicToDatabase(context, dao, allowedFolders)
                                            }
                                            isRefreshing = false
                                            android.widget.Toast.makeText(context, "列表已刷新", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                state = pullRefreshState,
                                modifier = Modifier.fillMaxSize(),
                                indicator = {
                                    if (pageIndex == 0) {
                                        PullToRefreshDefaults.Indicator(
                                            state = pullRefreshState,
                                            isRefreshing = isRefreshing,
                                            modifier = Modifier.align(Alignment.TopCenter),
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            ) {
                                if (currentProcessedSongs.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            // 关键：即使为空也要能划动，确保能拉出刷新球
                                            .verticalScroll(rememberScrollState()),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            if (pageIndex == 1) "还没有红心收藏哦，快去添加吧！" else "下拉刷新试试~",
                                            color = Color.Gray
                                        )
                                    }
                                } else {
                                    val listState = if (pageIndex == 0) mainListState else rememberLazyListState()

                                    LaunchedEffect(sortType, isAscending) {
                                        if (listState.firstVisibleItemIndex > 0) {
                                            listState.scrollToItem(0)
                                        }
                                    }

                                    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                                        itemsIndexed(items = currentProcessedSongs, key = { _, song -> song.data }) { index, song ->
                                            SongItemUI(
                                                song = song, index = index, isPlaying = currentAudioPath == song.data, allowMarquee = allowMarquee,
                                                onClick = { onSongClickAction(song, index, currentProcessedSongs) },
                                                onPlayNext = { onSongPlayNextAction(song) },
                                                onAddToPlaylist = { songToAddToPlaylist = song },
                                                onDelete = { onSongDeleteAction(song) }
                                            )
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            val activeTopList = topSongs.filter { it.playCount > 0 }
                            if (activeTopList.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("多听几首歌，这里就会满载回忆~", color = Color.Gray) }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    LazyColumn(modifier = Modifier.weight(1f)) {
                                        itemsIndexed(items = activeTopList, key = { _, song -> song.data }) { index, song ->
                                            ListItem(
                                                headlineContent = { Text(song.title, maxLines = 1) },
                                                supportingContent = {
                                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                                    val dateStr = if (song.lastPlayed > 0) sdf.format(java.util.Date(song.lastPlayed)) else "从未"
                                                    Text("${song.artist} • 最近: $dateStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                                },
                                                trailingContent = {
                                                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                                                        Text("${song.playCount}次", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                                    }
                                                },
                                                modifier = Modifier.clickable { onSongClickAction(song, index, activeTopList) }
                                            )
                                            HorizontalDivider()
                                        }
                                    }
                                    Card(modifier = Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.BarChart, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                            Spacer(Modifier.width(16.dp))
                                            Column {
                                                val totalPlays = activeTopList.sumOf { it.playCount }
                                                Text("听歌大数据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                                Text("累计播放 $totalPlays 次 • 已解锁 ${activeTopList.size} 首心头好", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        3 -> {
                            val artistGroups = remember(allSongs) { allSongs.groupBy { it.artist }.toList().sortedBy { it.first } }
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(artistGroups) { (artistName, songs) ->
                                    ListItem(
                                        headlineContent = { Text(artistName, fontWeight = FontWeight.Bold) },
                                        supportingContent = { Text("共 ${songs.size} 首单曲", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                                        leadingContent = {
                                            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                            }
                                        },
                                        modifier = Modifier.clickable { searchQuery = artistName; scope.launch { pagerState.animateScrollToPage(0) } }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }

                        4 -> {
                            if (selectedPlaylist == null) {
                                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                    Button(onClick = { showNewPlaylistDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("新建自定义歌单")
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    if (allPlaylists.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("空空如也，去创建第一个歌单吧", color = Color.Gray) }
                                    } else {
                                        LazyColumn {
                                            items(allPlaylists) { playlist ->
                                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                                ListItem(
                                                    headlineContent = { Text(playlist.name, fontWeight = FontWeight.Bold) },
                                                    supportingContent = { Text("创建于 ${sdf.format(java.util.Date(playlist.createdAt))}") },
                                                    leadingContent = { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.primary) },
                                                    trailingContent = {
                                                        IconButton(onClick = { scope.launch(Dispatchers.IO) { dao.deletePlaylist(playlist.id) } }) {
                                                            Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                                        }
                                                    },
                                                    modifier = Modifier.clickable { selectedPlaylist = playlist }
                                                )
                                                HorizontalDivider()
                                            }
                                        }
                                    }
                                }
                            } else {
                                val playlistSongs by dao.getSongsInPlaylist(selectedPlaylist!!.id).collectAsState(initial = emptyList())
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { selectedPlaylist = null }) { Icon(Icons.Filled.ArrowBack, null) }
                                        Spacer(Modifier.width(8.dp))
                                        Text(selectedPlaylist!!.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        Text("共 ${playlistSongs.size} 首", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                    }
                                    HorizontalDivider()
                                    if (playlistSongs.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("歌单还是空的，快去添加歌曲吧！", color = Color.Gray) }
                                    } else {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            itemsIndexed(items = playlistSongs, key = { _, song -> song.data }) { index, song ->
                                                SongItemUI(
                                                    song = song, index = index, isPlaying = currentAudioPath == song.data, allowMarquee = allowMarquee,
                                                    onClick = { onSongClickAction(song, index, playlistSongs) },
                                                    onPlayNext = { onSongPlayNextAction(song) },
                                                    onAddToPlaylist = { songToAddToPlaylist = song },
                                                    onRemoveFromPlaylist = {
                                                        scope.launch(Dispatchers.IO) {
                                                            dao.removeSongFromPlaylist(selectedPlaylist!!.id, song.data)
                                                            withContext(Dispatchers.Main) { Toast.makeText(context, "已从歌单移除", Toast.LENGTH_SHORT).show() }
                                                        }
                                                    },
                                                    onDelete = { onSongDeleteAction(song) }
                                                )
                                                HorizontalDivider()
                                            }
                                        }
                                    }
                                }
                                BackHandler(enabled = selectedPlaylist != null) { selectedPlaylist = null }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 全屏播放器覆盖在 Scaffold 上面 ──
    AnimatedContent(
        targetState = showFullScreenPlayer && currentTitle != null,
        transitionSpec = {
            if (targetState) {
                (slideInVertically(initialOffsetY = { it }, animationSpec = tween(420, easing = FastOutSlowInEasing)) + fadeIn(tween(350))).togetherWith(fadeOut(tween(200)))
            } else {
                fadeIn(tween(300)).togetherWith(slideOutVertically(targetOffsetY = { it }, animationSpec = tween(380, easing = FastOutSlowInEasing)) + fadeOut(tween(280)))
            }
        },
        label = "screenTransition"
    ) { isVisible ->
        if (isVisible) {
            Box(modifier = Modifier.fillMaxSize()) {
                FullScreenPlayer(
                    title = currentTitle!!, artist = currentArtist ?: "未知歌手",
                    isPlaying = isPlaying, artwork = currentArtwork, audioPath = currentAudioPath,
                    mediaController = mediaController, repeatMode = repeatMode, shuffleMode = shuffleMode,
                    sleepTimerSeconds = sleepTimerSeconds, audioManager = audioManager,
                    isFavorite = isFavorite,
                    onFavoriteClick = { scope.launch { dao.updateFavoriteStatus(currentAudioPath, !isFavorite) } },
                    onSleepTimerClick = { showSleepTimerDialog = true },
                    onBackClick = { showFullScreenPlayer = false },
                    onArtistClick = { clickedArtist -> searchQuery = clickedArtist; showFullScreenPlayer = false }
                )
                BackHandler { showFullScreenPlayer = false }
            }
        }
    }

    BackHandler(enabled = searchQuery.isNotEmpty() && !showFullScreenPlayer) { searchQuery = "" }

    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("睡眠定时器", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (sleepTimerSeconds > 0) {
                        Text("当前：${sleepTimerSeconds / 60}分${sleepTimerSeconds % 60}秒后暂停", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { sleepTimerSeconds = 0L; showSleepTimerDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("取消定时器") }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    listOf(15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "1 小时", 90 to "90 分钟").forEach { (minutes, label) ->
                        TextButton(onClick = { sleepTimerSeconds = minutes * 60L; showSleepTimerDialog = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepTimerDialog = false }) { Text("取消") } }
        )
    }

    if (showSettingsDialog) {
        // 记得在作用域顶部确保有这个变量定义：
        // var isPcMode by remember { mutableStateOf(PcAudioReceiver.isReceiving) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("音乐管家设置", fontWeight = FontWeight.W600) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    // 1. 音量标准化
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            enableReplayGain = !enableReplayGain
                            prefs.edit().putBoolean("enable_replay_gain", enableReplayGain).apply()
                            if (!enableReplayGain) mediaController?.setVolume(1.0f)
                        }.padding(vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("音量标准化 (ReplayGain)", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                            Text("自动平衡不同歌曲响度，保护听力", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = enableReplayGain,
                            onCheckedChange = {
                                enableReplayGain = it
                                prefs.edit().putBoolean("enable_replay_gain", it).apply()
                                if (!it) mediaController?.setVolume(1.0f)
                            }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 👇👇👇 2. PC 有线音箱模式 (新增) 👇👇👇
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (isPcMode) {
                                PcAudioReceiver.stop()
                                isPcMode = false
                            } else {
                                scope.launch(Dispatchers.IO) { PcAudioReceiver.startListening() }
                                isPcMode = true
                            }
                        }.padding(vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("PC 有线音箱模式", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge, color = if(isPcMode) MaterialTheme.colorScheme.primary else Color.Unspecified)
                            Text(if (isPcMode) "正在监听端口 (USB 传输中)" else "将手机变为 PC 的零延迟音箱", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = isPcMode,
                            onCheckedChange = {
                                if (it) {
                                    scope.launch(Dispatchers.IO) { PcAudioReceiver.startListening() }
                                    isPcMode = true
                                } else {
                                    PcAudioReceiver.stop()
                                    isPcMode = false
                                }
                            }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    // 👆👆👆 结束新增 👆👆👆

                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = pcServerIp, onValueChange = { pcServerIp = it; prefs.edit().putString("server_ip", it).apply() }, label = { Text("电脑局域网 IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    Spacer(Modifier.height(16.dp))
                    Text("同步保存路径", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { folderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text(if (savedFolderUriStr != null) "修改下载文件夹" else "选择下载文件夹") }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Spacer(Modifier.height(16.dp))
                    Text("本地扫描白名单", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (allowedFolders.isEmpty()) Text("当前扫描全盘", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    allowedFolders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(folder, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { val newSet = allowedFolders.toMutableSet().apply { remove(folder) }; allowedFolders = newSet; prefs.edit().putStringSet("allowed_folders", newSet).apply(); scope.launch { MusicUtils.syncLocalMusicToDatabase(context, dao, newSet) } }) { Icon(Icons.Filled.Close, "Remove", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline) }
                        }
                    }
                    OutlinedButton(onClick = { scanWhitelistLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("添加扫描路径") }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { batchLrcPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Subtitles, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("批量导入 LRC 歌词")
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                withContext(Dispatchers.Main) { Toast.makeText(context, "正在清空旧数据，开始深度解析...", Toast.LENGTH_SHORT).show() }
                                File(context.cacheDir, "audio_meta_cache").deleteRecursively()
                                db.openHelper.writableDatabase.execSQL("DELETE FROM songs")
                                MusicUtils.syncLocalMusicToDatabase(context, dao, allowedFolders)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "深度解析完成！", Toast.LENGTH_LONG).show()
                                    showSettingsDialog = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("强制深度解析全部歌曲") }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            duplicatesList = allSongs.groupBy { song ->
                                val titleNorm = song.title.trim().lowercase()
                                val artistNorm = song.artist.trim().lowercase()
                                if (titleNorm != "未知歌名" && titleNorm.isNotEmpty()) "${titleNorm}_${artistNorm}"
                                else song.data.substringAfterLast("/").substringBeforeLast(".").trim().lowercase()
                            }.filter { it.value.size > 1 }.values.toList()

                            if (duplicatesList.isEmpty()) Toast.makeText(context, "未发现重复歌曲", Toast.LENGTH_SHORT).show()
                            else { showSettingsDialog = false; showDuplicateDialog = true }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("智能检测重复歌曲") }
                    Spacer(Modifier.height(8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("完成") } }
        )
    }

    if (showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { showDuplicateDialog = false },
            containerColor = MaterialTheme.colorScheme.surface, // 去掉花哨底色
            title = { Text("清理重复歌曲", fontWeight = FontWeight.W600, fontSize = 20.sp) },
            text = {
                // 记录当前点击展开了哪首歌的详细信息
                var expandedSongId by remember { mutableStateOf<Long?>(null) }

                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    duplicatesList.forEachIndexed { index, group ->
                        item {
                            // 高级感标题：去掉了色块，采用极简的文字+留白
                            Text(
                                text = "组 ${index + 1} · ${group[0].title}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }

                        itemsIndexed(group) { _, song ->
                            val extension = song.data.substringAfterLast('.').uppercase()
                            val sizeMb = String.format("%.2f", song.size / 1048576.0)
                            val durationStr = "${song.duration / 60000}:${String.format("%02d", (song.duration % 60000) / 1000)}"
                            val isExpanded = expandedSongId == song.id

                            // 去掉 Card，改用无边框的 Column，依靠 padding 呼吸感
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null // 去掉点击的水波纹，更显冷淡高级
                                    ) { expandedSongId = if (isExpanded) null else song.id }
                                    .padding(vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${song.title} - ${song.artist}", maxLines = 1, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // 极简的小标签
                                            Box(modifier = Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                                                Text(extension, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text("$sizeMb MB • $durationStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    // 高级感垃圾桶：平时是淡淡的灰色线框图标，不再是一团大红色
                                    IconButton(
                                        onClick = {
                                            // 👇 真正的物理删除逻辑 (MediaStore API)
                                            try {
                                                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                                                // 1. 尝试使用官方 API 删物理文件
                                                if (context.contentResolver.delete(uri, null, null) > 0 || File(song.data).delete()) {
                                                    // 2. 删库
                                                    scope.launch(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data)) }
                                                    // 3. 刷新 UI
                                                    duplicatesList = duplicatesList.map { g -> g.filter { it.id != song.id } }.filter { it.size > 1 }
                                                    Toast.makeText(context, "已从手机彻底删除", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "删除失败，文件可能被占用", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: SecurityException) {
                                                // 触发 Android 10+ 系统级删除授权弹窗
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                                                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                                                    pendingDeleteSong = song
                                                    deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                                                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                                                    val recoverable = e as? android.app.RecoverableSecurityException
                                                    if (recoverable != null) {
                                                        pendingDeleteSong = song
                                                        deleteLauncher.launch(IntentSenderRequest.Builder(recoverable.userAction.actionIntent.intentSender).build())
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "删除出错", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Icon(androidx.compose.material.icons.Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.outline)
                                    }
                                }

                                // 👇 点按展开的底层高级属性面板 (占位，等接入 jaudiotagger 即可点亮)
                                AnimatedVisibility(visible = isExpanded) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text("物理路径: ${song.data.substringAfterLast("/")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(6.dp))

                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            // 👇 检查位深度：如果还是显示“未知”，说明数据库里这列是 0
                                            val bitsText = if (song.bitDepth > 0) "${song.bitDepth}-bit" else "等待解析..."
                                            Text(
                                                text = "位深度: $bitsText",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (song.bitDepth >= 24) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            // 👇 检查采样率
                                            val rateText = if (song.samplingRate > 0) {
                                                String.format("%.1f kHz", song.samplingRate / 1000f)
                                            } else {
                                                "等待解析..."
                                            }
                                            Text(
                                                text = "采样率: $rateText",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (song.samplingRate > 48000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // 🔮 加一个小彩蛋：显示这首歌的音量增益，这是 jaudiotagger 是否活着的终极证据
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "音量增益 (ReplayGain): ${String.format("%.2f dB", song.replayGain)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (song.replayGain != 0f) MaterialTheme.colorScheme.secondary else Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                        // 极简分隔线
                        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDuplicateDialog = false }) { Text("关闭") } }
        )
    }

    if (showSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showSelectionDialog = false }, title = { Text("发现新歌曲") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    item {
                        val allSelected = missingSongsList.all { it.isSelected }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { missingSongsList = missingSongsList.map { it.copy(isSelected = !allSelected) } }.padding(vertical = 8.dp)) {
                            Checkbox(checked = allSelected, onCheckedChange = null); Text("全选", fontWeight = FontWeight.Bold)
                        }; HorizontalDivider()
                    }
                    itemsIndexed(missingSongsList) { index, item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { val newList = missingSongsList.toMutableList(); newList[index] = item.copy(isSelected = !item.isSelected); missingSongsList = newList }.padding(vertical = 4.dp)) {
                            Checkbox(checked = item.isSelected, onCheckedChange = null)
                            Column { Text(item.remoteSong.filename, maxLines = 1, style = MaterialTheme.typography.bodyMedium); Row { Text("${item.remoteSong.size / 1048576} MB", style = MaterialTheme.typography.bodySmall, color = Color.Gray); if (item.remoteSong.has_lrc) Text(" • 带歌词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showSelectionDialog = false
                    val toDownload = missingSongsList.filter { it.isSelected }.map { it.remoteSong }
                    if (toDownload.isNotEmpty() && savedFolderUriStr != null) {
                        SyncTaskQueue.serverIp = pcServerIp; SyncTaskQueue.saveFolderUri = android.net.Uri.parse(savedFolderUriStr); SyncTaskQueue.songsToDownload = toDownload
                        val intent = Intent(context, SyncService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                        Toast.makeText(context, "已切换至后台同步，请下拉通知栏查看进度", Toast.LENGTH_LONG).show()
                    }
                }) { Text("后台同步") }
            },
            dismissButton = { TextButton(onClick = { showSelectionDialog = false }) { Text("取消") } }
        )
    }

    if (showDownloadingDialog) {
        AlertDialog(
            onDismissRequest = {}, title = { Text("同步中", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(syncLog); Spacer(Modifier.height(16.dp))
                    if (syncProgress > 0f) {
                        LinearProgressIndicator(progress = { syncProgress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                        Text("${(syncProgress * 100).toInt()}%", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (songToAddToPlaylist != null) {
        AlertDialog(
            onDismissRequest = { songToAddToPlaylist = null },
            title = { Text("添加到歌单", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item {
                        OutlinedButton(
                            onClick = { showNewPlaylistDialog = true },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Icon(Icons.Filled.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("新建歌单")
                        }
                    }
                    if (allPlaylists.isEmpty()) {
                        item { Text("暂无自定义歌单", color = Color.Gray, modifier = Modifier.padding(16.dp)) }
                    } else {
                        items(allPlaylists) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name) },
                                leadingContent = { Icon(Icons.Filled.QueueMusic, null) },
                                modifier = Modifier.clickable {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            // 把歌和歌单绑定存入数据库
                                            dao.addSongToPlaylist(PlaylistSong(playlistId = playlist.id, songPath = songToAddToPlaylist!!.data))
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "已添加到 ${playlist.name}", Toast.LENGTH_SHORT).show()
                                                songToAddToPlaylist = null // 关闭弹窗
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) { Toast.makeText(context, "添加失败或已在歌单中", Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { songToAddToPlaylist = null }) { Text("取消") } }
        )
    }

    // 🪟 弹窗：输入新歌单名字
    if (showNewPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showNewPlaylistDialog = false; newPlaylistName = "" },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            // 1. 创建歌单拿到 ID
                            val newId = dao.createPlaylist(Playlist(name = newPlaylistName))
                            // 2. 如果是从某首歌点进来的，顺便把这首歌塞进去
                            if (songToAddToPlaylist != null) {
                                dao.addSongToPlaylist(PlaylistSong(playlistId = newId, songPath = songToAddToPlaylist!!.data))
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "已创建并添加歌曲", Toast.LENGTH_SHORT).show()
                                    showNewPlaylistDialog = false
                                    newPlaylistName = ""
                                    songToAddToPlaylist = null // 连带外层选择窗一起关闭
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "歌单创建成功", Toast.LENGTH_SHORT).show()
                                    showNewPlaylistDialog = false
                                    newPlaylistName = ""
                                }
                            }
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showNewPlaylistDialog = false; newPlaylistName = "" }) { Text("取消") } }
        )
    }
}

// ==========================================
// SongItemUI (带 LRU 缓存 + 150ms 防抖)
// ==========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItemUI(
    song: Song,
    index: Int,
    isPlaying: Boolean,
    allowMarquee: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    val cachedInfo = AudioCache.getFromMemory(song.data)
    var spec by remember(song.data) { mutableStateOf(cachedInfo?.spec) }
    var bitmap by remember(song.data) { mutableStateOf(cachedInfo?.bitmap) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 👇 新增：分拆两个菜单的状态，并记录长按的精确坐标
    var showButtonMenu by remember { mutableStateOf(false) }
    var showTouchMenu by remember { mutableStateOf(false) }
    var touchOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // 统一关闭菜单的快捷方法
    val closeAllMenus = {
        showButtonMenu = false
        showTouchMenu = false
    }

    val lrcPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val lrcFile = File(File(song.data).parent, "${File(song.data).nameWithoutExtension}.lrc")
                    val outputStream = FileOutputStream(lrcFile)
                    inputStream?.copyTo(outputStream); inputStream?.close(); outputStream.close()
                    withContext(Dispatchers.Main) { Toast.makeText(context, "歌词导入成功！", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    LaunchedEffect(song.data) {
        if (spec != null) return@LaunchedEffect
        val diskInfo = AudioCache.loadFromDisk(context, song)
        if (diskInfo != null) {
            spec = diskInfo.spec; bitmap = diskInfo.bitmap
            return@LaunchedEffect
        }
        delay(250)
        val freshInfo = AudioCache.extractAndSave(context, song)
        spec = freshInfo.spec; bitmap = freshInfo.bitmap
    }

    val marqueeModifier = if (isPlaying && allowMarquee) Modifier.basicMarquee() else Modifier

    // 👇 提取出菜单项的 UI，避免写两遍重复代码
    val menuItems = @Composable {
        DropdownMenuItem(text = { Text("下一首播放") }, leadingIcon = { Icon(Icons.Filled.PlaylistPlay, null) }, onClick = { closeAllMenus(); onPlayNext() })
        DropdownMenuItem(text = { Text("添加到歌单...") }, leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null) }, onClick = { closeAllMenus(); onAddToPlaylist() })
        if (onRemoveFromPlaylist != null) {
            DropdownMenuItem(text = { Text("从歌单移除") }, leadingIcon = { Icon(Icons.Filled.RemoveCircleOutline, null) }, onClick = { closeAllMenus(); onRemoveFromPlaylist() })
        }
        DropdownMenuItem(text = { Text("导入 LRC 歌词") }, leadingIcon = { Icon(Icons.Filled.Subtitles, null) }, onClick = { closeAllMenus(); lrcPickerLauncher.launch("*/*") })
        DropdownMenuItem(text = { Text("彻底删除文件", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { closeAllMenus(); showDeleteConfirm = true })
    }

    // 最外层的 Box，包裹整行
    Box(modifier = Modifier.fillMaxWidth()) {

        // 🔮 黑科技：指尖锚点！一个大小为 0 的隐形盒子，永远跟着你的长按坐标走
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(touchOffset.x.toInt(), touchOffset.y.toInt()) }
                .size(0.dp)
        ) {
            DropdownMenu(expanded = showTouchMenu, onDismissRequest = closeAllMenus) {
                menuItems() // 展开这里的菜单
            }
        }

        // 歌曲卡片本体
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // 👇 修复 1：直接写函数名，千万别带那一长串包名
                    detectTapGestures(
                        // 👇 修复 2：用 `_ ->` 告诉编译器“我接收了这个参数但我不用”
                        onTap = { _ -> onClick() },
                        // 👇 修复 3：强行指定 offset 的类型，治好编译器的“类型推断失败”
                        onLongPress = { offset: Offset ->
                            touchOffset = offset
                            showTouchMenu = true
                        }
                    )
                }
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "${index + 1}", style = MaterialTheme.typography.bodyMedium, color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Gray, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)

            if (bitmap != null) {
                Image(bitmap = bitmap!!, contentDescription = "Cover", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(CircleShape))
            } else {
                AdvancedFluidCover(
                    seedString = song.data,
                    iconSize = 20.dp, // 保持小巧
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                )
            }

            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = song.title, style = MaterialTheme.typography.titleMedium, color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(marqueeModifier))
                    spec?.let {
                        if (it.level != AudioLevel.STANDARD) { Spacer(Modifier.width(8.dp)); Box(modifier = Modifier.border(1.dp, it.level.color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(it.level.label, fontSize = 9.sp, color = it.level.color, fontWeight = FontWeight.Bold) } }
                        if (it.isSpatial) { Spacer(Modifier.width(4.dp)); Box(modifier = Modifier.border(1.dp, it.spatialColor, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(it.spatialLabel, fontSize = 9.sp, color = it.spatialColor, fontWeight = FontWeight.Bold) } }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = song.artist, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(marqueeModifier))
                    Spacer(Modifier.width(8.dp))
                    Text("${song.size / 1048576} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                }
            }

            // 右侧三个点按钮和它的专属菜单
            Box {
                IconButton(onClick = { showButtonMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = Color.Gray) }
                DropdownMenu(expanded = showButtonMenu, onDismissRequest = closeAllMenus) {
                    menuItems() // 展开这里的菜单
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false }, title = { Text("确认删除") },
            text = { Text("将彻底从手机存储中删除此文件，不可恢复。") },
            confirmButton = { Button(onClick = { showDeleteConfirm = false; onDelete() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}

// ==========================================
// 全屏播放器 (终极定稿：保卫平板审美 + 智能高度断点 + 无损画质渐进加载 + 修复报错)
// ==========================================
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
        val listState = rememberLazyListState()
        val isUserDraggingLyrics by listState.interactionSource.collectIsDraggedAsState()
        var isLyricsPausedForInteraction by remember { mutableStateOf(false) }

        var showPlaylistSheet by remember { mutableStateOf(false) }
        var showInfoDialog by remember { mutableStateOf(false) }
        var showEqDialog by remember { mutableStateOf(false) }
        var showSpeedMenu by remember { mutableStateOf(false) }

        var lyricsFontSize by rememberSaveable { mutableFloatStateOf(20f) }
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

        var dominantColor by remember { mutableStateOf<Color?>(null) }
        val animatedDominantColor by animateColorAsState(
            targetValue = dominantColor ?: MaterialTheme.colorScheme.primaryContainer,
            animationSpec = tween(1500, easing = LinearEasing),
            label = "dominantColor"
        )

        LaunchedEffect(artwork, audioPath) { // 加上 audioPath 监听
            if (artwork != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val bmp = BitmapFactory.decodeByteArray(artwork, 0, artwork.size)
                        val palette = androidx.palette.graphics.Palette.from(bmp).generate()
                        // 提取颜色
                        val rgb = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: palette.mutedSwatch?.rgb
                        if (rgb != null) {
                            withContext(Dispatchers.Main) { dominantColor = Color(rgb) }
                        }
                    } catch (e: Exception) {
                        // 如果提取失败，用 fallback
                        withContext(Dispatchers.Main) { dominantColor = fallbackColors[0] }
                    }
                }
            } else {
                // C. 【重点】没有封面时，直接让背景颜色等于随机生成的优雅色第一个
                dominantColor = fallbackColors[0]
            }
        }

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
            lrcLines = LrcParser.parse(audioPath)
            showLyrics = false; isFullscreenLyrics = false; abLoopStart = -1L; abLoopEnd = -1L

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
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val sliderActualWidth = maxWidth
                    val bubbleOffsetX =
                        (sliderProgress * (sliderActualWidth.value - 48f)).coerceAtLeast(0f).dp

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            if (isDraggingSlider) {
                                val targetTime = (sliderProgress * duration).toLong()
                                Text(
                                    text = formatTime(targetTime),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .offset(x = bubbleOffsetX)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Slider(
                            value = if (isDraggingSlider) sliderProgress else actualProgress,
                            onValueChange = { isDraggingSlider = true; sliderProgress = it },
                            onValueChangeFinished = {
                                isDraggingSlider =
                                    false; mediaController?.seekTo((sliderProgress * duration).toLong()); currentPosition =
                                (sliderProgress * duration).toLong(); isLyricsPausedForInteraction =
                                false
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
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
                    Box(
                        modifier = Modifier.size(if (isLandscapeLayout) 64.dp else 72.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                if (isPlaying) mediaController?.pause() else {
                                    mediaController?.play(); isLyricsPausedForInteraction = false
                                }
                            }, contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween, // 改为两端对齐，内部靠 weight 分配
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. 速度 (占位 1/5)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = { showSpeedMenu = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = if (playbackSpeed != 1.0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text(
                            if (playbackSpeed == playbackSpeed.toLong()
                                    .toFloat()
                            ) "${playbackSpeed.toInt()}x" else "${playbackSpeed}x",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }) {
                        listOf(
                            0.5f,
                            0.75f,
                            1.0f,
                            1.25f,
                            1.5f,
                            2.0f
                        ).forEach { speed ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${speed}x",
                                        fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal,
                                        color = if (playbackSpeed == speed) MaterialTheme.colorScheme.primary else Color.Unspecified
                                    )
                                },
                                onClick = {
                                    playbackSpeed = speed; mediaController?.setPlaybackParameters(
                                    PlaybackParameters(speed)
                                ); showSpeedMenu = false
                                })
                        }
                    }
                }

                // 2. A-B 循环 (占位 1/5)
                val abLabel = when {
                    abLoopStart < 0 -> "A-B"; abLoopEnd < 0 -> "B ?"; else -> "A-B ✓"
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = {
                            when {
                                abLoopStart < 0 -> {
                                    abLoopStart = currentPosition; Toast.makeText(
                                        context,
                                        "A 点已设",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                                abLoopEnd < 0 -> {
                                    if (currentPosition > abLoopStart) {
                                        abLoopEnd = currentPosition; Toast.makeText(
                                            context,
                                            "B 点已设，循环开始",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else Toast.makeText(
                                        context,
                                        "B 点必须在 A 点之后",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                                else -> {
                                    abLoopStart = -1L; abLoopEnd = -1L; Toast.makeText(
                                        context,
                                        "A-B 取消",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = if (abLoopEnd >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    ) { Text(abLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }

                // 3. ❤️ 红心按键 (正中间，占位 1/5，绝对对齐播放键)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onFavoriteClick, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favorite",
                            modifier = Modifier.size(24.dp), // 稍微调大一点点，视觉更平衡
                            tint = if (isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 4. EQ 均衡器 (占位 1/5)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { showEqDialog = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            "EQ",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 5. 睡眠定时 (占位 1/5)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onSleepTimerClick, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.DarkMode,
                            "Sleep",
                            modifier = Modifier.size(22.dp),
                            tint = if (sleepTimerSeconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(gradientBrush)
                .windowInsetsPadding(WindowInsets.safeDrawing)
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
                            Spacer(Modifier.width(16.dp))

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
                                    val isCurrentLine = index == currentLyricIndex
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.width(60.dp),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                formatTime(line.timeMs),
                                                fontSize = 10.sp,
                                                color = if (isLyricsPausedForInteraction) MaterialTheme.colorScheme.primary.copy(
                                                    alpha = 0.7f
                                                ) else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            ); Icon(
                                            Icons.Filled.PlayArrow,
                                            null,
                                            modifier = Modifier.size(16.dp).padding(start = 2.dp),
                                            tint = if (isLyricsPausedForInteraction) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(
                                                alpha = 0.2f
                                            )
                                        )
                                        }
                                        Box(
                                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    mediaController?.seekTo(line.timeMs); currentPosition =
                                                    line.timeMs; isLyricsPausedForInteraction =
                                                    false
                                                }.padding(horizontal = 16.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                line.text,
                                                fontSize = if (isCurrentLine) (lyricsFontSize + 4).sp else lyricsFontSize.sp,
                                                fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrentLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                                                    alpha = 0.5f
                                                ),
                                                textAlign = TextAlign.Center,
                                                lineHeight = (lyricsFontSize + 8).sp
                                            )
                                        }
                                        Spacer(Modifier.width(60.dp))
                                    }
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
                                Text(
                                    title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee(
                                        velocity = 30.dp,
                                        initialDelayMillis = 1500
                                    )
                                )
                                Spacer(Modifier.height(4.dp))
                                val artists =
                                    artist.split("/").map { it.trim() }.filter { it.isNotEmpty() }
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                    artists.forEachIndexed { index, artistName ->
                                        Text(
                                            artistName,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                                .clickable { onArtistClick(artistName) }
                                                .padding(2.dp)
                                        ); if (index < artists.size - 1) Text(
                                        " / ",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))

                                spec?.let {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                it.specText,
                                                fontSize = 11.sp,
                                                color = it.level.color,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (it.isSpatial) {
                                                Spacer(Modifier.width(8.dp)); Box(
                                                    modifier = Modifier.border(
                                                        1.dp,
                                                        it.spatialColor,
                                                        RoundedCornerShape(4.dp)
                                                    ).padding(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        it.spatialLabel,
                                                        fontSize = 9.sp,
                                                        color = it.spatialColor,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
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
                                }
                                Spacer(Modifier.height(if (isCompactLandscape) 16.dp else 32.dp))

                                // 👇 还原：恢复固定尺寸排版。高度不足的手机强行缩小到 120dp，平板依旧保持完美的 200dp！
                                val imageSize = if (isCompactLandscape) 120.dp else 200.dp
                                val imageModifier =
                                    Modifier.size(imageSize).clip(RoundedCornerShape(16.dp))

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

                                    LaunchedEffect(path) {
                                        highResBitmap = withContext(Dispatchers.IO) {
                                            try {
                                                val r = MediaMetadataRetriever()
                                                r.setDataSource(path)
                                                val pic = r.embeddedPicture
                                                r.release()

                                                if (pic != null) {
                                                    // 强制 100% 无损画质
                                                    val options = BitmapFactory.Options().apply {
                                                        inPreferredConfig = Bitmap.Config.ARGB_8888
                                                    }
                                                    BitmapFactory.decodeByteArray(
                                                        pic,
                                                        0,
                                                        pic.size,
                                                        options
                                                    ).asImageBitmap()
                                                } else {
                                                    val imgFile = File(
                                                        context.cacheDir,
                                                        "audio_meta_cache/${path.hashCode()}.webp"
                                                    )
                                                    if (imgFile.exists()) BitmapFactory.decodeFile(
                                                        imgFile.absolutePath
                                                    )?.asImageBitmap() else null
                                                }
                                            } catch (e: Exception) {
                                                null
                                            }
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
                                                val isCurrentLine = index == currentLyricIndex
                                                Row(
                                                    modifier = Modifier.fillMaxWidth()
                                                        .padding(vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(
                                                        modifier = Modifier.width(60.dp),
                                                        horizontalArrangement = Arrangement.End,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            formatTime(line.timeMs),
                                                            fontSize = 10.sp,
                                                            color = if (isCurrentLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(
                                                                alpha = 0.3f
                                                            )
                                                        ); Icon(
                                                        Icons.Filled.PlayArrow,
                                                        null,
                                                        modifier = Modifier.size(16.dp)
                                                            .padding(start = 2.dp),
                                                        tint = if (isCurrentLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(
                                                            alpha = 0.3f
                                                        )
                                                    )
                                                    }
                                                    Box(
                                                        modifier = Modifier.clip(
                                                            RoundedCornerShape(
                                                                16.dp
                                                            )
                                                        ).clickable {
                                                            mediaController?.seekTo(line.timeMs); currentPosition =
                                                            line.timeMs; isLyricsPausedForInteraction =
                                                            false
                                                        }.padding(
                                                            horizontal = 16.dp,
                                                            vertical = 6.dp
                                                        ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            line.text,
                                                            fontSize = if (isCurrentLine) (lyricsFontSize + 4).sp else lyricsFontSize.sp,
                                                            fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                                                            color = if (isCurrentLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                                                                alpha = 0.5f
                                                            ),
                                                            textAlign = TextAlign.Center,
                                                            lineHeight = (lyricsFontSize + 8).sp
                                                        )
                                                    }
                                                    Spacer(Modifier.width(60.dp))
                                                }
                                            }
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                "暂无歌词",
                                                color = Color.Gray
                                            ); Spacer(Modifier.height(8.dp)); TextButton(onClick = {
                                            lrcPickerInPlayer.launch(
                                                "*/*"
                                            )
                                        }) {
                                            Icon(
                                                Icons.Filled.Add,
                                                null,
                                                modifier = Modifier.size(18.dp)
                                            ); Spacer(Modifier.width(4.dp)); Text("添加 LRC 歌词")
                                        }
                                        }
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
                                }; Spacer(Modifier.width(8.dp))
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
                            Row(
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(4.dp), verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.clip(RoundedCornerShape(50))
                                        .background(if (!showLyrics) MaterialTheme.colorScheme.surface else Color.Transparent)
                                        .clickable {
                                            showLyrics = false; isFullscreenLyrics = false
                                        }.padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "封面",
                                        fontSize = 14.sp,
                                        fontWeight = if (!showLyrics) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                                Box(
                                    modifier = Modifier.clip(RoundedCornerShape(50))
                                        .background(if (showLyrics) MaterialTheme.colorScheme.surface else Color.Transparent)
                                        .clickable { showLyrics = true; isFullscreenLyrics = false }
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "歌词",
                                        fontSize = 14.sp,
                                        fontWeight = if (showLyrics) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
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
                                    val isCurrentLine = index == currentLyricIndex
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.width(60.dp),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                formatTime(line.timeMs),
                                                fontSize = 10.sp,
                                                color = if (isLyricsPausedForInteraction) MaterialTheme.colorScheme.primary.copy(
                                                    alpha = 0.7f
                                                ) else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            ); Icon(
                                            Icons.Filled.PlayArrow,
                                            null,
                                            modifier = Modifier.size(16.dp).padding(start = 2.dp),
                                            tint = if (isLyricsPausedForInteraction) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(
                                                alpha = 0.2f
                                            )
                                        )
                                        }
                                        Box(
                                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    mediaController?.seekTo(line.timeMs); currentPosition =
                                                    line.timeMs; isLyricsPausedForInteraction =
                                                    false
                                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                line.text,
                                                fontSize = if (isCurrentLine) (lyricsFontSize + 4).sp else lyricsFontSize.sp,
                                                fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrentLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                                                    alpha = 0.5f
                                                ),
                                                textAlign = TextAlign.Center,
                                                lineHeight = (lyricsFontSize + 8).sp
                                            )
                                        }
                                        Spacer(Modifier.width(60.dp))
                                    }
                                }
                            }
                        } else if (showLyrics && lrcLines.isEmpty()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "暂无歌词",
                                    color = Color.Gray
                                ); Spacer(Modifier.height(8.dp)); TextButton(onClick = {
                                lrcPickerInPlayer.launch(
                                    "*/*"
                                )
                            }) {
                                Icon(
                                    Icons.Filled.Add,
                                    null,
                                    modifier = Modifier.size(18.dp)
                                ); Spacer(Modifier.width(4.dp)); Text("添加 LRC 歌词")
                            }
                            }
                        } else {
                            val imageModifier =
                                Modifier.size(300.dp).clip(RoundedCornerShape(24.dp))

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

                                LaunchedEffect(path) {
                                    highResBitmap = withContext(Dispatchers.IO) {
                                        try {
                                            val r = MediaMetadataRetriever()
                                            r.setDataSource(path)
                                            val pic = r.embeddedPicture
                                            r.release()

                                            if (pic != null) {
                                                // 强制 100% 无损画质
                                                val options = BitmapFactory.Options().apply {
                                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                                }
                                                BitmapFactory.decodeByteArray(
                                                    pic,
                                                    0,
                                                    pic.size,
                                                    options
                                                ).asImageBitmap()
                                            } else {
                                                val imgFile = File(
                                                    context.cacheDir,
                                                    "audio_meta_cache/${path.hashCode()}.webp"
                                                )
                                                if (imgFile.exists()) BitmapFactory.decodeFile(
                                                    imgFile.absolutePath
                                                )?.asImageBitmap() else null
                                            }
                                        } catch (e: Exception) {
                                            null
                                        }
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
                            Spacer(Modifier.height(24.dp)); Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.basicMarquee(
                                velocity = 30.dp,
                                initialDelayMillis = 1500
                            )
                        ); Spacer(Modifier.height(8.dp))
                            val artists =
                                artist.split("/").map { it.trim() }.filter { it.isNotEmpty() }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                artists.forEachIndexed { index, artistName ->
                                    Text(
                                        artistName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                            .clickable { onArtistClick(artistName) }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ); if (index < artists.size - 1) Text(
                                    " / ",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                }
                            }
                            spec?.let {
                                Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    it.specText,
                                    fontSize = 11.sp,
                                    color = it.level.color,
                                    fontWeight = FontWeight.Bold
                                ); if (it.isSpatial) {
                                Spacer(Modifier.width(8.dp)); Box(
                                    modifier = Modifier.border(
                                        1.dp,
                                        it.spatialColor,
                                        RoundedCornerShape(4.dp)
                                    ).padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        it.spatialLabel,
                                        fontSize = 9.sp,
                                        color = it.spatialColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            }
                            }
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
            title = { Text("Info", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    detailedInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
            },
            confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text("Close") } })
        if (showEqDialog) EqDialog(onDismiss = { showEqDialog = false })

        if (showPlaylistSheet) {
            val playlistState = rememberLazyListState()
            val currentIndex = mediaController?.currentMediaItemIndex ?: -1
            LaunchedEffect(showPlaylistSheet) {
                if (showPlaylistSheet && currentIndex >= 0) playlistState.scrollToItem(
                    maxOf(0, currentIndex - 1)
                )
            }
            ModalBottomSheet(onDismissRequest = { showPlaylistSheet = false }) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "当前播放队列",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    ); Spacer(Modifier.height(8.dp))
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
                                modifier = Modifier.fillMaxWidth()
                                    .zIndex(if (isBeingDragged) 1f else 0f).graphicsLayer {
                                    translationY = animatedTranslationY; scaleX =
                                    if (isBeingDragged) 1.05f else 1f; scaleY =
                                    if (isBeingDragged) 1.05f else 1f; shadowElevation =
                                    if (isBeingDragged) 8f else 0f; alpha =
                                    if (isBeingDragged) 0.9f else 1f
                                }.background(
                                    color = if (isBeingDragged) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                ).pointerInput(item?.mediaId) {
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
                                }.padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrentPlaying) Icon(
                                    Icons.Filled.GraphicEq,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                ) else Text(
                                    "${index + 1}",
                                    modifier = Modifier.width(20.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.width(16.dp)); Text(
                                item?.mediaMetadata?.title?.toString() ?: "未知",
                                color = if (isCurrentPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            ); Icon(
                                Icons.Filled.DragHandle,
                                null,
                                tint = Color.Gray.copy(alpha = 0.5f)
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
fun MiniPlayerBar(title: String, artist: String, isPlaying: Boolean, onPreviousClick: () -> Unit, onPlayPauseClick: () -> Unit, onNextClick: () -> Unit, onBarClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable { onBarClick() }, color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 8.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1); Text(artist, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            IconButton(onClick = onPreviousClick) { Icon(Icons.Filled.SkipPrevious, "Previous") }
            IconButton(onClick = onPlayPauseClick) { Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause") }
            IconButton(onClick = onNextClick) { Icon(Icons.Filled.SkipNext, "Next") }
        }
    }
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