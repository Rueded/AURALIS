package com.auralis.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.auralis.app.ui.theme.AuralisTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    val audioPermissionGranted = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        audioPermissionGranted.value = hasPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        audioPermissionGranted.value = hasPermission()

        // 🚨 关键：如果是从通知点击进来的，要能识别到
        val shouldOpenPlayer = mutableStateOf(false)
        if (intent?.action == "OPEN_PLAYER" || intent?.action == "OPEN_PLAYER_FULLSCREEN") {
            shouldOpenPlayer.value = true
        }

        setContent {
            AuralisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MusicAppScreen(shouldOpenPlayer = shouldOpenPlayer)
                }
            }
        }
    }

    fun requestRuntimePermissions() {
        val permissions = mutableListOf(audioReadPermission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.RECORD_AUDIO)
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun audioReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, audioReadPermission()) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "OPEN_PLAYER" || intent.action == "OPEN_PLAYER_FULLSCREEN") {
            // Notify Compose via the global flag in PlayerStateHolder
            PlayerStateHolder.requestOpenPlayer()
        }
    }
}

// ==========================================
// 均衡器对话框
// ==========================================
@Composable
fun EqDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val customPresets by db.eqPresetDao().getAllPresets().collectAsState(initial = emptyList())

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

    var showSaveNameDialog by remember { mutableStateOf(false) }
    var saveNameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("发烧级均衡器", fontWeight = FontWeight.Bold)
                IconButton(onClick = { showSaveNameDialog = true }) {
                    Icon(Icons.Default.Save, "保存预设", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // 👇 自定义预设区域
                if (customPresets.isNotEmpty()) {
                    Text("我的预设", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(customPresets) { preset ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val levels = preset.bandLevels.split(",").map { it.toShort() }
                                    levels.forEachIndexed { idx, level ->
                                        if (idx < numBands) {
                                            eq.setBandLevel(idx.toShort(), level)
                                            bandLevels[idx] = level.toInt()
                                        }
                                    }
                                },
                                label = { Text(preset.name, fontSize = 11.sp) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        null,
                                        modifier = Modifier.size(14.dp).clickable {
                                            scope.launch { db.eqPresetDao().deletePreset(preset.id) }
                                        }
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (presetNames.isNotEmpty()) {
                    Text("系统方案", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
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

    if (showSaveNameDialog) {
        AlertDialog(
            onDismissRequest = { showSaveNameDialog = false },
            title = { Text("保存预设") },
            text = {
                OutlinedTextField(
                    value = saveNameText,
                    onValueChange = { saveNameText = it },
                    label = { Text("预设名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (saveNameText.isNotBlank()) {
                        scope.launch {
                            val levelsStr = bandLevels.joinToString(",")
                            db.eqPresetDao().insertPreset(EqPreset(name = saveNameText, bandLevels = levelsStr))
                            showSaveNameDialog = false
                            saveNameText = ""
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSaveNameDialog = false }) { Text("取消") } }
        )
    }
}
