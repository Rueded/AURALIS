package com.example.mymusic

// ── 这个文件放两个东西：
//    1. HomeHeader     — 首页顶部横幅（替换原来的 Row searchbar）
//    2. SongItemUI     — 重设计的歌曲列表项（直接替换原来的同名函数）
// ── 使用方法见文件底部注释 ──────────────────────────────────────────────────

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.Image

// ══════════════════════════════════════════════════════════════════════════════
// 一、首页顶部横幅
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 用法：把原来 MusicAppScreen 里的 Row { OutlinedTextField + sort + settings + sync }
 * 整段替换成 HomeHeader(...)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeHeader(
    totalSongs: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSortClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSyncClick: () -> Unit,
    currentTitle: String?,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {

        // ── 顶部标题行 ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AURALIS",
                    style = MaterialTheme.typography.labelSmall,
                    color = primary,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$totalSongs",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "首曲目",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            // 图标按钮组
            IconButton(onClick = onSortClick) {
                Icon(
                    Icons.Filled.Sort, "排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Filled.Settings, "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalIconButton(
                onClick = onSyncClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Filled.Sync, "同步", modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
        }

        // ── 搜索栏 ──────────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = {
                Text(
                    "搜索歌名、歌手、格式…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search, "搜索",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                { IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Filled.Close, "清除", modifier = Modifier.size(18.dp))
                } }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = primary.copy(alpha = 0.6f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            )
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 二、歌曲列表项
// ══════════════════════════════════════════════════════════════════════════════

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cachedInfo = AudioCache.getFromMemory(song.data)
    var spec by remember(song.data) { mutableStateOf(cachedInfo?.spec) }
    var bitmap by remember(song.data) { mutableStateOf(cachedInfo?.bitmap) }

    var showButtonMenu by remember { mutableStateOf(false) }
    var showTouchMenu by remember { mutableStateOf(false) }
    var touchOffset by remember { mutableStateOf(Offset.Zero) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val closeAllMenus = { showButtonMenu = false; showTouchMenu = false }

    val lrcPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val ins = context.contentResolver.openInputStream(uri)
                    val out = FileOutputStream(
                        File(File(song.data).parent, "${File(song.data).nameWithoutExtension}.lrc")
                    )
                    ins?.copyTo(out); ins?.close(); out.close()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "歌词导入成功", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(song.data) {
        if (spec != null) return@LaunchedEffect
        val disk = AudioCache.loadFromDisk(context, song)
        if (disk != null) { spec = disk.spec; bitmap = disk.bitmap; return@LaunchedEffect }
        delay(250)
        val fresh = AudioCache.extractAndSave(context, song)
        spec = fresh.spec; bitmap = fresh.bitmap
    }

    val marqueeModifier = if (isPlaying && allowMarquee) Modifier.basicMarquee() else Modifier

    // ── 播放中高亮背景 ────────────────────────────────────────────────────
    val highlightAlpha by animateColorAsState(
        targetValue = if (isPlaying)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else Color.Transparent,
        animationSpec = tween(400), label = "rowHighlight"
    )

    val menuItems: @Composable () -> Unit = {
        DropdownMenuItem(
            text = { Text("下一首播放") },
            leadingIcon = { Icon(Icons.Filled.PlaylistPlay, null) },
            onClick = { closeAllMenus(); onPlayNext() }
        )
        DropdownMenuItem(
            text = { Text("添加到歌单") },
            leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null) },
            onClick = { closeAllMenus(); onAddToPlaylist() }
        )
        if (onRemoveFromPlaylist != null) {
            DropdownMenuItem(
                text = { Text("从歌单移除") },
                leadingIcon = { Icon(Icons.Filled.RemoveCircleOutline, null) },
                onClick = { closeAllMenus(); onRemoveFromPlaylist() }
            )
        }
        DropdownMenuItem(
            text = { Text("导入 LRC 歌词") },
            leadingIcon = { Icon(Icons.Filled.Subtitles, null) },
            onClick = { closeAllMenus(); lrcPickerLauncher.launch("*/*") }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("彻底删除文件", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
            },
            onClick = { closeAllMenus(); showDeleteConfirm = true }
        )
    }

    Box(modifier = Modifier.fillMaxWidth().background(highlightAlpha)) {

        // 长按锚点菜单
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(touchOffset.x.toInt(), touchOffset.y.toInt()) }
                .size(0.dp)
        ) {
            DropdownMenu(expanded = showTouchMenu, onDismissRequest = closeAllMenus) {
                menuItems()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { offset: Offset ->
                            touchOffset = offset
                            showTouchMenu = true
                        }
                    )
                }
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ── 封面：圆角正方形 ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!,          // ← 去掉 .asImageBitmap()
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }else {
                    AdvancedFluidCover(
                        seedString = song.data,
                        iconSize = 22.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 正在播放的覆盖层：波形动画
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayingWaveform()
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            // ── 文字区 ───────────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(marqueeModifier)
                    )
                    // 音质标签
                    spec?.let { s ->
                        if (s.level != AudioLevel.STANDARD) {
                            Spacer(Modifier.width(6.dp))
                            QualityBadge(label = s.level.label, color = s.level.color)
                        }
                        if (s.isSpatial) {
                            Spacer(Modifier.width(4.dp))
                            QualityBadge(label = s.spatialLabel, color = s.spatialColor)
                        }
                    }
                }

                Spacer(Modifier.height(3.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(marqueeModifier)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${song.size / 1048576} MB",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    )
                }
            }

            // ── 三点菜单 ─────────────────────────────────────────────────
            Box {
                IconButton(
                    onClick = { showButtonMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert, "菜单",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                DropdownMenu(expanded = showButtonMenu, onDismissRequest = closeAllMenus) {
                    menuItems()
                }
            }
        }

        // 正在播放时左侧高亮竖线
        if (isPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("将从手机存储中彻底删除此文件，无法恢复。") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ── 波形动画（封面播放中覆盖层）──────────────────────────────────────────────
@Composable
private fun PlayingWaveform() {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val bars = 3

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(bars) { i ->
            val phase = i * 120
            val height by infiniteTransition.animateFloat(
                initialValue = 4f,
                targetValue = 16f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 500,
                        delayMillis = phase,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.9f))
            )
        }
    }
}

