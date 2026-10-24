package com.auralis.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auralis.app.NearbyConnectionsTransport.Phase
import com.auralis.app.NearbyConnectionsTransport.Role

/**
 * "不依赖路由器传输"的入口卡片：发送 / 接收 两个按钮。
 * 点击后先检查环境（Google Play 服务、权限、蓝牙），通过了才真正开始。
 */
@Composable
fun NearbyConnectionsEntryCard(
    onSend: () -> Unit,
    onReceive: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WifiTethering, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.nc_entry_title),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.nc_entry_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSend, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.nc_btn_send))
                }
                OutlinedButton(onClick = onReceive, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.nc_btn_receive))
                }
            }
        }
    }
}

/** 传输过程的底部面板：搜索 / 等待 / 验证码确认 / 进度 / 结果，全部在这里。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyConnectionsSheet(onDismiss: () -> Unit) {
    val state by NearbyConnectionsTransport.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 传输期间保持屏幕常亮：息屏后 Wi-Fi Direct / 热点这类连接更容易被系统掐断
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 面板被关掉 = 取消本次传输
    val close = {
        NearbyConnectionsTransport.cancel()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.nc_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))

            when (state.phase) {
                Phase.ADVERTISING -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.nc_waiting_receive, state.localName),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Phase.DISCOVERING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.nc_searching),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (state.endpoints.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                            items(state.endpoints, key = { it.id }) { ep ->
                                ListItem(
                                    headlineContent = { Text(ep.name, fontWeight = FontWeight.Medium) },
                                    supportingContent = { Text(stringResource(R.string.nc_tap_to_connect)) },
                                    leadingContent = { Icon(Icons.Filled.PhoneAndroid, null) },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { NearbyConnectionsTransport.connectTo(ep.id) }
                                )
                            }
                        }
                    }
                }

                Phase.CONNECTING -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.nc_connecting, state.peerName ?: ""),
                        textAlign = TextAlign.Center
                    )
                }

                Phase.CONNECTED -> {
                    val p = state.progress
                    Text(
                        if (p.totalFiles > 1)
                            stringResource(R.string.nc_transferring, minOf(p.doneFiles + p.failedFiles + 1, p.totalFiles), p.totalFiles)
                        else (state.currentTitle ?: stringResource(R.string.nc_sheet_title)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    if (p.determinate) {
                        LinearProgressIndicator(
                            progress = { p.fraction },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            if (p.determinate) append("${(p.fraction * 100).toInt()}%")
                            if (p.totalBytes > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${formatTransferBytes(p.bytes)} / ${formatTransferBytes(p.totalBytes)}")
                            }
                            if (p.speedBps > 0) append(" · ${formatTransferBytes(p.speedBps)}/s")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Phase.DONE -> {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.nc_done, state.okCount, state.failedCount),
                        textAlign = TextAlign.Center
                    )
                }

                Phase.ERROR -> {
                    Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Phase.IDLE -> {}
            }

            Spacer(Modifier.height(20.dp))
            val finishedPhase = state.phase == Phase.DONE || state.phase == Phase.ERROR
            if (finishedPhase) {
                Button(onClick = close, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.nc_close)) }
            } else {
                TextButton(onClick = close) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }

    // 验证码确认：官方文档要求必须验证，否则连接不安全。
    state.auth?.let { auth ->
        AlertDialog(
            onDismissRequest = { NearbyConnectionsTransport.rejectAuth() },
            icon = { Icon(Icons.Filled.Security, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.nc_auth_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nc_auth_text, auth.peerName), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        auth.digits,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
            ApplyDialogBlur() // 统一的"背后模糊"（Android 12+），和均衡器对话框一致

                Button(onClick = { NearbyConnectionsTransport.acceptAuth() }) { Text(stringResource(R.string.nc_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { NearbyConnectionsTransport.rejectAuth() }) { Text(stringResource(R.string.nc_reject)) }
            }
        )
    }
}

/**
 * 环境检查：Google Play 服务 / 蓝牙 / 定位开关 / 运行时权限。
 * 返回 null 表示全部就绪；否则返回要给用户看的提示（缺权限的情况由调用方去弹系统授权框）。
 */
fun nearbyConnectionsPrecheck(context: android.content.Context): String? {
    if (!NearbyConnectionsPermissions.playServicesAvailable(context)) return context.getString(R.string.nc_err_no_gms)
    if (!NearbyConnectionsPermissions.bluetoothEnabled(context)) return context.getString(R.string.nc_err_bluetooth)
    if (!NearbyConnectionsPermissions.locationServiceOk(context)) return context.getString(R.string.nc_err_location)
    return null
}
