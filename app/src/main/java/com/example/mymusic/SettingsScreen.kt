package com.example.mymusic

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    enableReplayGain: Boolean,
    onReplayGainChange: (Boolean) -> Unit,
    enableBitPerfect: Boolean,
    onBitPerfectChange: (Boolean) -> Unit,
    isPcMode: Boolean,
    onPcModeChange: (Boolean) -> Unit,
    pcServerIp: String,
    onPcServerIpChange: (String) -> Unit,
    savedFolderUriStr: String?,       // 💡 修复：接收文件夹路径
    onPickFolder: () -> Unit,         // 💡 修复：接收选择文件夹的回调
    allowedFolders: Set<String>,
    onFolderAdded: (String) -> Unit,
    onFolderRemoved: (String) -> Unit,
    onRescanLibrary: () -> Unit,
    onBatchImportLrc: () -> Unit,
    onShowSleepTimer: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE) }

    var enableOnlineLyrics by remember { mutableStateOf(prefs.getBoolean("enable_online_lyrics", true)) }
    var onlineLyricsSource by remember { mutableStateOf(prefs.getString("online_lyrics_source", "auto") ?: "auto") }
    var bgMode by remember { mutableStateOf(BackgroundMode.entries.firstOrNull { it.name == prefs.getString("bg_mode", BackgroundMode.BREATHING.name) } ?: BackgroundMode.BREATHING) }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = uri.lastPathSegment?.replace("primary:", "/storage/emulated/0/") ?: return@rememberLauncherForActivityResult
            onFolderAdded(path)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 〇 外观 ──────────────────────────────────────────────────────
            SettingsSection("外观", Icons.Outlined.Palette, MaterialTheme.colorScheme.primary) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("主题配色", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    ThemePickerGrid(context)

                    Spacer(Modifier.height(20.dp))
                    Text("自定义色相", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))

                    val currentPreset by ThemeManager.preset.collectAsState()
                    val customHue by ThemeManager.customHue.collectAsState()
                    val artworkPrimary by ThemeManager.artworkPrimary.collectAsState()

                    // 💡 修复：让滑块跟随当前主题的色相动态改变
                    val effectiveHue = if (currentPreset == AuralisPreset.CUSTOM) customHue
                    else ThemeManager.getHue(ThemeManager.previewColor(currentPreset, artworkPrimary, customHue))

                    Box(
                        modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))
                            .background(androidx.compose.ui.graphics.Brush.horizontalGradient((0..12).map { i -> Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 30f, 0.7f, 0.85f))) }))
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = effectiveHue,
                        onValueChange = { ThemeManager.setCustomHue(it, context) },
                        valueRange = 0f..360f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("深浅色模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))

                    val forceDark by ThemeManager.forceDark.collectAsState()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "跟随系统", true to "深色", false to "浅色").forEach { (value, label) ->
                            val selected = forceDark == value
                            FilterChip(selected = selected, onClick = { ThemeManager.setForceDark(value, context) }, label = { Text(label, fontSize = 12.sp) }, leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp)) } } else null)
                        }
                    }
                }

                SettingsDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("播放器背景", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackgroundMode.entries.forEach { mode ->
                            FilterChip(selected = bgMode == mode, onClick = { bgMode = mode; prefs.edit().putString("bg_mode", mode.name).apply() }, label = { Text(mode.label, fontSize = 12.sp) })
                        }
                    }
                }
            }

            // ── 一 音频质量 ──────────────────────────────────────────────────
            SettingsSection("音频质量", Icons.Outlined.Headphones, MaterialTheme.colorScheme.primary) {
                SettingToggleRow(icon = Icons.Outlined.Equalizer, iconTint = MaterialTheme.colorScheme.tertiary, title = "音量标准化", subtitle = "ReplayGain · 自动平衡不同歌曲响度", checked = enableReplayGain, onToggle = onReplayGainChange)
                SettingsDivider()
                SettingToggleRow(icon = Icons.Outlined.SettingsInputHdmi, iconTint = if (enableBitPerfect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, title = "USB 源码直通", subtitle = if (Build.VERSION.SDK_INT < 34) "Bit-perfect · 需要 Android 14+ 及外接 DAC" else if (enableBitPerfect) "Bit-perfect · 已绕过系统混音器 ✓" else "Bit-perfect · 绕过 AudioFlinger，连接 USB DAC 生效", checked = enableBitPerfect, enabled = Build.VERSION.SDK_INT >= 34, onToggle = { onBitPerfectChange(it) })
            }

            // ── 二 歌词 ──────────────────────────────────────────────────────
            SettingsSection("歌词", Icons.Outlined.Lyrics, MaterialTheme.colorScheme.secondary) {
                SettingToggleRow(icon = Icons.Outlined.CloudDownload, iconTint = MaterialTheme.colorScheme.secondary, title = "在线歌词搜索", subtitle = "无本地 LRC 时自动联网获取", checked = enableOnlineLyrics, onToggle = { enableOnlineLyrics = it; prefs.edit().putBoolean("enable_online_lyrics", it).apply() })
                SettingsDivider()
                SettingsClickRow(icon = Icons.Outlined.FileOpen, iconTint = MaterialTheme.colorScheme.secondary, title = "批量导入 LRC", subtitle = "从文件管理器批量选择歌词文件", onClick = onBatchImportLrc)
            }

            // ── 三 音乐库 ────────────────────────────────────────────────────
            SettingsSection("音乐库", Icons.Outlined.LibraryMusic, Color(0xFF7C4DFF)) {
                if (allowedFolders.isEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("扫描全盘音乐", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    allowedFolders.forEachIndexed { i, folder ->
                        if (i > 0) SettingsDivider()
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, null, tint = Color(0xFF7C4DFF), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(folder, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
                            IconButton(onClick = { onFolderRemoved(folder) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "移除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline) }
                        }
                    }
                }
                SettingsDivider()
                SettingsClickRow(icon = Icons.Outlined.CreateNewFolder, iconTint = Color(0xFF7C4DFF), title = "添加扫描路径", subtitle = "指定文件夹，不再扫描全盘", onClick = { folderPickerLauncher.launch(null) })
                SettingsDivider()
                SettingsClickRow(icon = Icons.Outlined.Refresh, iconTint = Color(0xFF7C4DFF), title = "重新深度扫描", subtitle = "清空缓存，重新解析所有音乐文件元数据", onClick = onRescanLibrary)
            }

            // ── 四 连接与同步 ──────────────────────────────────────────────────────
            SettingsSection("连接与同步", Icons.Outlined.Wifi, Color(0xFF00897B)) {
                SettingToggleRow(icon = Icons.Outlined.Speaker, iconTint = if (isPcMode) Color(0xFF00897B) else MaterialTheme.colorScheme.outline, title = "PC 有线音箱模式", subtitle = if (isPcMode) "正在监听端口…" else "将手机变为 PC 的零延迟音箱", checked = isPcMode, onToggle = onPcModeChange)
                SettingsDivider()
                // 💡 修复：补回同步文件夹的选择
                SettingsClickRow(icon = Icons.Outlined.FolderSpecial, iconTint = Color(0xFF00897B), title = "同步保存路径", subtitle = if (savedFolderUriStr != null) "已配置" else "尚未设置下载位置", onClick = onPickFolder)
                SettingsDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(value = pcServerIp, onValueChange = onPcServerIpChange, label = { Text("电脑局域网 IP") }, leadingIcon = { Icon(Icons.Outlined.Computer, null, modifier = Modifier.size(20.dp)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                }
            }

            SettingsSection("其他", Icons.Outlined.MoreHoriz, MaterialTheme.colorScheme.outline) {
                SettingsClickRow(icon = Icons.Outlined.Bedtime, iconTint = Color(0xFF5C6BC0), title = "睡眠定时器", subtitle = "定时暂停播放", onClick = onShowSleepTimer)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun ThemePickerGrid(context: Context) {
    val currentPreset  by ThemeManager.preset.collectAsState()
    val artworkPrimary by ThemeManager.artworkPrimary.collectAsState()
    val customHue      by ThemeManager.customHue.collectAsState()

    val presets = AuralisPreset.entries.filter { it != AuralisPreset.CUSTOM }
    val rows = presets.chunked(3)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    val color = ThemeManager.previewColor(preset, artworkPrimary, customHue)
                    ThemePresetCard(preset = preset, accentColor = color, isSelected = currentPreset == preset, onSelect = { ThemeManager.setPreset(preset, context) }, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ThemePresetCard(preset: AuralisPreset, accentColor: Color, isSelected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val borderColor by animateColorAsState(targetValue = if (isSelected) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), animationSpec = tween(250), label = "border")
    val bgAlpha by androidx.compose.animation.core.animateFloatAsState(targetValue = if (isSelected) 0.10f else 0f, animationSpec = tween(250), label = "bgAlpha")

    Surface(onClick = onSelect, modifier = modifier, shape = RoundedCornerShape(12.dp), color = accentColor.copy(alpha = bgAlpha), border = BorderStroke(width = if (isSelected) 1.5.dp else 0.5.dp, color = borderColor)) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(accentColor))
            Spacer(Modifier.height(6.dp))
            Text(preset.label, style = MaterialTheme.typography.bodySmall, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

// ── 通用子组件保留 ─────────────────────────────────────────────────────────────────
@Composable
private fun SettingsSection(title: String, icon: ImageVector, iconTint: Color, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
            Box(modifier = Modifier.size(26.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(15.dp)) }
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

@Composable
private fun SettingToggleRow(icon: ImageVector, iconTint: Color, title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onToggle(!checked) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) iconTint else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f), lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = { onToggle(it) }, enabled = enabled)
    }
}

@Composable
private fun SettingsClickRow(icon: ImageVector, iconTint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsDivider() { HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) }