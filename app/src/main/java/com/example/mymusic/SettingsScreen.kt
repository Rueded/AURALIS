package com.example.mymusic

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

// ── 设置项数据模型 ────────────────────────────────────────────────────────────
private data class SettingToggleItem(
    val icon: ImageVector,
    val iconTint: Color,
    val title: String,
    val subtitle: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val onToggle: (Boolean) -> Unit
)

// ── 主入口 ────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    // ── 从外部注入的状态（与 MainActivity 共享） ──
    enableReplayGain: Boolean,
    onReplayGainChange: (Boolean) -> Unit,
    enableBitPerfect: Boolean,
    onBitPerfectChange: (Boolean) -> Unit,
    isPcMode: Boolean,
    onPcModeChange: (Boolean) -> Unit,
    pcServerIp: String,
    onPcServerIpChange: (String) -> Unit,
    allowedFolders: Set<String>,
    onFolderAdded: (String) -> Unit,
    onFolderRemoved: (String) -> Unit,
    onRescanLibrary: () -> Unit,
    onBatchImportLrc: () -> Unit,
    onShowSleepTimer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 在线歌词开关（本地持久化）
    val prefs = remember { context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE) }
    var enableOnlineLyrics by remember { mutableStateOf(prefs.getBoolean("enable_online_lyrics", true)) }
    var onlineLyricsSource by remember { mutableStateOf(prefs.getString("online_lyrics_source", "auto") ?: "auto") }

    // 文件夹选择器
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = uri.lastPathSegment?.replace("primary:", "/storage/emulated/0/") ?: return@rememberLauncherForActivityResult
            onFolderAdded(path)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 一、音频质量 ──────────────────────────────────────────────────
            SettingsSection(
                title = "音频质量",
                icon = Icons.Outlined.Headphones,
                iconTint = MaterialTheme.colorScheme.primary
            ) {
                SettingToggleRow(
                    item = SettingToggleItem(
                        icon = Icons.Outlined.Equalizer,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = "音量标准化",
                        subtitle = "ReplayGain · 自动平衡不同歌曲响度",
                        checked = enableReplayGain,
                        onToggle = onReplayGainChange
                    )
                )
                SettingsDivider()
                SettingToggleRow(
                    item = SettingToggleItem(
                        icon = Icons.Outlined.SettingsInputHdmi,
                        iconTint = if (enableBitPerfect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        title = "USB 源码直通",
                        subtitle = if (Build.VERSION.SDK_INT < 34)
                            "Bit-perfect · 需要 Android 14+ 及外接 DAC"
                        else if (enableBitPerfect)
                            "Bit-perfect · 已绕过系统混音器 ✓"
                        else
                            "Bit-perfect · 绕过 AudioFlinger，连接 USB DAC 生效",
                        checked = enableBitPerfect,
                        enabled = Build.VERSION.SDK_INT >= 34,
                        onToggle = { checked ->
                            onBitPerfectChange(checked)
                            if (checked && Build.VERSION.SDK_INT < 34) {
                                Toast.makeText(context, "您的系统低于 Android 14，不支持此功能", Toast.LENGTH_SHORT).show()
                            } else if (checked) {
                                Toast.makeText(context, "已开启源码直通，请连接 USB DAC", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                )
            }

            // ── 二、歌词 ──────────────────────────────────────────────────────
            SettingsSection(
                title = "歌词",
                icon = Icons.Outlined.Lyrics,
                iconTint = MaterialTheme.colorScheme.secondary
            ) {
                SettingToggleRow(
                    item = SettingToggleItem(
                        icon = Icons.Outlined.CloudDownload,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = "在线歌词搜索",
                        subtitle = "无本地 LRC 时自动联网获取",
                        checked = enableOnlineLyrics,
                        onToggle = { checked ->
                            enableOnlineLyrics = checked
                            prefs.edit().putBoolean("enable_online_lyrics", checked).apply()
                        }
                    )
                )

                // 来源选择（仅在在线歌词开启时展示）
                AnimatedVisibility(
                    visible = enableOnlineLyrics,
                    enter = expandVertically(tween(200)),
                    exit = shrinkVertically(tween(200))
                ) {
                    Column {
                        SettingsDivider()
                        // 来源选择按钮组
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                "优先来源",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("auto" to "自动（最快）", "kugou" to "酷狗", "qq" to "QQ音乐").forEach { (key, label) ->
                                    val selected = onlineLyricsSource == key
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            onlineLyricsSource = key
                                            prefs.edit().putString("online_lyrics_source", key).apply()
                                        },
                                        label = { Text(label, fontSize = 12.sp) }
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsDivider()
                SettingsClickRow(
                    icon = Icons.Outlined.FileOpen,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    title = "批量导入 LRC",
                    subtitle = "从文件管理器批量选择歌词文件",
                    onClick = onBatchImportLrc
                )
            }

            // ── 三、音乐库 ────────────────────────────────────────────────────
            SettingsSection(
                title = "音乐库",
                icon = Icons.Outlined.LibraryMusic,
                iconTint = Color(0xFF7C4DFF)
            ) {
                // 已添加的扫描路径列表
                if (allowedFolders.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "扫描全盘音乐",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    allowedFolders.forEachIndexed { i, folder ->
                        if (i > 0) SettingsDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = Color(0xFF7C4DFF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                folder,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 2
                            )
                            IconButton(
                                onClick = { onFolderRemoved(folder) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "移除",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                SettingsDivider()
                SettingsClickRow(
                    icon = Icons.Outlined.CreateNewFolder,
                    iconTint = Color(0xFF7C4DFF),
                    title = "添加扫描路径",
                    subtitle = "指定文件夹，不再扫描全盘",
                    onClick = { folderPickerLauncher.launch(null) }
                )
                SettingsDivider()
                SettingsClickRow(
                    icon = Icons.Outlined.Refresh,
                    iconTint = Color(0xFF7C4DFF),
                    title = "重新深度扫描",
                    subtitle = "清空缓存，重新解析所有音乐文件元数据",
                    onClick = onRescanLibrary
                )
            }

            // ── 四、连接 ──────────────────────────────────────────────────────
            SettingsSection(
                title = "连接",
                icon = Icons.Outlined.Wifi,
                iconTint = Color(0xFF00897B)
            ) {
                SettingToggleRow(
                    item = SettingToggleItem(
                        icon = Icons.Outlined.Speaker,
                        iconTint = if (isPcMode) Color(0xFF00897B) else MaterialTheme.colorScheme.outline,
                        title = "PC 有线音箱模式",
                        subtitle = if (isPcMode) "正在监听端口…" else "将手机变为 PC 的零延迟音箱",
                        checked = isPcMode,
                        onToggle = onPcModeChange
                    )
                )
                SettingsDivider()
                // IP 输入框
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = pcServerIp,
                        onValueChange = onPcServerIpChange,
                        label = { Text("电脑局域网 IP") },
                        leadingIcon = { Icon(Icons.Outlined.Computer, null, modifier = Modifier.size(20.dp)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // ── 五、其他 ──────────────────────────────────────────────────────
            SettingsSection(
                title = "其他",
                icon = Icons.Outlined.MoreHoriz,
                iconTint = MaterialTheme.colorScheme.outline
            ) {
                SettingsClickRow(
                    icon = Icons.Outlined.Bedtime,
                    iconTint = Color(0xFF5C6BC0),
                    title = "睡眠定时器",
                    subtitle = "定时暂停播放",
                    onClick = onShowSleepTimer
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── 子组件 ────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        // 区块标题行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 卡片容器
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingToggleRow(item: SettingToggleItem) {
    val iconTint by animateColorAsState(item.iconTint, tween(300), label = "iconTint")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.enabled) { item.onToggle(!item.checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            tint = if (item.enabled) iconTint else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (item.enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (item.enabled) 1f else 0.4f
                ),
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = item.checked,
            onCheckedChange = { item.onToggle(it) },
            enabled = item.enabled
        )
    }
}

@Composable
private fun SettingsClickRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}