// ── 音质标签徽章 ─────────────────────────────────────────────────────────────
@Composable
private fun QualityBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .border(0.5.dp, color.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// ── MainActivity.kt 需要改的两处地方说明 ──────────────────────────────────────
//
// 【改动 1】setContent 替换主题（约第 400 行）
//
//   原来：
//     setContent {
//         MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
//             Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
//                 MusicAppScreen(shouldOpenPlayer = shouldOpenPlayer)
//             }
//         }
//     }
//
//   改成：
//     setContent {
//         ThemeManager.loadSavedPreset(this)
//         AuralisTheme {                          // ← 改用新 Theme
//             Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
//                 MusicAppScreen(shouldOpenPlayer = shouldOpenPlayer)
//             }
//         }
//     }
//   需要 import: com.example.mymusic.ui.theme.AuralisTheme
//
// ────────────────────────────────────────────────────────────────────────────
//
// 【改动 2】切歌时提取专辑色（在 MusicAppScreen 里 currentArtwork 变化处，约第 810 行左右）
//   找到这一段（大约是处理 onArtworkChanged 或 mediaController listener 更新 currentArtwork 的地方）：
//
//     currentArtwork = ... // 你赋值 artwork ByteArray 的那一行
//
//   在它下面加：
//
//     scope.launch {
//         currentArtwork?.let { bytes ->
//             val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//             if (bmp != null) ThemeManager.updateFromArtwork(bmp)
//         }
//     }
//   需要 import: android.graphics.BitmapFactory
//
// ────────────────────────────────────────────────────────────────────────────
//
// 【改动 3】首页顶部搜索栏替换
//   找到原来的 Row { OutlinedTextField + sort + settings + sync }（约第 960 行）整段替换：
//
//     HomeHeader(
//         totalSongs = allSongs.size,
//         searchQuery = searchQuery,
//         onSearchChange = { searchQuery = it },
//         onSortClick = { expandedSortMenu = true },
//         onSettingsClick = { showSettingsScreen = true },
//         onSyncClick = { if (pcServerIp.endsWith(".") || savedFolderUriStr == null) showSettingsScreen = true else fetchSongsList() },
//         currentTitle = currentTitle
//     )
//
//   原来那一整个 Row { ... } 块删掉就行
//
// ══════════════════════════════════════════════════════════════════════════════
