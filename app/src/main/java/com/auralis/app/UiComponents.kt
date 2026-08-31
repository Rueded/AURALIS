package com.auralis.app

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 首页顶部氛围光晕，随封面多维色调深度演化 */
@Composable
fun HomeAmbientBackground(
    palette: AlbumPalette?,
    modifier: Modifier = Modifier
) {
    val primary = palette?.primary ?: MaterialTheme.colorScheme.primary
    val secondary = palette?.secondary ?: MaterialTheme.colorScheme.secondary
    val accent = palette?.accent ?: MaterialTheme.colorScheme.tertiary

    val animatedPrimary by animateColorAsState(primary, tween(1000, easing = LinearOutSlowInEasing), label = "hPrimary")
    val animatedSecondary by animateColorAsState(secondary, tween(1000, easing = LinearOutSlowInEasing), label = "hSecondary")
    val animatedAccent by animateColorAsState(accent, tween(1000, easing = LinearOutSlowInEasing), label = "hAccent")

    val surface = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surface)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        animatedPrimary.copy(alpha = 0.25f),
                        animatedSecondary.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = Offset(0.2f, -0.1f),
                    radius = 1500f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        animatedAccent.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = Offset(0.9f, 0.1f),
                    radius = 1000f
                )
            )
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        surface.copy(alpha = 0.5f),
                        surface
                    ),
                    startY = 0f,
                    endY = 1200f
                )
            )
    )
}

/** 胶囊式 Tab，替代默认 ScrollableTabRow */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tabs.size) { index ->
            val selected = selectedIndex == index
            val bg by animateColorAsState(
                if (selected) primary.copy(alpha = 0.18f) else Color.Transparent,
                tween(250),
                label = "tabBg"
            )
            val textColor by animateColorAsState(
                if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(250),
                label = "tabText"
            )

            Surface(
                onClick = { onTabSelected(index) },
                shape = RoundedCornerShape(20.dp),
                color = bg,
                border = if (selected) null else BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                ),
                modifier = Modifier.height(36.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tabs[index],
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/** 玻璃态底部迷你播放器 */
@Composable
fun PremiumMiniPlayerBar(
    title: String,
    artist: String,
    isPlaying: Boolean,
    coverBitmap: ImageBitmap?,
    audioPath: String,
    progress: Float = 0f,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onBarClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val animatedProgress by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        tween(350),
        label = "miniProgress"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(16.dp, RoundedCornerShape(22.dp), spotColor = primary.copy(alpha = 0.25f))
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onBarClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 6.dp
    ) {
        Column {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = stringResource(R.string.content_desc_cover),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AdvancedFluidCover(
                            seedString = audioPath.ifEmpty { title },
                            modifier = Modifier.fillMaxSize(),
                            iconSize = 22.dp
                        )
                    }
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center
                        ) {
                            PlayingWaveform(isPlaying = true)
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (isPlaying) Modifier.basicMarquee() else Modifier
                    )
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                AnimatedEqIcon(
                    isPlaying = isPlaying,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    tint = primary
                )

                FilledIconButton(
                    onClick = onPreviousClick,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.content_desc_previous), modifier = Modifier.size(22.dp))
                }

                FilledIconButton(
                    onClick = onPlayPauseClick,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = primary)
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(R.string.content_desc_play_pause),
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                FilledIconButton(
                    onClick = onNextClick,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.content_desc_next), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** 统一空状态 */
@Composable
fun EmptyStateView(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = primary.copy(alpha = 0.12f),
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, null, tint = primary, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** 排行榜序号徽章（前三名高亮） */
@Composable
fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    val (bg, fg) = when (rank) {
        0 -> Color(0xFFFFD54F) to Color(0xFF5D4037)
        1 -> Color(0xFFE0E0E0) to Color(0xFF424242)
        2 -> Color(0xFFFFCC80) to Color(0xFF6D4C41)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.size(28.dp),
        shape = RoundedCornerShape(8.dp),
        color = bg.copy(alpha = if (rank < 3) 1f else 0.7f)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                "${rank + 1}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = fg
            )
        }
    }
}

/** 常听榜单行 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopSongRow(
    rank: Int,
    song: Song,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val cardBg by animateColorAsState(
        if (isCurrentSong) primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tween(300),
        label = "topSongBg"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        tonalElevation = if (isCurrentSong) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RankBadge(rank)
            Spacer(Modifier.width(12.dp))
            SongCoverThumb(
                song = song,
                isCurrentSong = isCurrentSong,
                isPlaying = isPlaying,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp)),
                iconSize = 18.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrentSong) primary else Color.Unspecified
                )
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val dateStr = if (song.lastPlayed > 0) sdf.format(java.util.Date(song.lastPlayed)) else stringResource(R.string.never_played_short)
                Text(
                    "${song.artist} · $dateStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = primary.copy(alpha = 0.15f)
            ) {
                Text(
                    stringResource(R.string.play_count_times, song.playCount),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** 专辑列表卡片 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumRow(
    albumName: String,
    artistName: String,
    songCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(primary.copy(0.35f), MaterialTheme.colorScheme.tertiary.copy(0.25f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Album,
                    null,
                    tint = primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(albumName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.song_count_with_artist, artistName, songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 歌手列表卡片 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistRow(
    artistName: String,
    songCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(primary.copy(0.35f), MaterialTheme.colorScheme.tertiary.copy(0.25f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    artistName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(artistName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.song_count_songs_suffix, songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 歌单列表卡片 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = primary.copy(alpha = 0.15f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Filled.QueueMusic, null, tint = primary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.outline)
                }
            } else {
                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 听歌统计横幅 */
@Composable
fun ListeningStatsBanner(
    totalPlays: Int,
    uniqueSongs: Int,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = primary.copy(alpha = 0.18f), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Filled.BarChart, null, tint = primary, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.listening_stats_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.listening_stats_summary, totalPlays, uniqueSongs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 睡眠定时器提示条 */
@Composable
fun SleepTimerBanner(
    secondsRemaining: Long,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.NightsStay, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.pause_in_label, "${secondsRemaining / 60}:${String.format("%02d", secondsRemaining % 60)}"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 全屏播放器发光主播放键 */
@Composable
fun GlowPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 72.dp,
    iconSize: androidx.compose.ui.unit.Dp = 36.dp
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "playScale"
    )

    Box(
        modifier = Modifier
            .size(size + 8.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size + 12.dp)
                .background(primary.copy(alpha = 0.2f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(size)
                .shadow(16.dp, CircleShape, spotColor = primary.copy(alpha = 0.45f))
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(primary.copy(0.85f), primary)))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(R.string.content_desc_play_pause),
                modifier = Modifier.size(iconSize),
                tint = onPrimary
            )
        }
    }
}

/** 设置页顶部栏 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHeroTopBar(onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                }
                Spacer(Modifier.weight(1f))
            }
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}