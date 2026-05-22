package com.auralis.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 播放器进度条：粗轨道 + 拖拽时间气泡 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumProgressSlider(
    progress: Float,
    isDragging: Boolean,
    dragProgress: Float,
    durationMs: Long,
    accentColor: Color,
    onDragStart: () -> Unit,
    onDragChange: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val display = if (isDragging) dragProgress else progress
    val primary = accentColor

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val bubbleX = (display * (maxWidth.value - 56f)).coerceAtLeast(0f).dp
            if (isDragging && durationMs > 0) {
                Surface(
                    modifier = Modifier
                        .offset(x = bubbleX)
                        .padding(bottom = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    tonalElevation = 4.dp
                ) {
                    Text(
                        formatTime((dragProgress * durationMs).toLong()),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = primary
                    )
                }
            }
        }
        Slider(
            value = display.coerceIn(0f, 1f),
            onValueChange = {
                onDragStart()
                onDragChange(it)
            },
            onValueChangeFinished = { onDragEnd() },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = primary,
                activeTrackColor = primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )
    }
}

/** 封面圆角 + 阴影外框 */
fun Modifier.playerCoverFrame(size: Dp, corner: Dp = 24.dp, glowColor: Color = Color.Black): Modifier =
    this
        .size(size)
        .shadow(20.dp, RoundedCornerShape(corner), spotColor = glowColor.copy(alpha = 0.35f))
        .clip(RoundedCornerShape(corner))

/** 封面 / 歌词 分段切换 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverLyricsSegmentedControl(
    showLyrics: Boolean,
    onCoverSelect: () -> Unit,
    onLyricsSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            listOf(false to "封面", true to "歌词").forEach { (lyrics, label) ->
                val selected = showLyrics == lyrics
                Surface(
                    onClick = if (lyrics) onLyricsSelect else onCoverSelect,
                    shape = RoundedCornerShape(50),
                    color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    tonalElevation = if (selected) 2.dp else 0.dp,
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 单行歌词 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricLineItem(
    text: String,
    timeMs: Long,
    isCurrent: Boolean,
    isPausedForInteraction: Boolean,
    fontSizeSp: Float,
    activeColor: Color = Color(0xFFFFD700), // ✨ 新增参数：接收根据背景色实时算出的最适配高亮色
    onSeek: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(
        if (isCurrent) 1.05f else 1f,
        spring(dampingRatio = 0.65f, stiffness = 350f),
        label = "lyricScale"
    )
    val alpha by animateFloatAsState(
        if (isCurrent) 1f else 0.5f,
        tween(200),
        label = "lyricAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧时间轴提示数字
        Text(
            text = formatTime(timeMs),
            fontSize = 11.sp,
            style = androidx.compose.ui.text.TextStyle(
                color = if (isPausedForInteraction) Color.White else Color.White.copy(alpha = 0.35f),
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black.copy(alpha = 0.8f),
                    offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                    blurRadius = 3f
                )
            ),
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.End
        )
        Spacer(Modifier.width(8.dp))

        // 歌词载体卡片
        Surface(
            onClick = onSeek,
            shape = RoundedCornerShape(14.dp),
            color = if (isCurrent) Color.Black.copy(alpha = 0.28f) else Color.Transparent
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = if (isCurrent) (fontSizeSp + 3).sp else fontSizeSp.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = (fontSizeSp + 8).sp,
                // ✨ 修复：动态采用自适应 activeColor 色彩方案，搭配抗眩光边缘全景阴影
                style = androidx.compose.ui.text.TextStyle(
                    color = if (isCurrent) activeColor else Color.White.copy(alpha = alpha),
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.95f),
                        offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                        blurRadius = 5f
                    )
                )
            )
        }
        Spacer(Modifier.width(50.dp))
    }
}

/** 歌手气泡行（多歌手用间距分隔，无斜杠） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistChipRow(
    artist: String,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val artists = artist.split("/").map { it.trim() }.filter { it.isNotEmpty() }
    if (artists.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(
            8.dp,
            Alignment.CenterHorizontally
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        artists.forEach { name ->
            Surface(
                onClick = { onArtistClick(name) },
                shape = RoundedCornerShape(if (compact) 8.dp else 10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                )
            ) {
                Text(
                    name,
                    modifier = Modifier.padding(
                        horizontal = if (compact) 8.dp else 10.dp,
                        vertical = if (compact) 3.dp else 4.dp
                    ),
                    style = if (compact) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun AudioSpecBadges(spec: AudioSpec, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = spec.level.color.copy(alpha = 0.12f),
            border = androidx.compose.foundation.BorderStroke(1.dp, spec.level.color.copy(0.5f))
        ) {
            Text(
                spec.specText,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                fontSize = 11.sp,
                color = spec.level.color,
                fontWeight = FontWeight.Bold
            )
        }
        if (spec.isSpatial) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = spec.spatialColor.copy(alpha = 0.12f),
                border = androidx.compose.foundation.BorderStroke(1.dp, spec.spatialColor.copy(0.5f))
            ) {
                Text(
                    spec.spatialLabel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 10.sp,
                    color = spec.spatialColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 倍速芯片 + 锚定菜单（修复弹出位置） */
