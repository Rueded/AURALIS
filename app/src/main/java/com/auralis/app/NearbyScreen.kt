package com.auralis.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    onBack: () -> Unit,
    currentSong: Song?,          // 当前播放中的歌，用于快速投喂
) {
    val context      = LocalContext.current
    val scope        = rememberCoroutineScope()
    val gson         = remember { Gson() }

    // ── 状态 ───────────────────────────────────────────────────
    val discoveredDevices by NsdHelper.discovered.collectAsState()
    val boundDevices      = remember { mutableStateListOf<BoundDevice>() }
    var isScanning        by remember { mutableStateOf(false) }

    // 投喂/下载 UI 状态
    var selectedDevice    by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var showSongPicker    by remember { mutableStateOf(false) }
    var allSongs          by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedSongs     by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSending         by remember { mutableStateOf(false) }
    var sendResult        by remember { mutableStateOf<String?>(null) }

    // 绑定确认
    var deviceToBindDialog by remember { mutableStateOf<DiscoveredDevice?>(null) }

    // ── 初始化 ──────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        // 读取已绑定列表
        boundDevices.clear()
        boundDevices.addAll(BoundDeviceStore.getAll(context))
        // 启动扫描
        // ✅ 不重复启动，discovery 已在 MusicAppScreen 启动
// 只刷新一次让 UI 显示 isScanning 状态
        isScanning = true
        delay(3_000)
        isScanning = false
    }

    DisposableEffect(Unit) {
        onDispose {
            // ✅ 不停止 discovery，保持后台持续扫描
            // 只有 app 完全退出时才停
        }
    }

    // 合并：把 discoveredDevices 按 UUID 匹配到 boundDevices
    val onlineDeviceIds = discoveredDevices.map { it.deviceId }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("附近的 Auralis", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (isScanning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            val inf = rememberInfiniteTransition(label = "scan")
                            val angle by inf.animateFloat(
                                initialValue = 0f, targetValue = 360f,
                                animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
                                label = "rot"
                            )
                            Icon(
                                Icons.Filled.Sync, null,
                                modifier = Modifier.size(20.dp)
                                    .graphicsLayer { rotationZ = angle },
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("扫描中", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(onClick = {
                            scope.launch {
                                isScanning = true
                                NsdHelper.startDiscovery(context)
                                delay(12_000)
                                NsdHelper.stopDiscovery()
                                isScanning = false
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, "重新扫描")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── 已绑定设备 ─────────────────────────────────────
            if (boundDevices.isNotEmpty()) {
                item {
                    Text(
                        "已绑定",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
                items(boundDevices, key = { it.deviceId }) { bound ->
                    val liveDevice = discoveredDevices.firstOrNull { it.deviceId == bound.deviceId }
                    val isOnline   = liveDevice != null
                    BoundDeviceCard(
                        bound      = bound,
                        isOnline   = isOnline,
                        onSend     = {
                            if (liveDevice != null) {
                                selectedDevice = liveDevice
                                scope.launch(Dispatchers.IO) {
                                    allSongs = AppDatabase.getDatabase(context).songDao()
                                        .getAllSongs().first()
                                    // 预选当前歌
                                    currentSong?.let { selectedSongs = setOf(it.data) }
                                }
                                showSongPicker = true
                            }
                        },
                        onPushCurrent = {
                            if (liveDevice != null && currentSong != null) {
                                scope.launch { pushSong(context, liveDevice, currentSong, gson) }
                            }
                        },
                        onUnbind   = {
                            BoundDeviceStore.unbind(context, bound.deviceId)
                            boundDevices.removeAll { it.deviceId == bound.deviceId }
                        },
                        hasCurrent = currentSong != null && isOnline
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // ── 发现的新设备 ───────────────────────────────────
            val newDevices = discoveredDevices.filter { d ->
                !BoundDeviceStore.isBound(context, d.deviceId)
            }

            item {
                Text(
                    if (newDevices.isEmpty() && !isScanning) "附近暂无设备"
                    else "发现新设备",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            items(newDevices, key = { it.deviceId }) { device ->
                NewDeviceCard(
                    device  = device,
                    onBind  = { deviceToBindDialog = device },
                    onSend  = {
                        selectedDevice = device
                        scope.launch(Dispatchers.IO) {
                            allSongs = AppDatabase.getDatabase(context).songDao()
                                .getAllSongs().first()
                            currentSong?.let { selectedSongs = setOf(it.data) }
                        }
                        showSongPicker = true
                    }
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // ── 绑定确认弹窗 ────────────────────────────────────────────
    deviceToBindDialog?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToBindDialog = null },
            shape = RoundedCornerShape(20.dp),
            icon = { Icon(Icons.Filled.Link, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("绑定设备？") },
            text  = {
                Text("将「${device.deviceName}」添加到绑定列表后，下次无需重新扫描，直接出现在顶部。")
            },
            confirmButton = {
                Button(onClick = {
                    val bound = BoundDevice(
                        deviceId    = device.deviceId,
                        displayName = device.deviceName,
                        lastSeenMs  = System.currentTimeMillis()
                    )
                    BoundDeviceStore.bind(context, bound)
                    boundDevices.clear()
                    boundDevices.addAll(BoundDeviceStore.getAll(context))
                    deviceToBindDialog = null
                }) { Text("绑定") }
            },
            dismissButton = {
                TextButton(onClick = { deviceToBindDialog = null }) { Text("暂不") }
            }
        )
    }

    // ── 选歌弹窗 ────────────────────────────────────────────────
    if (showSongPicker && selectedDevice != null) {
        SongPickerSheet(
            songs        = allSongs,
            selected     = selectedSongs,
            onToggle     = { path ->
                selectedSongs = if (selectedSongs.contains(path))
                    selectedSongs - path else selectedSongs + path
            },
            isSending    = isSending,
            sendResult   = sendResult,
            onSend       = {
                val device = selectedDevice ?: return@SongPickerSheet
                val toSend = allSongs.filter { selectedSongs.contains(it.data) }
                if (toSend.isEmpty()) return@SongPickerSheet
                isSending = true
                sendResult = null
                scope.launch {
                    val ok = sendSongs(context, device, toSend, gson)
                    isSending = false
                    sendResult = if (ok) "✅ 传输完成！" else "❌ 传输失败，对方可能未开启接收"
                    if (ok) {
                        delay(1500)
                        showSongPicker = false
                        selectedSongs  = emptySet()
                        sendResult     = null
                    }
                }
            },
            onDismiss = {
                showSongPicker = false
                selectedSongs  = emptySet()
                sendResult     = null
            }
        )
    }
}

// ── 已绑定设备卡片 ────────────────────────────────────────────────

@Composable
private fun BoundDeviceCard(
    bound: BoundDevice,
    isOnline: Boolean,
    hasCurrent: Boolean,
    onSend: () -> Unit,
    onPushCurrent: () -> Unit,
    onUnbind: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (isOnline)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态指示灯
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                        )
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Filled.PhoneAndroid, null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isOnline) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        bound.displayName,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        if (isOnline) "在线 · 已绑定" else "离线 · 已绑定",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 投喂当前歌
                    if (hasCurrent) {
                        FilledTonalButton(
                            onClick  = onPushCurrent,
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Send, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("推送当前歌", fontSize = 13.sp)
                        }
                    }
                    // 选歌发送
                    OutlinedButton(
                        onClick  = onSend,
                        enabled  = isOnline,
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("选歌传输", fontSize = 13.sp)
                    }
                    // 解绑
                    IconButton(onClick = onUnbind) {
                        Icon(
                            Icons.Filled.LinkOff, "解绑",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// ── 新发现设备卡片 ────────────────────────────────────────────────

@Composable
private fun NewDeviceCard(
    device: DiscoveredDevice,
    onBind: () -> Unit,
    onSend: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.PhoneAndroid, null,
                modifier = Modifier.size(28.dp),
                tint     = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.deviceName, fontWeight = FontWeight.Medium)
                Text(
                    device.host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onBind) { Text("绑定") }
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(
                onClick = onSend,
                shape   = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Send, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("发送")
            }
        }
    }
}

// ── 选歌底部面板 ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongPickerSheet(
    songs: List<Song>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    isSending: Boolean,
    sendResult: String?,
    onSend: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortAscending by remember { mutableStateOf(true) }
    var sortType by remember { mutableStateOf("Name") }
    var showSortMenu by remember { mutableStateOf(false) }

    val displaySongs = remember(songs, searchQuery, sortAscending) {
        songs
            .filter {
                searchQuery.isBlank() ||
                        it.title.contains(searchQuery, ignoreCase = true) ||
                        it.artist.contains(searchQuery, ignoreCase = true)
            }
            .let { list ->
                val sorted = when (sortType) {
                    "Artist"    -> list.sortedBy { it.artist }
                    "Album"     -> list.sortedBy { it.album }
                    "Date"      -> list.sortedBy { it.dateModified }
                    "Size"      -> list.sortedBy { it.size }
                    "PlayCount" -> list.sortedBy { it.playCount }
                    else        -> list.sortedBy { it.title }
                }
                if (sortAscending) sorted else sorted.reversed()
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "选择要发送的歌曲",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "已选 ${selected.size} 首",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(10.dp))

            // 搜索栏 + 排序按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索歌名、歌手…") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Clear, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                )
                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(Icons.Filled.Sort, "排序", tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (sortAscending) "升序" else "降序",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.SwapVert, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { sortAscending = !sortAscending; showSortMenu = false }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        listOf(
                            "Name"      to "按名称",
                            "Artist"    to "按歌手",
                            "Album"     to "按专辑",
                            "Date"      to "按日期",
                            "Size"      to "按大小",
                            "PlayCount" to "按播放次数"
                        ).forEach { (type, label) ->
                            val selected = sortType == type
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                trailingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                } else null,
                                onClick = { sortType = type; showSortMenu = false }
                            )
                        }
                    }
                }
                // 全选/全不选
                IconButton(
                    onClick = {
                        if (selected.size == displaySongs.size) {
                            displaySongs.forEach { onToggle(it.data) }
                        } else {
                            displaySongs.filter { !selected.contains(it.data) }
                                .forEach { onToggle(it.data) }
                        }
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        if (selected.size == displaySongs.size && displaySongs.isNotEmpty())
                            Icons.Filled.CheckBox
                        else
                            Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = "全选",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 歌曲列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 380.dp)
            ) {
                items(displaySongs, key = { it.data }) { song ->
                    val isChecked = selected.contains(song.data)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(song.data) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onToggle(song.data) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    )
                }

                if (displaySongs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "没有找到匹配的歌曲",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            sendResult?.let {
                Text(
                    it,
                    color = if (it.startsWith("✅")) Color(0xFF4CAF50)
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Button(
                onClick = onSend,
                enabled = selected.isNotEmpty() && !isSending,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("传输中…")
                } else {
                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("发送 ${selected.size} 首")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── 接收推送弹窗（在 MusicAppScreen 层调用） ─────────────────────

@Composable
fun IncomingPushDialog(
    request: PushRequest,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val coverBitmap = remember(request.coverBase64) {
        if (request.coverBase64.isNotEmpty()) {
            try {
                val bytes = android.util.Base64.decode(request.coverBase64, android.util.Base64.NO_WRAP)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        } else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 封面
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = coverBitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Filled.MusicNote, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 发送者
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.PhoneAndroid, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            request.senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "想和你分享一首歌",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    request.songTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    request.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                if (request.hasLrc) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            "📄 含歌词",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即接收")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) { Text("暂时不要") }
        }
    )
}

// ── 网络操作（suspend） ───────────────────────────────────────────

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

/** 推送"当前正在播放"给对方（不传文件，只推通知） */
suspend fun pushSong(
    context: Context,
    device: DiscoveredDevice,
    song: Song,
    gson: Gson
): Boolean = withContext(Dispatchers.IO) {
    try {
        val coverBitmap = CoverArtCache.loadBitmapFromDisk(context, song.data)
        val coverB64 = if (coverBitmap != null) {
            val small = Bitmap.createScaledBitmap(coverBitmap, 120, 120, true)
            val bos   = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 60, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } else ""

        val lrcExists = java.io.File(
            java.io.File(song.data).parent,
            "${java.io.File(song.data).nameWithoutExtension}.lrc"
        ).exists()

        val body = gson.toJson(
            mapOf(
                "senderName"     to AuralisDeviceId.getName(context),
                "senderDeviceId" to AuralisDeviceId.getId(context),
                "songTitle"      to song.title,
                "artist"         to song.artist,
                "filename"       to java.io.File(song.data).name,
                "hasLrc"         to lrcExists,
                "coverBase64"    to coverB64
            )
        )

        val req = Request.Builder()
            .url("http://${device.host}:${device.port}/auralis/push")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = httpClient.newCall(req).execute()
        resp.isSuccessful
    } catch (e: Exception) {
        Log.e("NearbyScreen", "推送失败: ${e.message}")
        false
    }
}

/** 把选中的歌曲文件传到对方（流式 HTTP 下载方向反过来：我是服务端，对方来拉） */
suspend fun sendSongs(
    context: Context,
    device: DiscoveredDevice,
    songs: List<Song>,
    gson: Gson
): Boolean = withContext(Dispatchers.IO) {
    // 原理：我方已经开着 AuralisServer，通知对方来我这里拉取
    // 先推一条 push 通知，让对方弹出下载确认
    try {
        songs.forEach { song ->
            pushSong(context, device, song, gson)
            delay(200)
        }
        true
    } catch (e: Exception) {
        Log.e("NearbyScreen", "sendSongs 失败: ${e.message}")
        false
    }
}

/** 从对方 AuralisServer 下载歌曲到本地 */
suspend fun downloadFromPeer(
    context: Context,
    push: PushRequest,
    saveFolder: String,
    onProgress: (Float) -> Unit = {}   // ✅ 新增进度回调
): Boolean = withContext(Dispatchers.IO) {
    try {
        val baseUrl  = "http://${push.senderIp}:${AuralisServer.PORT}"
        val filename = push.filename
        val destDir  = java.io.File(saveFolder).apply { mkdirs() }
        val destFile = java.io.File(destDir, filename)

        val req  = Request.Builder().url("$baseUrl/auralis/download/$filename").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext false

        val body = resp.body ?: return@withContext false
        val totalBytes = body.contentLength()  // -1 if unknown

        var downloadedBytes = 0L
        val buffer = ByteArray(8192)

        body.byteStream().use { input ->
            destFile.outputStream().use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (totalBytes > 0) {
                        onProgress(downloadedBytes.toFloat() / totalBytes)
                    }
                }
            }
        }

        // MediaStore 通知
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(destFile.absolutePath),
            null
        ) { _, _ -> }

        // LRC
        if (push.hasLrc) {
            val lrcName = "${java.io.File(filename).nameWithoutExtension}.lrc"
            val lrcDest = java.io.File(destDir, lrcName)
            try {
                val lrcResp = httpClient.newCall(
                    Request.Builder().url("$baseUrl/auralis/download/$lrcName").build()
                ).execute()
                if (lrcResp.isSuccessful) {
                    lrcResp.body?.byteStream()?.use { i ->
                        lrcDest.outputStream().use { o -> i.copyTo(o) }
                    }
                }
            } catch (_: Exception) {}
        }

        onProgress(1f)
        true
    } catch (e: Exception) {
        Log.e("NearbyScreen", "下载失败: ${e.message}")
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyShareQuickSheet(
    song: Song,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }
    val discoveredDevices by NsdHelper.discovered.collectAsState()
    val boundDevices = remember { BoundDeviceStore.getAll(context) }
    var sendingTo by remember { mutableStateOf<String?>(null) }
    var resultMsg by remember { mutableStateOf<String?>(null) }

    // 合并在线设备（绑定的在前）
    val onlineDevices = remember(discoveredDevices, boundDevices) {
        val bound = boundDevices
            .mapNotNull { b -> discoveredDevices.firstOrNull { it.deviceId == b.deviceId } }
        val others = discoveredDevices.filter { d -> bound.none { it.deviceId == d.deviceId } }
        bound + others
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Text(
                "分享给附近设备",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${song.title} · ${song.artist}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))

            if (onlineDevices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "附近没有在线的 Auralis 设备\n请确保对方在同一 WiFi 且已开启应用",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                onlineDevices.forEach { device ->
                    val isBound = boundDevices.any { it.deviceId == device.deviceId }
                    val isSending = sendingTo == device.deviceId

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.PhoneAndroid, null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(device.deviceName, fontWeight = FontWeight.Medium)
                                    if (isBound) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                "已绑定",
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                                Text(
                                    device.host,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                FilledTonalButton(
                                    onClick = {
                                        sendingTo = device.deviceId
                                        resultMsg = null
                                        scope.launch {
                                            val ok = pushSong(context, device, song, gson)
                                            sendingTo = null
                                            resultMsg = if (ok) "✅ 已推送给 ${device.deviceName}"
                                            else "❌ 推送失败"
                                            if (ok) delay(1500)
                                            if (ok) onDismiss()
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("发送")
                                }
                            }
                        }
                    }
                }
            }

            resultMsg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = if (it.startsWith("✅")) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}