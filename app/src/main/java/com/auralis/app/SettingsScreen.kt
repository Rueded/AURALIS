package com.auralis.app

import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.horizontalScroll

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
@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    enableReplayGain: Boolean, onReplayGainChange: (Boolean) -> Unit,
    enableBitPerfect: Boolean, onBitPerfectChange: (Boolean) -> Unit,
    isPcMode: Boolean, onPcModeChange: (Boolean) -> Unit,
    pcServerIp: String, onPcServerIpChange: (String) -> Unit,
    savedFolderUriStr: String?, onPickFolder: () -> Unit,
    allowedFolders: Set<String>, onFolderAdded: (String) -> Unit, onFolderRemoved: (String) -> Unit,
    onRescanLibrary: () -> Unit, onBatchImportLrc: () -> Unit, onShowSleepTimer: () -> Unit, onFindDuplicates: () -> Unit,
    onNearbyDevices: () -> Unit, onAbout: () -> Unit
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scrollState = rememberScrollState()
    val prefs = remember { context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE) }

    var enableOnlineLyrics by remember { mutableStateOf(prefs.getBoolean("enable_online_lyrics", true)) }
    var onlineLyricsSource by remember { mutableStateOf(prefs.getString("online_lyrics_source", "auto") ?: "auto") }
    var bgMode by remember { mutableStateOf(BackgroundMode.entries.firstOrNull { it.name == prefs.getString("bg_mode", BackgroundMode.BREATHING.name) } ?: BackgroundMode.BREATHING) }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // ✨ 云糯修复：调用 Android 官方的 DocumentFile 提取纯净的文件夹名称！
            val folderName = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: return@rememberLauncherForActivityResult
            onFolderAdded(folderName)
        }
    }

    Scaffold(
        topBar = { SettingsHeroTopBar(onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 〇 外观 (哥哥最爱的 v1.5 风格网格) ─────────────────────────────
            SettingsSection(stringResource(R.string.settings_section_appearance), Icons.Outlined.Palette, MaterialTheme.colorScheme.primary) {
                Column(modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
                    Text(stringResource(R.string.theme_color_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(12.dp))

                    ThemePickerGrid(context)

                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.custom_hue_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(8.dp))

                    val currentPreset by ThemeManager.preset.collectAsState()
                    val customHue by ThemeManager.customHue.collectAsState()
                    val artworkPrimary by ThemeManager.artworkPrimary.collectAsState()
                    val targetHue = if (currentPreset == AuralisPreset.CUSTOM) customHue else ThemeManager.getHue(ThemeManager.previewColor(currentPreset, artworkPrimary, customHue))

                    // ✨ 修复滑块动画：加回了长达 800 毫秒的柔和滑动过渡！
                    var isDraggingSlider by remember { mutableStateOf(false) }
                    var sliderValue by remember { mutableFloatStateOf(targetHue) }

                    LaunchedEffect(targetHue) {
                        if (!isDraggingSlider) {
                            androidx.compose.animation.core.animate(
                                initialValue = sliderValue,
                                targetValue = targetHue,
                                animationSpec = tween(durationMillis = 800) // ✨ 哥哥要求的长滑动效果
                            ) { value, _ ->
                                sliderValue = value
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Box(modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Brush.horizontalGradient((0..12).map { i -> Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 30f, 0.7f, 0.85f))) })))
                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                isDraggingSlider = true
                                sliderValue = it
                                ThemeManager.setCustomHue(it, context)
                            },
                            onValueChangeFinished = { isDraggingSlider = false },
                            valueRange = 0f..360f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.dark_light_mode_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(10.dp))
                    val forceDark by ThemeManager.forceDark.collectAsState()
                    Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to stringResource(R.string.follow_system), true to stringResource(R.string.dark_mode), false to stringResource(R.string.light_mode)).forEach { (value, label) ->
                            val selected = forceDark == value
                            FilterChip(selected = selected, onClick = { ThemeManager.setForceDark(value, context) }, label = { Text(label, fontSize = 12.sp) }, leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp)) } } else null)
                        }
                    }
                }

                SettingsDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.fullscreen_player_bg_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    // 👇 加上 horizontalScroll，滑动丝滑如德芙！
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        BackgroundMode.entries.forEach { mode ->
                            FilterChip(
                                selected = bgMode == mode,
                                onClick = { bgMode = mode; prefs.edit().putString("bg_mode", mode.name).apply() },
                                label = { Text(backgroundModeDisplayName(mode), fontSize = 12.sp) }
                            )
                        }
                    }

                    // 👇 云糯新增：专属于“音频响应”的灵敏度滑块！
                    AnimatedVisibility(visible = bgMode == BackgroundMode.FLUID || bgMode == BackgroundMode.HORIZON || bgMode == BackgroundMode.CLASSIC_EQ || bgMode == BackgroundMode.STARDUST ) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            var sensitivity by remember { mutableFloatStateOf(prefs.getFloat("reactive_sensitivity", 1.5f)) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                                Text(stringResource(R.string.visualizer_sensitivity_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(String.format("%.1fx", sensitivity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = sensitivity,
                                onValueChange = { sensitivity = it },
                                onValueChangeFinished = { prefs.edit().putFloat("reactive_sensitivity", sensitivity).apply() },
                                valueRange = 0.5f..3.0f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ── 一 音频质量 (统一为粉色系) ──────────────────────────────────────
            SettingsSection(stringResource(R.string.settings_section_audio_quality), Icons.Outlined.Headphones, MaterialTheme.colorScheme.tertiary) {
                SettingToggleRow(
                    item = SettingToggleItem(
                        icon = Icons.Outlined.Equalizer,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.volume_normalization_title),
                        subtitle = stringResource(R.string.replaygain_subtitle),
                        checked = enableReplayGain,
                        onToggle = onReplayGainChange
                    )
                )
                SettingsDivider()
                SettingToggleRow(
                    item = SettingToggleItem(
                        icon = Icons.Outlined.SettingsInputHdmi,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.usb_passthrough_title),
                        subtitle = if (Build.VERSION.SDK_INT < 34) stringResource(R.string.bitperfect_subtitle_needs_android14) else if (enableBitPerfect) stringResource(R.string.bitperfect_subtitle_active) else stringResource(R.string.bitperfect_subtitle_needs_usb_dac),
                        checked = enableBitPerfect,
                        enabled = Build.VERSION.SDK_INT >= 34,
                        onToggle = { onBitPerfectChange(it) }
                    )
                )
                SettingsDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    var crossfadeSecs by remember { mutableFloatStateOf(prefs.getFloat("crossfade_duration", 0f)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CompareArrows, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(stringResource(R.string.crossfade_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                        Text(if (crossfadeSecs > 0) "${crossfadeSecs.toInt()}s" else stringResource(R.string.state_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.crossfade_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 36.dp))
                    Slider(
                        value = crossfadeSecs,
                        onValueChange = { crossfadeSecs = it },
                        onValueChangeFinished = { prefs.edit().putFloat("crossfade_duration", crossfadeSecs).apply() },
                        valueRange = 0f..10f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth().padding(start = 28.dp)
                    )
                }
            }

            // ── 二 歌词 ────────────────────────────────────────
            SettingsSection(stringResource(R.string.settings_section_lyrics), Icons.Outlined.Lyrics, MaterialTheme.colorScheme.secondary) {
                var enableLyricsOverlay by remember { mutableStateOf(prefs.getBoolean("lyrics_overlay_enabled", true)) }
                SettingToggleRow(
                    item = SettingToggleItem(
                        icon = Icons.Outlined.Subtitles,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.lyrics_overlay_title),
                        subtitle = stringResource(R.string.lyrics_overlay_subtitle),
                        checked = enableLyricsOverlay,
                        onToggle = {
                            enableLyricsOverlay = it
                            prefs.edit().putBoolean("lyrics_overlay_enabled", it).apply()
                            PlayerStateHolder.setLyricsOverlayEnabled(it)
                        }
                    )
                )
                SettingsDivider()
                SettingToggleRow(
                    item = SettingToggleItem(
                        icon = Icons.Outlined.CloudDownload,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.online_lyrics_search_title),
                        subtitle = stringResource(R.string.online_lyrics_search_subtitle),
                        checked = enableOnlineLyrics,
                        onToggle = { enableOnlineLyrics = it; prefs.edit().putBoolean("enable_online_lyrics", it).apply() }
                    )
                )
                AnimatedVisibility(visible = enableOnlineLyrics, enter = expandVertically(tween(200)), exit = shrinkVertically(tween(200))) {
                    Column {
                        SettingsDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(stringResource(R.string.lyrics_source_priority_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                listOf(
                                    "auto" to stringResource(R.string.source_auto),
                                    "163" to stringResource(R.string.source_netease),
                                    "qq" to stringResource(R.string.source_qqmusic),
                                    "kugou" to stringResource(R.string.source_kugou),
                                    "lrclib" to "LrcLib"
                                ).forEach { (key, label) ->
                                    FilterChip(
                                        selected = onlineLyricsSource == key,
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
                SettingsClickRow(icon = Icons.Outlined.FileOpen, iconTint = MaterialTheme.colorScheme.secondary, title = stringResource(R.string.action_batch_import_lrc), subtitle = stringResource(R.string.batch_import_lrc_subtitle), onClick = onBatchImportLrc)
            }

            // ── 三 音乐库 ────────────────────────────────────────────────────
            val libraryColor = Color(0xFF7C4DFF)
            SettingsSection(stringResource(R.string.settings_section_library), Icons.Outlined.LibraryMusic, libraryColor) {
                if (allowedFolders.isEmpty()) { Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.scan_all_music_label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                else { allowedFolders.forEachIndexed { i, folder -> if (i > 0) SettingsDivider(); Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Folder, null, tint = libraryColor, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(folder, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2); IconButton(onClick = { onFolderRemoved(folder) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, stringResource(R.string.action_remove), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline) } } } }
                SettingsDivider()
                SettingsClickRow(icon = Icons.Outlined.CreateNewFolder, iconTint = libraryColor, title = stringResource(R.string.action_add_scan_path), subtitle = stringResource(R.string.add_scan_path_subtitle), onClick = { folderPickerLauncher.launch(null) })
                SettingsDivider()
                SettingsClickRow(icon = Icons.Outlined.Refresh, iconTint = libraryColor, title = stringResource(R.string.action_deep_rescan), subtitle = stringResource(R.string.deep_rescan_subtitle), onClick = onRescanLibrary)
                SettingsDivider()
                SettingsClickRow(
                    icon = Icons.Outlined.ContentCopy,
                    iconTint = libraryColor,
                    title = stringResource(R.string.action_clean_duplicates),
                    subtitle = stringResource(R.string.clean_duplicates_subtitle),
                    onClick = onFindDuplicates
                )
            }

            // ── 四 连接与同步 ──────────────────────────────────────────────────
            val syncColor = Color(0xFF00897B)
            SettingsSection(stringResource(R.string.settings_section_connectivity), Icons.Outlined.Wifi, syncColor) {
                SettingToggleRow(item = SettingToggleItem(icon = Icons.Outlined.Speaker, iconTint = syncColor, title = stringResource(R.string.pc_speaker_mode_title), subtitle = if (isPcMode) stringResource(R.string.pc_speaker_mode_listening) else stringResource(R.string.pc_speaker_mode_subtitle), checked = isPcMode, onToggle = onPcModeChange))
                SettingsDivider()
                SettingsClickRow(icon = Icons.Outlined.FolderSpecial, iconTint = syncColor, title = stringResource(R.string.sync_save_path_title), subtitle = if (savedFolderUriStr != null) stringResource(R.string.configured_label) else stringResource(R.string.no_download_location_set), onClick = onPickFolder)
                SettingsDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { OutlinedTextField(value = pcServerIp, onValueChange = onPcServerIpChange, label = { Text(stringResource(R.string.pc_lan_ip_label)) }, leadingIcon = { Icon(Icons.Outlined.Computer, null, modifier = Modifier.size(20.dp)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    SettingsDivider()
// 接收开关
                    var receiveEnabled by remember {
                        mutableStateOf(prefs.getBoolean("auralis_receive_enabled", true))
                    }
                    SettingToggleRow(
                        item = SettingToggleItem(
                            icon     = Icons.Outlined.Inbox,
                            iconTint = syncColor,
                            title    = stringResource(R.string.accept_nearby_push_title),
                            subtitle = if (receiveEnabled) stringResource(R.string.accept_nearby_push_subtitle_on) else stringResource(R.string.accept_nearby_push_subtitle_off),
                            checked  = receiveEnabled,
                            onToggle = {
                                receiveEnabled = it
                                prefs.edit().putBoolean("auralis_receive_enabled", it).apply()
                            }
                        )
                    )
                    SettingsDivider()
                    SettingsClickRow(
                        icon     = Icons.Outlined.Wifi,
                        iconTint = syncColor,
                        title    = stringResource(R.string.nearby_auralis_title),
                        subtitle = stringResource(R.string.nearby_auralis_subtitle),
                        onClick  = onNearbyDevices
                    )}
            }

            var customCookie by remember {
                mutableStateOf(prefs.getString("netease_custom_cookie", "") ?: "")
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        stringResource(R.string.netease_cookie_auth_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.netease_cookie_auth_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = customCookie,
                        onValueChange = {
                            customCookie = it
                            prefs.edit().putString("netease_custom_cookie", it).apply()
                        },
                        label = { Text(stringResource(R.string.custom_cookie_label)) },
                        placeholder = { Text("MUSIC_U=xxx; __csrf=yyy...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            SettingsSection(stringResource(R.string.settings_section_other), Icons.Outlined.MoreHoriz, MaterialTheme.colorScheme.outline) {
                SettingsClickRow(icon = Icons.Outlined.Bedtime, iconTint = Color(0xFF5C6BC0), title = stringResource(R.string.sleep_timer_title), subtitle = stringResource(R.string.sleep_timer_subtitle), onClick = onShowSleepTimer)
                SettingsDivider()
                // ── 语言切换：中文 / English ──
                val currentLang by LocalizationManager.language.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.language_label), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.language_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = currentLang == lang,
                                onClick = {
                                    LocalizationManager.setLanguage(context, lang)
                                    // 光改偏好设置不够——弹窗(AlertDialog/Dialog)拿到的是
                                    // Activity attachBaseContext 时那份 Resources，
                                    // 必须让 Activity 重新走一遍生命周期才能让所有窗口都刷新。
                                    activity.recreate()
                                },
                                label = { Text(lang.displayName) }
                            )
                        }
                    }
                }
                SettingsDivider()
                SettingsClickRow(
                    icon     = Icons.Outlined.Info,
                    iconTint = MaterialTheme.colorScheme.outline,
                    title    = stringResource(R.string.about_title),
                    subtitle = stringResource(R.string.about_subtitle),
                    onClick  = onAbout
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── 子组件 ────────────────────────────────────────────────────────────────────

@Composable
fun ThemePickerGrid(context: Context) {
    val currentPreset by ThemeManager.preset.collectAsState()
    val artworkPrimary by ThemeManager.artworkPrimary.collectAsState()
    val customHue by ThemeManager.customHue.collectAsState()
    val forceDark by ThemeManager.forceDark.collectAsState()
    val isDark = forceDark ?: isSystemInDarkTheme()

    val presets = listOf(
        Triple(AuralisPreset.DYNAMIC, artworkPrimary, stringResource(R.string.theme_dynamic_cover)),
        Triple(AuralisPreset.OBSIDIAN, Color(0xFFE2E2E2), stringResource(R.string.theme_obsidian)),
        Triple(AuralisPreset.MIDNIGHT, Color(0xFF7EB8F7), stringResource(R.string.theme_midnight)),
        Triple(AuralisPreset.AMBER, Color(0xFFFFB74D), stringResource(R.string.theme_amber)),
        Triple(AuralisPreset.ROSE, Color(0xFFF48FB1), stringResource(R.string.theme_rose)),
        Triple(AuralisPreset.AURORA, Color(0xFF69F0AE), stringResource(R.string.theme_aurora)),
        Triple(AuralisPreset.VIOLET, Color(0xFFCE93D8), stringResource(R.string.theme_violet)),
        Triple(AuralisPreset.SOLAR, Color(0xFFFFF176), stringResource(R.string.theme_solar))
    )

    val rows = presets.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (preset, color, subtitle) ->
                    ThemePresetCard(
                        preset = preset, accentColor = color, subtitle = subtitle, artworkPrimary = artworkPrimary, customHue = customHue, isDark = isDark,
                        isSelected = currentPreset == preset, onSelect = { ThemeManager.setPreset(preset, context) }, modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun backgroundModeDisplayName(mode: BackgroundMode): String = when (mode) {
    BackgroundMode.STATIC     -> stringResource(R.string.bgmode_static)
    BackgroundMode.BREATHING  -> stringResource(R.string.bgmode_breathing)
    BackgroundMode.FLUID      -> stringResource(R.string.bgmode_fluid)
    BackgroundMode.HORIZON    -> stringResource(R.string.bgmode_horizon)
    BackgroundMode.CLASSIC_EQ -> stringResource(R.string.bgmode_classic_eq)
    BackgroundMode.STARDUST   -> stringResource(R.string.bgmode_stardust)
}

@Composable
private fun presetDisplayName(preset: AuralisPreset): String = when (preset) {
    AuralisPreset.DYNAMIC  -> stringResource(R.string.preset_name_dynamic)
    AuralisPreset.OBSIDIAN -> stringResource(R.string.preset_name_obsidian)
    AuralisPreset.MIDNIGHT -> stringResource(R.string.preset_name_midnight)
    AuralisPreset.AMBER    -> stringResource(R.string.preset_name_amber)
    AuralisPreset.ROSE     -> stringResource(R.string.preset_name_rose)
    AuralisPreset.JADE     -> stringResource(R.string.preset_name_jade)
    AuralisPreset.AURORA   -> stringResource(R.string.preset_name_aurora)
    AuralisPreset.VIOLET   -> stringResource(R.string.preset_name_violet)
    AuralisPreset.SOLAR    -> stringResource(R.string.preset_name_solar)
    AuralisPreset.CUSTOM   -> stringResource(R.string.preset_name_custom)
}

@Composable
private fun ThemePresetCard(
    preset: AuralisPreset, accentColor: Color, subtitle: String, artworkPrimary: Color, customHue: Float, isDark: Boolean, isSelected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(targetValue = if (isSelected) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), label = "border")
    val bgColor by animateColorAsState(targetValue = if (isSelected) accentColor.copy(alpha = 0.08f) else Color.Transparent, label = "bg")

    Surface(onClick = onSelect, modifier = modifier, shape = RoundedCornerShape(14.dp), color = bgColor, border = BorderStroke(width = if (isSelected) 1.5.dp else 0.5.dp, color = borderColor)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(accentColor))
                val bgPreview = ThemeManager.buildScheme(preset, artworkPrimary, customHue, isDark).background
                Box(modifier = Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(4.dp)).background(bgPreview))
                if (isSelected) { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = accentColor) }
            }
            Spacer(Modifier.height(8.dp))
            Text(presetDisplayName(preset), style = MaterialTheme.typography.bodyMedium, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: ImageVector, iconTint: Color, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
            Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

@Composable
private fun SettingToggleRow(item: SettingToggleItem) {
    val targetTint = if (item.enabled && item.checked) item.iconTint else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val iconTint by animateColorAsState(targetTint, label = "iconTint")

    Row(modifier = Modifier.fillMaxWidth().clickable(enabled = item.enabled) { item.onToggle(!item.checked) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(item.icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (item.enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (item.enabled) 1f else 0.4f), lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = item.checked, onCheckedChange = { item.onToggle(it) }, enabled = item.enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = item.iconTint, checkedBorderColor = item.iconTint)
        )
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
