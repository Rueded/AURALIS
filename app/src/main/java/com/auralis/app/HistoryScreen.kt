package com.auralis.app

import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

// ─────────────────────────────────────────────────────────────
// 数据持有类
// ─────────────────────────────────────────────────────────────

private data class HistoryPageState(
    val availableYears: List<String> = emptyList(),
    val selectedYear: String = "",
    val selectedMonth: Int = 0,          // 0 = 年视图，1-12 = 具体月份
    val monthlyData: List<MonthCount> = emptyList(),
    val dailyData: List<DayCount> = emptyList(),
    val topSongs: List<Song> = emptyList(),
    val totalPlays: Int = 0,
    val totalListenedMs: Long = 0L,
    val isLoading: Boolean = true,
    val sortByRecent: Boolean = false    // false = 按次数（原本行为），true = 按最近播放时间
)

// 模块级缓存：跟 HistoryScreen() 这个 Composable 的生命周期脱钩，只要进程没死就一直在。
// 用来解决"每次点进历史足迹都要重新转圈加载"的问题——专辑、最近常听这些 tab 的数据
// 本来就是从外层常驻内存的歌曲列表派生出来的，切页不会重新查库；历史足迹这边是自己
// 单独查 Room 数据库统计年度/月度数据，状态只存在 Composable 自己身上的话，Pager 把
// 这一页挪出可见范围再挪回来时，Composable 会被整个销毁重建，state 也跟着清零变回
// isLoading=true，于是每次点进来都要先转一圈圈才出内容。
// 有了这份缓存之后：非第一次进这个 tab 时，直接拿上次的数据垫底展示，不转圈；
// 同时在背后悄悄跑一次刷新，把这段时间里新产生的播放记录补上，用户全程无感。
private var cachedHistoryState: HistoryPageState? = null

