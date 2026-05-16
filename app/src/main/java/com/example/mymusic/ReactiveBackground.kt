package com.example.mymusic

import android.media.audiofx.Visualizer
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val TAG = "ReactiveBackground"

enum class BackgroundMode(val label: String) {
    STATIC("静态渐变"),
    BREATHING("呼吸律动"),
    REACTIVE("音频响应")
}

// ── 对外主组件 ─────────────────────────────────────────────────────────────────
@Composable
fun ReactiveBackground(
    dominantColor: Color,
    mode: BackgroundMode,
    isPlaying: Boolean,
    audioSessionId: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface

    when (mode) {
        BackgroundMode.STATIC    -> StaticGradientBackground(dominantColor, surface, modifier, content)
        BackgroundMode.BREATHING -> BreathingGradientBackground(dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.REACTIVE  -> ReactiveGradientBackground(dominantColor, surface, isPlaying, audioSessionId, modifier, content)
    }
}

// ── 静态渐变 ──────────────────────────────────────────────────────────────────
@Composable
private fun StaticGradientBackground(
    dominantColor: Color,
    surface: Color,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val brush = Brush.verticalGradient(
        listOf(
            dominantColor.copy(alpha = 0.55f),
            dominantColor.copy(alpha = 0.15f),
            surface
        )
    )
    Box(modifier = modifier.background(brush)) { content() }
}

// ── 呼吸渐变：alpha 周期脉动，无需权限 ───────────────────────────────────────
@Composable
private fun BreathingGradientBackground(
    dominantColor: Color,
    surface: Color,
    isPlaying: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")

    // 1. 让动画永远在后台呼吸运转
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f, targetValue = 0.70f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breatheAlpha"
    )
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.70f, targetValue = 1.00f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breatheScale"
    )

    // 2. 利用 animateFloatAsState 负责把控状态切换的“渐变平滑度”！
    val currentAlpha by animateFloatAsState(
        targetValue = if (isPlaying) breatheAlpha else 0.40f, // 暂停时平滑过渡到 0.40
        animationSpec = tween(800), label = "alphaTransition"
    )
    val currentScale by animateFloatAsState(
        targetValue = if (isPlaying) breatheScale else 0.85f, // 暂停时平滑收缩到 0.85
        animationSpec = tween(800), label = "scaleTransition"
    )

    val brush = Brush.radialGradient(
        colors = listOf(
            dominantColor.copy(alpha = currentAlpha),
            dominantColor.copy(alpha = currentAlpha * 0.45f),
            surface.copy(alpha = 1f)
        ),
        radius = 1800f * currentScale
    )
    Box(modifier = modifier.background(brush)) { content() }
}

// ── 音频响应：Visualizer 驱动，需要 RECORD_AUDIO 权限 ─────────────────────────
@Composable
private fun ReactiveGradientBackground(
    dominantColor: Color,
    surface: Color,
    isPlaying: Boolean,
    audioSessionId: Int, // 不再需要这个破参数啦
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    var currentAmp by remember { mutableStateOf(0f) }

    // 直接从底层的自研处理器中抽取数据！
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                currentAmp = VisualizerData.amplitude
                delay(16L) // 维持 60fps 刷新率
            }
        } else {
            currentAmp = 0f
            VisualizerData.amplitude = 0f
        }
    }

    // 平滑过滤数值，防止爆音导致的画面闪烁
    val smoothedAmplitude by animateFloatAsState(
        targetValue = currentAmp,
        animationSpec = tween(80, easing = LinearEasing),
        label = "amplitude"
    )

    val topAlpha  = 0.30f + smoothedAmplitude * 0.55f
    val gradRadius = 1200f + smoothedAmplitude * 1200f

    val hsvBuf = FloatArray(3)
    android.graphics.Color.colorToHSV(dominantColor.toArgb(), hsvBuf)
    hsvBuf[0] = (hsvBuf[0] + 30f) % 360f
    val secondColor = Color(android.graphics.Color.HSVToColor(hsvBuf))

    val brush = Brush.radialGradient(
        colors = listOf(
            dominantColor.copy(alpha = topAlpha),
            secondColor.copy(alpha = topAlpha * 0.5f),
            surface.copy(alpha = 1f)
        ),
        radius = gradRadius
    )
    Box(modifier = modifier.background(brush)) { content() }
}