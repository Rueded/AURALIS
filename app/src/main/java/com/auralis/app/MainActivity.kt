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
// MainActivity
// ==========================================
private fun audioReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

class MainActivity : ComponentActivity() {
    val shouldOpenPlayer = mutableStateOf(false)
    val audioPermissionGranted = mutableStateOf(false)

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            audioPermissionGranted.value = hasAudioReadPermission()
            results.forEach { (perm, granted) ->
                Log.d("Auralis", "$perm: ${if (granted) "已授予" else "已拒绝"}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioPermissionGranted.value = hasAudioReadPermission()
        handleIntent(intent)
        setContent {
           ThemeManager.loadSaved(this)
            AuralisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                     MusicAppScreen(shouldOpenPlayer = shouldOpenPlayer)
                 }
            }
        }
        requestRuntimePermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        audioPermissionGranted.value = hasAudioReadPermission()
    }

    private fun hasAudioReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioReadPermission()) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePermissionsIfNeeded() {
        val needed = buildList {
            if (!hasAudioReadPermission()) add(audioReadPermission())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    fun requestRuntimePermissions() {
        requestRuntimePermissionsIfNeeded()
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
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE) }
    val isBitPerfect = prefs.getBoolean("enable_bit_perfect", false)

    if (isBitPerfect) {
        AlertDialog(
            onDismissRequest = onDismiss, title = { Text("均衡器已旁路") },
            text = { Text("当前已开启 USB 源码直通，音频信号直接发送至 DAC，系统 EQ 无法介入。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("了解") } }
        )
        return
    }

    var eqErrorMessage by remember { mutableStateOf<String?>(null) }
    val eq = remember {
        try {
            if (PlaybackService.audioSessionId != 0) {
                Equalizer(0, PlaybackService.audioSessionId).also { it.enabled = true }
            } else {
                eqErrorMessage = "AudioSessionId 尚未准备好，请先播放任意一首歌曲后再打开 EQ。"
                null
            }
        } catch (e: Exception) {
            eqErrorMessage = "当前手机系统拒绝了全局均衡器请求：${e.message}"
            null
        }
    }

    if (eq == null) {
        AlertDialog(
            onDismissRequest = onDismiss, title = { Text("均衡器不可用") },
            text = { Text(eqErrorMessage ?: "未知错误") },
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
        title = { Text("发烧级均衡器", fontWeight = FontWeight.Bold) },
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
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp), textAlign = TextAlign.End, fontSize = 10.sp,
                            color = when {
                                bandLevels[bandIdx] > 0 -> MaterialTheme.colorScheme.primary
                                bandLevels[bandIdx] < 0 -> MaterialTheme.colorScheme.error
                                else -> Color.Gray
                            }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = { repeat(numBands) { i -> eq.setBandLevel(i.toShort(), 0); bandLevels[i] = 0 } }) { Text("重置") } }
    )
}

// ==========================================
// MusicAppScreen