// ─────────────────────────────────────────────────────────────
// 入口 Composable
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(cachedHistoryState ?: HistoryPageState()) }
    // 记录"这次进入这个 tab 后，年/月/排序变化触发的加载"是不是头一回——
    // 头一回如果有缓存垫底，就悄悄刷新、不弹转圈；之后用户自己手动切年份/月份，
    // 那是新请求了从没缓存过的数据，还是应该正常转圈提示一下
    var firstLoadHandled by remember { mutableStateOf(false) }

    // 初始加载年份列表：只有这个进程里头一次打开这个 tab（没有任何缓存）才需要查，
    // 有缓存的话年份列表、当前选中的年月都已经是现成的，不用重新查一遍
    LaunchedEffect(Unit) {
        if (cachedHistoryState == null) {
            state = loadYears(context, state)
        }
    }

    // 年份、月份或排序方式变化时（重新）加载数据
    LaunchedEffect(state.selectedYear, state.selectedMonth, state.sortByRecent) {
        if (state.selectedYear.isNotEmpty()) {
            val silentRefresh = !firstLoadHandled && cachedHistoryState != null
            firstLoadHandled = true
            if (!silentRefresh) state = state.copy(isLoading = true)
            state = loadPeriodData(context, state).copy(isLoading = false)
            cachedHistoryState = state
        }
    }

    // 👇 不再自带 Scaffold/TopAppBar/背景色，和"专辑""最近常听"等 tab 保持同一种写法，
    // 直接透出外层共享的动态氛围背景，不再是一块单独盖上去的纯色板
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else if (state.availableYears.isEmpty()) {
            EmptyHistoryPlaceholder()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 年份 + 月份选择器
                item {
                    YearMonthSelector(
                        years = state.availableYears,
                        selectedYear = state.selectedYear,
                        selectedMonth = state.selectedMonth,
                        onYearSelected = { year ->
                            state = state.copy(selectedYear = year, selectedMonth = 0)
                        },
                        onMonthSelected = { month ->
                            state = state.copy(selectedMonth = month)
                        }
                    )
                }

                // 概览卡片
                item {
                    SummaryCard(
                        totalPlays = state.totalPlays,
                        totalListenedMs = state.totalListenedMs,
                        selectedMonth = state.selectedMonth
                    )
                }

                // 柱状图（月视图 = 每日，年视图 = 每月）
                item {
                    if (state.selectedMonth == 0) {
                        MonthlyBarChart(
                            data = state.monthlyData,
                            selectedYear = state.selectedYear
                        )
                    } else {
                        DailyBarChart(
                            data = state.dailyData,
                            year = state.selectedYear,
                            month = state.selectedMonth
                        )
                    }
                }

                // Top 歌曲
                if (state.topSongs.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (state.selectedMonth == 0) stringResource(R.string.this_year_favorite) else stringResource(R.string.this_month_favorite),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = !state.sortByRecent,
                                    onClick = { state = state.copy(sortByRecent = false) },
                                    label = { Text(stringResource(R.string.sort_by_count), fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = state.sortByRecent,
                                    onClick = { state = state.copy(sortByRecent = true) },
                                    label = { Text(stringResource(R.string.sort_by_recent), fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                    items(state.topSongs) { song ->
                        TopSongRow(
                            song = song,
                            rank = state.topSongs.indexOf(song) + 1,
                            context = context
                        )
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 数据加载
// ─────────────────────────────────────────────────────────────

private suspend fun loadYears(context: Context, state: HistoryPageState): HistoryPageState {
    return withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).songDao()
        val years = dao.getDistinctYears()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR).toString()
        val selectedYear = if (years.contains(currentYear)) currentYear else years.firstOrNull() ?: ""
        state.copy(
            availableYears = years,
            selectedYear = selectedYear,
            isLoading = selectedYear.isEmpty()
        )
    }
}

private suspend fun loadPeriodData(context: Context, state: HistoryPageState): HistoryPageState {
    return withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).songDao()
        val year = state.selectedYear.toIntOrNull() ?: return@withContext state.copy(isLoading = false)

        if (state.selectedMonth == 0) {
            // 年视图
            val yearStart = Calendar.getInstance().apply {
                set(year, Calendar.JANUARY, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val yearEnd = Calendar.getInstance().apply {
                set(year + 1, Calendar.JANUARY, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val monthly = dao.getMonthlyPlayCounts(yearStart, yearEnd)
            val top = if (state.sortByRecent) dao.getRecentSongsInPeriod(yearStart, yearEnd, 10)
            else dao.getTopSongsInPeriod(yearStart, yearEnd, 10)
            val total = dao.getTotalPlaysInYear(yearStart, yearEnd)
            val totalMs = dao.getTotalListenedMs() ?: 0L

            state.copy(
                monthlyData = monthly,
                dailyData = emptyList(),
                topSongs = top,
                totalPlays = total,
                totalListenedMs = totalMs,
                isLoading = false
            )
        } else {
            // 月视图
            val monthStart = Calendar.getInstance().apply {
                set(year, state.selectedMonth - 1, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val monthEnd = Calendar.getInstance().apply {
                set(year, state.selectedMonth, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val daily = dao.getDailyPlayCounts(monthStart, monthEnd)
            val top = if (state.sortByRecent) dao.getRecentSongsInPeriod(monthStart, monthEnd, 10)
            else dao.getTopSongsInPeriod(monthStart, monthEnd, 10)
            val total = dao.getPlayCountInPeriod(monthStart, monthEnd)

            state.copy(
                dailyData = daily,
                monthlyData = emptyList(),
                topSongs = top,
                totalPlays = total,
                isLoading = false
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 年份 + 月份选择器
// ─────────────────────────────────────────────────────────────

@Composable
private fun YearMonthSelector(
    years: List<String>,
    selectedYear: String,
    selectedMonth: Int,
    onYearSelected: (String) -> Unit,
    onMonthSelected: (Int) -> Unit
) {
    var showYearDropdown by remember { mutableStateOf(false) }
    val monthLabels = listOf(
        stringResource(R.string.month_all_year), stringResource(R.string.month_1), stringResource(R.string.month_2),
        stringResource(R.string.month_3), stringResource(R.string.month_4), stringResource(R.string.month_5),
        stringResource(R.string.month_6), stringResource(R.string.month_7), stringResource(R.string.month_8),
        stringResource(R.string.month_9), stringResource(R.string.month_10), stringResource(R.string.month_11),
        stringResource(R.string.month_12)
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 年份选择
        Box {
            AssistChip(
                onClick = { showYearDropdown = true },
                label = { Text(selectedYear, fontWeight = FontWeight.Bold) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                shape = RoundedCornerShape(12.dp)
            )
            DropdownMenu(expanded = showYearDropdown, onDismissRequest = { showYearDropdown = false }) {
                years.forEach { year ->
                    DropdownMenuItem(
                        text = { Text(year) },
                        onClick = { onYearSelected(year); showYearDropdown = false }
                    )
                }
            }
        }

        // 月份横向滚动
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(13) { index ->
                val isSelected = index == selectedMonth
                FilterChip(
                    selected = isSelected,
                    onClick = { onMonthSelected(index) },
                    label = { Text(monthLabels[index], fontSize = 13.sp) },
                    shape = RoundedCornerShape(20.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 概览卡片
// ─────────────────────────────────────────────────────────────

@Composable
private fun SummaryCard(totalPlays: Int, totalListenedMs: Long, selectedMonth: Int) {
    val hours = totalListenedMs / 3_600_000
    val minutes = (totalListenedMs % 3_600_000) / 60_000

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryStatItem(
                label = if (selectedMonth == 0) stringResource(R.string.plays_this_year) else stringResource(R.string.plays_this_month),
                value = stringResource(R.string.plays_count_label, totalPlays)
            )
            Divider(
                modifier = Modifier.height(40.dp).width(1.dp).align(Alignment.CenterVertically),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )
            SummaryStatItem(
                label = stringResource(R.string.total_listening_label),
                value = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
            )
        }
    }
}

@Composable
private fun SummaryStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 月度柱状图（年视图，12 个月）
// ─────────────────────────────────────────────────────────────

@Composable
private fun MonthlyBarChart(data: List<MonthCount>, selectedYear: String) {
    val monthLabels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12")
    // 将查询结果转为 month(Int) -> count 的 Map
    val countMap = data.associate { it.month.toIntOrNull() to it.count }
    val maxCount = (data.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.year_play_distribution, selectedYear),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                for (m in 1..12) {
                    val count = countMap[m] ?: 0
                    val fraction = count.toFloat() / maxCount
                    BarItem(
                        fraction = fraction,
                        label = monthLabels[m - 1],
                        count = count,
                        isActive = count > 0
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 每日柱状图（月视图）
// ─────────────────────────────────────────────────────────────

@Composable
private fun DailyBarChart(data: List<DayCount>, year: String, month: Int) {
    val countMap = data.associate { it.day.toIntOrNull() to it.count }
    val daysInMonth = Calendar.getInstance().apply {
        set(year.toIntOrNull() ?: 2024, month - 1, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)
    val maxCount = (data.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.month_daily_plays, year, month),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            // 每行显示 10 天，共最多 4 行
            for (rowStart in 1..daysInMonth step 10) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    for (d in rowStart until minOf(rowStart + 10, daysInMonth + 1)) {
                        val count = countMap[d] ?: 0
                        val fraction = count.toFloat() / maxCount
                        Box(modifier = Modifier.weight(1f)) {
                            BarItem(
                                fraction = fraction,
                                label = d.toString(),
                                count = count,
                                isActive = count > 0,
                                compact = true
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 单根柱子
// ─────────────────────────────────────────────────────────────

@Composable
private fun BarItem(
    fraction: Float,
    label: String,
    count: Int,
    isActive: Boolean,
    compact: Boolean = false
) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(600),
        label = "bar_anim"
    )
    val barColor = if (isActive)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier.fillMaxHeight()
    ) {
        if (isActive && !compact) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .width(if (compact) 8.dp else 14.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedFraction.coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(barColor)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = if (compact) 8.sp else 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Top 歌曲行
// ─────────────────────────────────────────────────────────────

@Composable
private fun TopSongRow(song: Song, rank: Int, context: Context) {
    var bitmap by remember(song.data) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(song.data) {
        withContext(Dispatchers.IO) {
            val bmp = CoverArtCache.loadBitmapFromDisk(context, song.data)
                ?: CoverArtCache.loadAlbumArtFromMediaStore(context, song.data)
            if (bmp != null) bitmap = bmp.asImageBitmap()
        }
    }

    val rankColor = when (rank) {
        1 -> Color(0xFFFFD700)
        2 -> Color(0xFFC0C0C0)
        3 -> Color(0xFFCD7F32)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 排名
        Text(
            text = rank.toString(),
            modifier = Modifier.width(28.dp),
            fontWeight = if (rank <= 3) FontWeight.Bold else FontWeight.Normal,
            color = rankColor,
            fontSize = if (rank <= 3) 16.sp else 14.sp
        )

        // 封面
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = stringResource(R.string.play_count_suffix, song.playCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 空状态
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyHistoryPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎵", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.no_listening_history),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.play_some_songs_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}