package com.auralis.app

import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
    // “一起听”当前加入的房主设备（真正的轮询/播放对齐逻辑在 MusicAppScreen 那边做，
    // 这里只负责让用户点按钮来 join/leave）
    val joinedRoomHost    by RoomManager.joinedHost.collectAsState()

    // 投喂/下载 UI 状态
    var selectedDevice    by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var showSongPicker    by remember { mutableStateOf(false) }
    var allSongs          by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedSongs     by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSending         by remember { mutableStateOf(false) }
    var sendResult        by remember { mutableStateOf<String?>(null) }

    // 绑定确认
    var deviceToBindDialog by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var showManualDialog   by remember { mutableStateOf(false) }

    // ── Nearby Connections（不依赖路由器的备用传输）──
    var showNcSheet     by remember { mutableStateOf(false) }
    var ncPickingSongs  by remember { mutableStateOf(false) }
    var ncPendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ncPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = ncPendingAction
        ncPendingAction = null
        if (result.values.all { it }) action?.invoke()
        else android.widget.Toast.makeText(context, context.getString(R.string.nc_err_permission), android.widget.Toast.LENGTH_LONG).show()
    }
    /** 先检查 Google Play 服务 / 蓝牙 / 定位开关，再申请运行时权限，全部就绪后才执行 [action]。 */
    fun ncRun(action: () -> Unit) {
        val problem = nearbyConnectionsPrecheck(context)
        if (problem != null) {
            android.widget.Toast.makeText(context, problem, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val missing = NearbyConnectionsPermissions.missing(context)
        if (missing.isEmpty()) action()
        else { ncPendingAction = action; ncPermLauncher.launch(missing.toTypedArray()) }
    }

    // ── 初始化 ──────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        // 读取已绑定列表
        boundDevices.clear()
        boundDevices.addAll(BoundDeviceStore.getAll(context))
        // 启动扫描
        // ✅ 不重复启动，discovery 已在 MusicAppScreen 启动
// 只刷新一次让 UI 显示 isScanning 状态
        isScanning = true
        // mDNS 被网络拦截时（校园网/访客网络/部分路由器）扫不到设备，这里同时扫一遍本网段兜底
        scope.launch(Dispatchers.IO) { NsdHelper.scanSubnet(context) }
        delay(4_000)
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
                title = { Text(stringResource(R.string.nearby_auralis_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { showManualDialog = true }) {
                        Icon(Icons.Filled.Link, stringResource(R.string.nearby_manual_title))
                    }
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
                            Text(stringResource(R.string.scanning_label), fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(onClick = {
                            scope.launch {
                                isScanning = true
                                // 重新开始扫描即可。以前扫完 12 秒后调用 stopDiscovery()：
                                // 它会停掉整个 app 的持续扫描并且清空已发现的设备列表，
                                // 点一次"重新扫描"之后设备反而全消失了，要重启 app 才恢复。
                                NsdHelper.startDiscovery(context)
                                val subnet = launch(Dispatchers.IO) { NsdHelper.scanSubnet(context) }
                                delay(5_000)
                                subnet.join()
                                isScanning = false
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.action_rescan))
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

            // ── 不依赖路由器的传输（Google 附近分享）─────────────
            item {
                NearbyConnectionsEntryCard(
                    onSend = {
                        scope.launch(Dispatchers.IO) {
                            allSongs = AppDatabase.getDatabase(context).songDao().getAllSongs().first()
                            selectedSongs = emptySet()
                            ncPickingSongs = true
                        }
                    },
                    onReceive = {
                        ncRun {
                            NearbyConnectionsTransport.startReceiving(context)
                            showNcSheet = true
                        }
                    }
                )
            }

            // ── 一起听：房主开关 ─────────────────────────────
            item {
                val isHosting by RoomManager.isHosting.collectAsState()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isHosting) Icons.Filled.Headset else Icons.Outlined.Headset,
                            null,
                            tint = if (isHosting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.nearby_allow_listen_together), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (isHosting) stringResource(R.string.nearby_hosting_on_subtitle) else stringResource(R.string.nearby_hosting_off_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isHosting,
                            onCheckedChange = { if (it) RoomManager.startHosting() else RoomManager.stopHosting() }
                        )
                    }
                }
            }

            // ── 已绑定设备 ─────────────────────────────────────
            if (boundDevices.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.bound_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
                items(boundDevices, key = { it.deviceId }) { bound ->
                    val liveDevice = discoveredDevices.firstOrNull { it.deviceId == bound.deviceId }
                    val isOnline   = liveDevice?.reachable == true
                    val isListeningTogether = joinedRoomHost?.deviceId == bound.deviceId
                    BoundDeviceCard(
                        bound      = bound,
                        isOnline   = isOnline,
                        isListeningTogether = isListeningTogether,
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
                        onToggleListenTogether = {
                            if (isListeningTogether) {
                                RoomManager.leave()
                            } else if (liveDevice != null) {
                                RoomManager.join(liveDevice)
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
                    if (newDevices.isEmpty() && !isScanning) stringResource(R.string.no_devices_nearby)
                    else stringResource(R.string.new_devices_found),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            if (newDevices.isEmpty() && !isScanning) {
                item {
                    Text(
                        stringResource(R.string.nearby_no_devices_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
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
            title = { Text(stringResource(R.string.bind_device_title)) },
            text  = {
                Text(stringResource(R.string.bind_device_body, device.deviceName))
            },
            confirmButton = {
            ApplyDialogBlur() // 统一的"背后模糊"（Android 12+），和均衡器对话框一致

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
                }) { Text(stringResource(R.string.action_bind)) }
            },
            dismissButton = {
                TextButton(onClick = { deviceToBindDialog = null }) { Text(stringResource(R.string.action_not_now)) }
            }
        )
    }

    if (showManualDialog) ManualConnectDialog(onDismiss = { showManualDialog = false })

    // Nearby Connections：发送方先选歌，选好后开始搜索附近设备
    if (ncPickingSongs) {
        SongPickerSheet(
            songs      = allSongs,
            selected   = selectedSongs,
            onToggle   = { path ->
                selectedSongs = if (selectedSongs.contains(path)) selectedSongs - path else selectedSongs + path
            },
            isSending  = false,
            sendResult = null,
            onSend     = {
                val toSend = allSongs.filter { selectedSongs.contains(it.data) }
                if (toSend.isEmpty()) return@SongPickerSheet
                ncPickingSongs = false
                selectedSongs  = emptySet()
                ncRun {
                    NearbyConnectionsTransport.startSending(context, toSend)
                    showNcSheet = true
                }
            },
            onDismiss  = {
                ncPickingSongs = false
                selectedSongs  = emptySet()
            }
        )
    }
    if (showNcSheet) NearbyConnectionsSheet(onDismiss = { showNcSheet = false })

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
                    sendResult = if (ok) context.getString(R.string.nearby_sent_waiting_confirm) else context.getString(R.string.transfer_failed_maybe_not_receiving)
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
    isListeningTogether: Boolean,
    onSend: () -> Unit,
    onPushCurrent: () -> Unit,
    onToggleListenTogether: () -> Unit,
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
                        if (isOnline) stringResource(R.string.online_bound_label) else stringResource(R.string.offline_bound_label),
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
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
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
                                Text(stringResource(R.string.action_push_current_song), fontSize = 13.sp)
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
                            Text(stringResource(R.string.action_pick_songs_to_send), fontSize = 13.sp)
                        }
                        // 解绑
                        IconButton(onClick = onUnbind) {
                            Icon(
                                Icons.Filled.LinkOff, stringResource(R.string.action_unbind),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 一起听：只读跟播对方正在放的歌（不会反过来控制对方）
                    FilledTonalButton(
                        onClick  = onToggleListenTogether,
                        enabled  = isOnline,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = if (isListeningTogether)
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        else ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Icon(
                            if (isListeningTogether) Icons.Filled.Headset else Icons.Outlined.Headset,
                            null, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isListeningTogether) stringResource(R.string.listening_together_active) else stringResource(R.string.listen_along),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

// ── 手动连接对话框 ────────────────────────────────────────────────
// 自动发现（mDNS）被网络拦截时的兜底：直接输入对方 IP。

@Composable
private fun ManualConnectDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val myIps = remember { LanNet.localIpv4().map { it.ip } }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(20.dp),
        icon = { Icon(Icons.Filled.Link, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.nearby_manual_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.nearby_manual_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (myIps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.nearby_manual_my_ip, myIps.joinToString(", ")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; error = null },
                    label = { Text(stringResource(R.string.nearby_manual_ip_label)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
                    ),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } }
                )
            }
        },
        confirmButton = {
            ApplyDialogBlur() // 统一的"背后模糊"（Android 12+），和均衡器对话框一致

            Button(
                enabled = host.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        // 支持 "192.168.1.5" 或 "192.168.1.5:5001"
                        val parts = host.trim().split(':')
                        val h = parts[0]
                        val p = parts.getOrNull(1)?.toIntOrNull() ?: AuralisServer.PORT
                        val dev = NsdHelper.connectManually(context, h, p)
                        busy = false
                        if (dev != null) onDismiss()
                        else error = context.getString(R.string.nearby_manual_failed, host.trim())
                    }
                }
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.nearby_manual_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
        }
    )
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
                if (!device.reachable) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(
                            when {
                                device.lastError == "refused"     -> R.string.nearby_err_refused
                                device.lastError == "read_timeout" -> R.string.nearby_err_frozen
                                device.lastError == "unreachable" -> R.string.nearby_err_noroute
                                else                              -> R.string.nearby_unreachable_hint
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            TextButton(onClick = onBind) { Text(stringResource(R.string.action_bind)) }
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(
                onClick = onSend,
                enabled = device.reachable,
                shape   = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Send, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_send))
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
    // 默认改成按时间排序、新的在前——传歌/备份场景下，最想先看到的通常是最近改动过的歌，
    // 而不是按名字排序时东一个西一个混在列表中间。
    var sortAscending by remember { mutableStateOf(false) }
    var sortType by remember { mutableStateOf("Date") }
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
                    stringResource(R.string.select_songs_to_send_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.selected_count_label, selected.size),
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
                    placeholder = { Text(stringResource(R.string.search_song_artist_placeholder)) },
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
                        Icon(Icons.Filled.Sort, stringResource(R.string.action_sort), tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (sortAscending) stringResource(R.string.sort_ascending) else stringResource(R.string.sort_descending),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.SwapVert, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { sortAscending = !sortAscending; showSortMenu = false }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        listOf(
                            "Name"      to stringResource(R.string.sort_by_name),
                            "Artist"    to stringResource(R.string.sort_by_artist),
                            "Album"     to stringResource(R.string.sort_by_album),
                            "Date"      to stringResource(R.string.sort_by_date),
                            "Size"      to stringResource(R.string.sort_by_size),
                            "PlayCount" to stringResource(R.string.sort_by_play_count)
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
                        contentDescription = stringResource(R.string.action_select_all),
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
                                stringResource(R.string.no_matching_songs),
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
                    Text(stringResource(R.string.transferring_ellipsis))
                } else {
                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send_count_label, selected.size))
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

    val items = remember(request) { request.allItems() }
    val totalSize = remember(items) { items.sumOf { it.size.coerceAtLeast(0L) } }

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
                    if (items.size > 1)
                        stringResource(R.string.nearby_wants_share_count, items.size, formatTransferBytes(totalSize))
                    else stringResource(R.string.wants_to_share_song),
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
                if (items.size > 1) {
                    Text(
                        stringResource(R.string.nearby_and_more, items.size - 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }

                if (items.any { it.hasLrc }) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            stringResource(R.string.has_lyrics_badge),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            ApplyDialogBlur() // 统一的"背后模糊"（Android 12+），和均衡器对话框一致

            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_receive_now))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.action_not_now_alt)) }
        }
    )
}

// ── 网络操作（suspend） ───────────────────────────────────────────

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

/** 一次批量接收的进度。 */
data class TransferProgress(
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val failedFiles: Int = 0,
    val bytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBps: Long = 0L
) {
    /** 已知总字节数就按字节算（最平滑）；否则多首歌按完成数算；单首且大小未知时是"不确定"进度。 */
    val determinate: Boolean get() = totalBytes > 0 || totalFiles > 1
    val fraction: Float get() = when {
        totalBytes > 0 -> (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        totalFiles > 0 -> doneFiles.toFloat() / totalFiles
        else -> 0f
    }
}

data class BatchResult(val ok: Int, val failed: Int)

fun formatTransferBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1024      -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
    else           -> "$b B"
}

/**
 * 一次推送一批歌（只发一条通知，对方只需确认一次）。返回对方是否接受了这次推送。
 * 以前是每首歌单独推送一条：对方要点 N 次"接收"，而且一首传完才开始下一首。
 */
suspend fun pushSongs(
    context: Context,
    device: DiscoveredDevice,
    songs: List<Song>,
    gson: Gson
): Boolean = withContext(Dispatchers.IO) {
    try {
        val existing = songs.filter { java.io.File(it.data).exists() }
        if (existing.isEmpty()) {
            Log.w("NearbyScreen", "要发送的歌曲文件都不存在")
            return@withContext false
        }

        // 先登记"允许对方下载这几首"，再通知对方
        AuralisServer.offer(context, existing.map { it.data })

        val items = existing.map { s ->
            val f = java.io.File(s.data)
            PushItem(
                filename = f.name,
                title    = s.title,
                artist   = s.artist,
                size     = f.length(),
                hasLrc   = java.io.File(f.parent, "${f.nameWithoutExtension}.lrc").exists()
            )
        }

        val first = existing.first()
        val coverBitmap = CoverArtCache.loadBitmapFromDisk(context, first.data)
        val coverB64 = if (coverBitmap != null) {
            val small = Bitmap.createScaledBitmap(coverBitmap, 120, 120, true)
            val bos   = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 60, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } else ""

        val body = gson.toJson(
            PushRequest(
                senderName     = AuralisDeviceId.getName(context),
                senderDeviceId = AuralisDeviceId.getId(context),
                senderIp       = "",                       // 接收方用 TCP 连接的来源地址填写
                songTitle      = first.title,
                artist         = first.artist,
                filename       = items[0].filename,
                hasLrc         = items[0].hasLrc,
                coverBase64    = coverB64,
                items          = items
            )
        )

        val req = Request.Builder()
            .url(NsdHelper.peerUrl(device.host, device.port, "auralis", "push"))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        // 要读响应体确认 ok=true（对方关了接收开关时是 200 + ok:false），并关闭响应避免连接泄漏
        LanNet.clientFor(context, httpClient, device.host).newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val accepted = resp.isSuccessful && text.contains("\"ok\":true")
            if (!accepted) Log.w("NearbyScreen", "对方没有接受推送: HTTP ${resp.code} $text")
            accepted
        }
    } catch (e: Exception) {
        Log.e("NearbyScreen", "推送失败: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

/** 单曲推送（"推送当前播放"用），内部就是一批只有一首。 */
suspend fun pushSong(context: Context, device: DiscoveredDevice, song: Song, gson: Gson): Boolean =
    pushSongs(context, device, listOf(song), gson)

/** 把选中的歌曲一次性通知给对方；对方确认后会自己来我这里拉取。 */
suspend fun sendSongs(
    context: Context,
    device: DiscoveredDevice,
    songs: List<Song>,
    gson: Gson
): Boolean = pushSongs(context, device, songs, gson)

/** 同时下载几首。局域网里单个连接常常跑不满带宽（尤其是很多小文件时握手/写入占比高），3 路并发是个稳妥的折中。 */
private const val DOWNLOAD_CONCURRENCY = 3

/**
 * 批量接收：最多 [DOWNLOAD_CONCURRENCY] 首并发下载，整体按"字节"汇报进度。
 * 单首失败会自动重试一次，且不会影响其他歌曲。
 */
suspend fun downloadBatch(
    context: Context,
    push: PushRequest,
    saveFolder: String,
    onProgress: (TransferProgress) -> Unit
): BatchResult = withContext(Dispatchers.IO) {
    val items = push.allItems()
    val client = LanNet.clientFor(context, httpClient, push.senderIp)
    TransferLocks.acquire(context, 30 * 60 * 1000L)   // 传输期间保持 CPU/Wi-Fi 不休眠
    val readNs  = java.util.concurrent.atomic.AtomicLong(0L)
    val writeNs = java.util.concurrent.atomic.AtomicLong(0L)

    // 总大小优先用对方推送里带的 size（不依赖 HTTP 响应头，进度条永远可靠）；
    // 旧版发送方没有 size 时，拿到响应头的 Content-Length 再补上。
    val totalBytes = java.util.concurrent.atomic.AtomicLong(items.sumOf { it.size.coerceAtLeast(0L) })
    val bytes      = java.util.concurrent.atomic.AtomicLong(0L)
    val done       = java.util.concurrent.atomic.AtomicInteger(0)
    val failed     = java.util.concurrent.atomic.AtomicInteger(0)
    val startNs    = System.nanoTime()
    val lastEmitNs = java.util.concurrent.atomic.AtomicLong(0L)

    // 进度回调最多每 100ms 一次。以前每读 16KB 就写一次 Compose 状态，
    // 大文件时每秒几千次状态写入，没必要，还会让界面刷新节奏很不均匀。
    fun emit(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastEmitNs.get() < 100_000_000L) return
        lastEmitNs.set(now)
        val elapsed = (now - startNs) / 1_000_000_000.0
        val speed = if (elapsed > 0.5) (bytes.get() / elapsed).toLong() else 0L
        onProgress(TransferProgress(done.get(), items.size, failed.get(), bytes.get(), totalBytes.get(), speed))
    }

    emit(force = true)
    val sem = kotlinx.coroutines.sync.Semaphore(DOWNLOAD_CONCURRENCY)

    try {
        coroutineScope {
            items.map { item ->
                async {
                    sem.acquire()
                    try {
                        val counted = java.util.concurrent.atomic.AtomicBoolean(item.size > 0)
                        var ok = false
                        for (attempt in 1..2) {
                            if (!isActive) break
                            ok = downloadOne(
                                context, client, push.senderIp, item, saveFolder, counted,
                                onBytes = { delta -> bytes.addAndGet(delta); emit() },
                                onTotal = { add -> totalBytes.addAndGet(add) },
                                readNs = readNs, writeNs = writeNs
                            )
                            if (ok) break
                            Log.w("NearbyScreen", "第 $attempt 次下载失败: ${item.filename}")
                            delay(500)
                        }
                        if (ok) done.incrementAndGet() else failed.incrementAndGet()
                        emit(force = true)
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll()
        }
    } finally {
        TransferLocks.release()   // 无论成功、失败还是被取消都要释放
    }

    emit(force = true)
    val wall = (System.nanoTime() - startNs) / 1e9
    val mb = bytes.get() / 1048576.0
    val rn = readNs.get().toDouble(); val wn = writeNs.get().toDouble()
    Log.i("NearbyPerf", "共 ${"%.1f".format(mb)}MB 用时 ${"%.1f".format(wall)}s 平均 ${"%.1f".format(if (wall > 0) mb / wall else 0.0)}MB/s " +
        "等待数据 ${"%.0f".format(rn / (rn + wn + 1) * 100)}% 写存储 ${"%.0f".format(wn / (rn + wn + 1) * 100)}% 并发 $DOWNLOAD_CONCURRENCY")
    BatchResult(done.get(), failed.get())
}

/** 下载一首（含歌词）。失败返回 false，并把已经计入进度的字节数扣回去，方便重试。 */
private fun downloadOne(
    context: Context,
    client: OkHttpClient,
    host: String,
    item: PushItem,
    saveFolder: String,
    totalCounted: java.util.concurrent.atomic.AtomicBoolean,
    onBytes: (Long) -> Unit,
    onTotal: (Long) -> Unit,
    readNs: java.util.concurrent.atomic.AtomicLong,
    writeNs: java.util.concurrent.atomic.AtomicLong
): Boolean {
    val tag = "NearbyScreen"
    val resolver = context.contentResolver
    var pendingUri: android.net.Uri? = null
    var gotBytes = 0L
    return try {
        // 只取文件名部分，防止对方发来带 ../ 的恶意路径
        val filename = java.io.File(item.filename).name
        if (host.isBlank() || filename.isBlank()) {
            Log.e(tag, "下载参数无效: host='$host' filename='$filename'")
            return false
        }
        // 用 HttpUrl 拼地址：文件名里的空格/#/?/%/中文都会被正确编码
        fun urlFor(name: String) = NsdHelper.peerUrl(host, AuralisServer.PORT, "auralis", "download", name)

        // ── 音频：通过 MediaStore 写入（不需要存储写权限，插入后系统媒体库自动可见）──
        val audioCollection = android.provider.MediaStore.Audio.Media
            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = android.os.Environment.DIRECTORY_MUSIC + "/"

        var alreadyHave = false
        resolver.query(
            audioCollection,
            arrayOf(android.provider.MediaStore.Audio.Media._ID),
            "${android.provider.MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${android.provider.MediaStore.Audio.Media.RELATIVE_PATH}=?",
            arrayOf(filename, relPath), null
        )?.use { c -> alreadyHave = c.moveToFirst() }

        if (alreadyHave) {
            Log.i(tag, "Music/ 里已有 $filename，跳过音频下载")
            if (item.size > 0) { onBytes(item.size); gotBytes += item.size }
        } else {
            val ext  = java.io.File(filename).extension.lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, mime)
                put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(audioCollection, values)
                ?: throw java.io.IOException("MediaStore 拒绝创建 $filename")
            pendingUri = uri

            client.newCall(Request.Builder().url(urlFor(filename)).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("对方返回 HTTP ${resp.code}")
                val body = resp.body ?: throw java.io.IOException("对方返回空内容")
                val len = body.contentLength()
                if (len > 0 && totalCounted.compareAndSet(false, true)) onTotal(len)

                val buffer = ByteArray(64 * 1024)
                val rawOut = resolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入 $uri")
                // OkHttp 的 read() 每次最多只给 8KB，直接写到 MediaStore/FUSE 就是每 8KB 一次系统调用；
                // 包一层 256KB 的缓冲，把小块攒成大块再写，写存储这一段会快很多。
                val output = java.io.BufferedOutputStream(rawOut, 256 * 1024)
                body.byteStream().use { input ->
                    output.use { out ->
                        while (true) {
                            val r0 = System.nanoTime()
                            val read = input.read(buffer)
                            val r1 = System.nanoTime()
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            readNs.addAndGet(r1 - r0)
                            writeNs.addAndGet(System.nanoTime() - r1)
                            gotBytes += read
                            onBytes(read.toLong())
                        }
                        val f0 = System.nanoTime()
                        out.flush()
                        writeNs.addAndGet(System.nanoTime() - f0)
                    }
                }
            }
            resolver.update(uri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            pendingUri = null
        }

        // ── 歌词（尽力而为，失败不影响歌曲本身算成功）──
        if (item.hasLrc) {
            val lrcName = "${java.io.File(filename).nameWithoutExtension}.lrc"
            try {
                client.newCall(Request.Builder().url(urlFor(lrcName)).build()).execute().use { lrcResp ->
                    if (lrcResp.isSuccessful) {
                        val destDir = java.io.File(saveFolder).apply { mkdirs() }
                        lrcResp.body?.byteStream()?.use { i ->
                            java.io.File(destDir, lrcName).outputStream().use { o -> i.copyTo(o) }
                        }
                    } else Log.w(tag, "歌词下载 HTTP ${lrcResp.code}")
                }
            } catch (e: Exception) {
                Log.w(tag, "歌词保存失败（通常是没有文件写权限）: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        true
    } catch (e: Exception) {
        Log.e(tag, "下载失败 ${item.filename}: ${e.javaClass.simpleName}: ${e.message}")
        pendingUri?.let { try { resolver.delete(it, null, null) } catch (_: Exception) {} }
        if (gotBytes > 0) onBytes(-gotBytes)   // 把这次尝试已经计入的进度扣掉，重试才不会超过 100%
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
        val reachableDevices = discoveredDevices.filter { it.reachable }
        val bound = boundDevices
            .mapNotNull { b -> reachableDevices.firstOrNull { it.deviceId == b.deviceId } }
        val others = reachableDevices.filter { d -> bound.none { it.deviceId == d.deviceId } }
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
                stringResource(R.string.share_to_nearby_device),
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
                        stringResource(R.string.no_nearby_devices_detail),
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
                                                stringResource(R.string.bound_label),
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
                                            resultMsg = if (ok) context.getString(R.string.pushed_to_device, device.deviceName)
                                            else context.getString(R.string.push_failed)
                                            if (ok) delay(1500)
                                            if (ok) onDismiss()
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_send))
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