@Composable
fun SpeedControlChip(
    playbackSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val label = if (playbackSpeed == 1.0f) "1.0x" else String.format("%.2fx", playbackSpeed)

    Box(modifier = modifier) {
        PlayerToolChip(
            label = label,
            selected = playbackSpeed != 1.0f,
            onClick = { showDialog = true }
        )

        if (showDialog) {
            var tempSpeed by remember { mutableFloatStateOf(playbackSpeed) }

            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("播放倍速 (自然变调)", fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "当前倍速: ${String.format("%.2fx", tempSpeed)}",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(16.dp))
                        Slider(
                            value = tempSpeed,
                            onValueChange = {
                                tempSpeed = it
                                onSpeedSelected(it) // 实时反馈调节
                            },
                            valueRange = 0.5f..2.0f
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "提示：已启用自然变速算法。减速时声音变粗，加速时声音变细，避免了强制保调带来的金属电音，以获得最佳无损音质与纯净听感。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDialog = false }) { Text("完成") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        tempSpeed = 1.0f
                        onSpeedSelected(1.0f)
                    }) { Text("重置 1.0x") }
                }
            )
        }
    }
}

/** 播放器标题区 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerTitleSection(
    title: String,
    artist: String,
    spec: AudioSpec?,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = if (compact) MaterialTheme.typography.headlineSmall
            else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 12.dp else 24.dp)
                .basicMarquee(velocity = 30.dp, initialDelayMillis = 1500)
        )
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        ArtistChipRow(
            artist = artist,
            onArtistClick = onArtistClick,
            compact = compact,
            modifier = Modifier.padding(horizontal = if (compact) 12.dp else 24.dp)
        )
        spec?.let { s ->
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
            AudioSpecBadges(s)
        }
    }
}

/** 播放器底部工具芯片 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerToolChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, primary.copy(0.4f)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 无歌词空状态 */
@Composable
fun NoLyricsEmptyState(onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Subtitles,
            null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f)
        )
        Spacer(Modifier.height(12.dp))
        Text("暂无歌词", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onImport) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("导入 LRC")
        }
    }
}

/** 播放队列 BottomSheet 顶栏 */
@Composable
fun PlaylistQueueHeader(
    songCount: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("播放队列", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "$songCount 首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.KeyboardArrowDown, "关闭")
        }
    }
}

/** 播放队列单行 */
@Composable
fun PlaylistQueueRow(
    index: Int,
    title: String,
    artist: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isPlaying) primary.copy(alpha = 0.1f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPlaying) {
                AnimatedEqIcon(isPlaying = true, tint = primary, modifier = Modifier.width(24.dp))
            } else {
                Text(
                    "$index",
                    modifier = Modifier.width(24.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isPlaying) primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (!artist.isNullOrBlank()) {
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
