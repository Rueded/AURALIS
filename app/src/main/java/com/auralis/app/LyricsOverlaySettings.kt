package com.auralis.app

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 悬浮歌词外观设置：字号 / 宽度 / 背景不透明度 / 字体颜色 / 描边 / 锁定。
 * 所有值都写进 SharedPreferences（key 见 LyricsOverlayPrefs），系统悬浮窗每个刷新周期
 * 都会重新读一遍，所以这里拖滑块、点颜色，切到别的应用时看到的就是新样式。
 * 上面的预览条只是让你不用切出去也能看到大致效果。
 */
@Composable
fun LyricsOverlayStylePanel(prefs: SharedPreferences) {
    var textSize by remember { mutableFloatStateOf(prefs.getFloat(LyricsOverlayPrefs.KEY_TEXT_SIZE, LyricsOverlayPrefs.DEF_TEXT_SIZE)) }
    var widthPct by remember { mutableFloatStateOf(prefs.getInt(LyricsOverlayPrefs.KEY_WIDTH_PCT, LyricsOverlayPrefs.DEF_WIDTH_PCT).toFloat()) }
    var bgAlpha by remember { mutableFloatStateOf(prefs.getInt(LyricsOverlayPrefs.KEY_BG_ALPHA, LyricsOverlayPrefs.DEF_BG_ALPHA).toFloat()) }
    var stroke by remember { mutableFloatStateOf(prefs.getFloat(LyricsOverlayPrefs.KEY_STROKE, LyricsOverlayPrefs.DEF_STROKE)) }
    var colorInt by remember { mutableIntStateOf(prefs.getInt(LyricsOverlayPrefs.KEY_TEXT_COLOR, LyricsOverlayPrefs.DEF_TEXT_COLOR)) }
    var locked by remember { mutableStateOf(prefs.getBoolean(LyricsOverlayPrefs.KEY_LOCKED, false)) }

    // 长按悬浮窗也会改 KEY_LOCKED：这里监听同一个 SharedPreferences，开关才会跟着变，
    // 不然 remember 只在进入页面时读一次，之后悬浮窗里锁定/解锁了，这里还是旧状态
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == LyricsOverlayPrefs.KEY_LOCKED) locked = p.getBoolean(key, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val albumColor by PlayerStateHolder.dominantColor.collectAsState()
    val previewTextColor = if (colorInt == LyricsOverlayPrefs.COLOR_FOLLOW_ALBUM)
        (albumColor?.ensureContrastOn(Color(0xFF1A1A1A), 7f) ?: Color.White) else Color(colorInt)
    val lum = 0.299f * previewTextColor.red + 0.587f * previewTextColor.green + 0.114f * previewTextColor.blue
    val edgeColor = if (lum > 0.5f) Color.Black else Color.White

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // ── 预览条 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthPct / 100f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1A1A).copy(alpha = bgAlpha / 100f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                val sample = stringResource(R.string.lyrics_overlay_preview_text)
                val base = TextStyle(fontSize = textSize.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (stroke > 0f) {
                    Text(sample, style = base.copy(color = edgeColor, drawStyle = Stroke(width = stroke * 2f)))
                }
                Text(sample, style = base.copy(color = previewTextColor))
            }
        }

        Spacer(Modifier.height(8.dp))

        OverlaySliderRow(stringResource(R.string.lyrics_overlay_style_size), "${textSize.toInt()}sp", textSize, 12f..32f, 19) {
            textSize = it
        }
        OverlaySliderRow(stringResource(R.string.lyrics_overlay_style_width), "${widthPct.toInt()}%", widthPct, 50f..100f, 9) {
            widthPct = it
        }
        OverlaySliderRow(stringResource(R.string.lyrics_overlay_style_bg), "${bgAlpha.toInt()}%", bgAlpha, 0f..100f, 19) {
            bgAlpha = it
        }
        OverlaySliderRow(stringResource(R.string.lyrics_overlay_style_stroke), if (stroke <= 0f) stringResource(R.string.state_off) else "${"%.1f".format(stroke)}dp", stroke, 0f..4f, 7) {
            stroke = it
        }

        // 滑块拖完才落盘（拖动过程中只更新本地预览，不频繁写 SharedPreferences）
        // —— 用 LaunchedEffect 不如直接在值变化后延迟落盘简单：这里每次变化直接写，apply() 是异步的，开销很小
        SideEffect {
            prefs.edit()
                .putFloat(LyricsOverlayPrefs.KEY_TEXT_SIZE, textSize)
                .putInt(LyricsOverlayPrefs.KEY_WIDTH_PCT, widthPct.toInt())
                .putInt(LyricsOverlayPrefs.KEY_BG_ALPHA, bgAlpha.toInt())
                .putFloat(LyricsOverlayPrefs.KEY_STROKE, stroke)
                .putInt(LyricsOverlayPrefs.KEY_TEXT_COLOR, colorInt)
                .apply()
        }

        // ── 字体颜色 ──
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.lyrics_overlay_style_color), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        val presets = listOf(
            0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF80DEEA.toInt(),
            0xFFA5D6A7.toInt(), 0xFFF48FB1.toInt(), 0xFFFFAB91.toInt(), 0xFF212121.toInt()
        )
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 跟随专辑主色
            FilterChip(
                selected = colorInt == LyricsOverlayPrefs.COLOR_FOLLOW_ALBUM,
                onClick = { colorInt = LyricsOverlayPrefs.COLOR_FOLLOW_ALBUM },
                label = { Text(stringResource(R.string.lyrics_overlay_color_album)) }
            )
            presets.forEach { c ->
                val selected = colorInt == c
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .clickable { colorInt = c },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.size(32.dp)
                        ) {}
                    }
                }
            }
        }

        // ── 锁定 ──
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    locked = !locked
                    prefs.edit().putBoolean(LyricsOverlayPrefs.KEY_LOCKED, locked).apply()
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.lyrics_overlay_style_lock), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.lyrics_overlay_style_lock_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            // 颜色与设置页其他开关保持一致（SettingToggleRow：轨道用 secondary，滑块用 onPrimary）
            Switch(
                checked = locked,
                onCheckedChange = {
                    locked = it
                    prefs.edit().putBoolean(LyricsOverlayPrefs.KEY_LOCKED, it).apply()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.secondary,
                    checkedBorderColor = MaterialTheme.colorScheme.secondary
                )
            )
        }
    }
}

@Composable
private fun OverlaySliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.fillMaxWidth())
}
