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

    fun updateMemoryCacheBitmap(path: String, bmp: ImageBitmap) {
        putCoverInMemory(path, bmp)
    }

    fun putCoverInMemory(path: String, bmp: ImageBitmap) {
        val existing = memoryCache.get(path)
        memoryCache.put(path, (existing ?: CachedAudioInfo(AudioSpec(), null)).copy(bitmap = bmp))
    }

    fun removeFromMemory(path: String) {
        memoryCache.remove(path)
    }

    fun clearMemory() {
        memoryCache.evictAll()
    }

    fun getFromMemory(path: String): CachedAudioInfo? = memoryCache.get(path)

    suspend fun loadFromDisk(context: Context, song: Song): CachedAudioInfo? {
        return withContext(Dispatchers.IO) {
            val txtFile = CoverArtCache.metaFile(context, song.data)
            val imgFile = CoverArtCache.imageFile(context, song.data)

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
                                isDatabaseReady = true
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
                            // 1. 优先使用本地物理内嵌封面
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = 2
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                            resultBitmap = BitmapFactory.decodeByteArray(picData, 0, picData.size, options)
                        } else {
                            // 🚨 2. 本地没有图片？呼叫神级爬虫去网上扒高清原图！
                            // 为了不卡住列表滑动，这里通过协程挂起，在 IO 线程去下载
                            resultBitmap = CoverFetcher.fetchHighResCover(song.title, song.artist)

                            // 3. 如果连网络都没找到，再走原来的 MediaStore 兜底逻辑
                            if (resultBitmap == null) {
                                val isUnknown = albumStr.isEmpty() ||
                                        albumStr.equals("Unknown", ignoreCase = true) ||
                                        albumStr.equals("Unknown album", ignoreCase = true) ||
                                        albumStr.equals("Music", ignoreCase = true)

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
                        }
                    } catch (e: Exception) {
                        Log.e("AudioCache", "提取或下载图片异常: ${e.message}")
                    }

                } catch (e: Exception) {
                    Log.e("AudioCache", "提取元数据失败", e)
                }

                // =================写入缓存阶段=================
                try {
                    val isPotentiallyWrong = resultSpec.isLossless && resultSpec.bitDepth <= 16 && !isDatabaseReady
                    val shouldCache = !isPotentiallyWrong

                    if (shouldCache) {
                        CoverArtCache.metaFile(context, song.data).writeText(
                            "${resultSpec.format}|${resultSpec.bitRate}|${resultSpec.sampleRate}|${resultSpec.bitDepth}|${resultSpec.channels}"
                        )
                        if (resultBitmap != null) {
                            CoverArtCache.saveBitmap(context, song.data, resultBitmap!!)
                            putCoverInMemory(song.data, resultBitmap!!.asImageBitmap())
                            if (song.data == PlayerStateHolder.currentPath) {
                                PlayerStateHolder.updateFromBitmap(resultBitmap!!, song.data)
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
