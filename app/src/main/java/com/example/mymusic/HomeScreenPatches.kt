package com.example.mymusic

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// ══════════════════════════════════════════════════════════════════════════════
// HomeHeader — 替换原来的 Row { OutlinedTextField + Sort + Settings + Sync }
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeHeader(
    totalSongs: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortType: String,
    isAscending: Boolean,
    onSortChange: (type: String, asc: Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    var expandedSort by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 标题行 ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "AURALIS",
                    style = MaterialTheme.typography.labelSmall,
                    color = primary,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$totalSongs",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "首曲目",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            // 💡 修复：极具高级感的内联排序菜单
            Box {
                IconButton(onClick = { expandedSort = true }) {
                    Icon(
                        Icons.Filled.Sort, "排序",
                        tint = if (expandedSort) primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = expandedSort,
                    onDismissRequest = { expandedSort = false },
                    // 稍微加一点圆角，更显精致
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    // 1. 顶部第一行：独立的升降序切换开关
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isAscending) "A-Z / 升序排列" else "Z-A / 降序排列",
                                color = primary,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.SwapVert, null, tint = primary) },
                        onClick = {
                            onSortChange(sortType, !isAscending)
                            expandedSort = false
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))

                    // 2. 下面是具体的排序选项
                    listOf(
                        "Name" to "按名称",
                        "Date" to "按日期",
                        "Size" to "按大小"
                    ).forEach { (type, label) ->
                        val selected = sortType == type
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            // 选中时，尾部出现漂亮的打勾图标
                            trailingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, null, tint = primary, modifier = Modifier.size(18.dp)) }
                            } else null,
                            onClick = {
                                // 如果点击的不是当前项，默认重置为升序；如果是当前项，保持现有升降序
                                val newAsc = if (sortType == type) isAscending else true
                                onSortChange(type, newAsc)
                                expandedSort = false
                            }
                        )
                    }
                }
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
                Icon(Icons.Filled.Search, "搜索",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                { IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Filled.Close, "清除", modifier = Modifier.size(18.dp)) }
                }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = primary.copy(alpha = 0.6f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedContainerColor   = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            )
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// SongItemUI — 修复：律动条、封面圆角、保留所有原有功能
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItemUI(
    song: Song,
    index: Int,
    isCurrentSong: Boolean, // 👈 新增参数：标记是不是“当前选中”的歌
    isPlaying: Boolean,     // 👈 原有的，代表全局是否正在发出声音
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
    var spec   by remember(song.data) { mutableStateOf(cachedInfo?.spec) }
    var bitmap by remember(song.data) { mutableStateOf(cachedInfo?.bitmap) }

    val globalCover by PlayerStateHolder.coverBitmap.collectAsState()
    LaunchedEffect(globalCover, isCurrentSong) {
        // 只有当前正在播放的那首歌，才允许它强行更新为全局下载的高清封面！
        if (isCurrentSong && globalCover != null) {
            bitmap = globalCover
        }
    }

    var showButtonMenu    by remember { mutableStateOf(false) }
    var showTouchMenu     by remember { mutableStateOf(false) }
    var touchOffset       by remember { mutableStateOf(Offset.Zero) }
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
                        File(File(song.data).parent,
                            "${File(song.data).nameWithoutExtension}.lrc")
                    )
                    ins?.copyTo(out); ins?.close(); out.close()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "歌词导入成功！", Toast.LENGTH_SHORT).show()
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

    val marqueeModifier =
        if (isPlaying && allowMarquee) Modifier.basicMarquee() else Modifier

    // 播放中行高亮
    val rowBg by animateColorAsState(
        targetValue = if (isCurrentSong) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else Color.Transparent,
        animationSpec = tween(400), label = "rowBg"
    )

    // 菜单项（三点按钮和长按都用这个）
    val menuItems = @Composable {
        DropdownMenuItem(
            text = { Text("下一首播放") },
            leadingIcon = { Icon(Icons.Filled.PlaylistPlay, null) },
            onClick = { closeAllMenus(); onPlayNext() }
        )
        DropdownMenuItem(
            text = { Text("添加到歌单...") },
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
                Icon(Icons.Filled.Delete, null,
                    tint = MaterialTheme.colorScheme.error)
            },
            onClick = { closeAllMenus(); showDeleteConfirm = true }
        )
    }

    Box(modifier = Modifier.fillMaxWidth().background(rowBg)) {

        // 长按锚点菜单
        Box(
            modifier = Modifier
                .offset { IntOffset(touchOffset.x.toInt(), touchOffset.y.toInt()) }
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
                        onTap = { _ -> onClick() },
                        onLongPress = { offset: Offset ->
                            touchOffset = offset
                            showTouchMenu = true
                        }
                    )
                }
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
            Text("${index + 1}", color = if (isCurrentSong) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.Center
            )

            // ── 封面（修复：使用圆角正方形，和原版圆形保持一致）──────────────
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AdvancedFluidCover(
                        seedString = song.data,
                        iconSize = 20.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // ── 修复：isPlaying=false 时律动条不再显示 ──────────────────
                if (isCurrentSong) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        PlayingWaveform(isPlaying = isPlaying) // 👈 只有这里用 isPlaying，决定它跳不跳！
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // ── 文字 ─────────────────────────────────────────────────────────
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isCurrentSong) MaterialTheme.colorScheme.primary
                        else Color.Unspecified,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(marqueeModifier)
                    )
                    // 保留原版音质标签
                    spec?.let { s ->
                        if (s.level != AudioLevel.STANDARD) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .border(1.dp, s.level.color, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(s.level.label, fontSize = 9.sp,
                                    color = s.level.color, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (s.isSpatial) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .border(1.dp, s.spatialColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(s.spatialLabel, fontSize = 9.sp,
                                    color = s.spatialColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(marqueeModifier)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${song.size / 1048576} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }

            // 三点菜单
            Box {
                IconButton(onClick = { showButtonMenu = true }) {
                    Icon(Icons.Filled.MoreVert, "菜单", tint = Color.Gray)
                }
                DropdownMenu(expanded = showButtonMenu, onDismissRequest = closeAllMenus) {
                    menuItems()
                }
            }
        }

        // 播放中左侧竖线
        if (isCurrentSong) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("将彻底从手机存储中删除此文件，不可恢复。") },
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

// ══════════════════════════════════════════════════════════════════════════════
// PlayingWaveform — 修复：加 isPlaying 参数，停止时不动
// ══════════════════════════════════════════════════════════════════════════════
@Composable
fun PlayingWaveform(isPlaying: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val height by if (isPlaying) {
                infiniteTransition.animateFloat(
                    initialValue = 4f, targetValue = 16f,
                    animationSpec = infiniteRepeatable(
                        tween(
                            500,
                            delayMillis = i * 120,
                            easing = FastOutSlowInEasing
                        ), RepeatMode.Reverse
                    ),
                    label = "bar$i"
                )
            } else {
                // 暂停时，平滑降落回 4f
                animateFloatAsState(targetValue = 4f, animationSpec = tween(300), label = "stopBar")
            }

            Box(
                modifier = Modifier.width(3.dp).height(height.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.9f))
            )
        }
    }
}
