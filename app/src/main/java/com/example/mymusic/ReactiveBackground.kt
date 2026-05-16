package com.example.mymusic

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class BackgroundMode(val label: String) {
    STATIC("静态渐变"),
    BREATHING("呼吸律动"),
    REACTIVE("音频响应")
}

@Composable
fun ReactiveBackground(
    dominantColor: Color,
    mode: BackgroundMode,
    isPlaying: Boolean,
    audioSessionId: Int, // 废弃参数保留防报错
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface
    when (mode) {
        BackgroundMode.STATIC    -> StaticGradientBackground(dominantColor, surface, modifier, content)
        BackgroundMode.BREATHING -> BreathingGradientBackground(dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.REACTIVE  -> ReactiveGradientBackground(dominantColor, surface, isPlaying, modifier, content)
    }
}

@Composable
private fun StaticGradientBackground(dominantColor: Color, surface: Color, modifier: Modifier, content: @Composable () -> Unit) {
    val brush = Brush.verticalGradient(listOf(dominantColor.copy(alpha = 0.55f), dominantColor.copy(alpha = 0.15f), surface))
    Box(modifier = modifier.background(brush)) { content() }
}

@Composable
private fun BreathingGradientBackground(
    dominantColor: Color,
    surface: Color,
    isPlaying: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")

    // 让后台产生完美的循环动画数值
    val targetBreatheAlpha = infiniteTransition.animateFloat(
        initialValue = 0.20f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha"
    )
    val targetOffsetX = infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(8500, easing = LinearEasing), RepeatMode.Reverse), label = "x"
    )
    val targetOffsetY = infiniteTransition.animateFloat(
        initialValue = 0.1f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Reverse), label = "y"
    )

    // 👇 【神级修复】：用一个统一的进度条来控制播放/暂停的过渡，彻底解决画面冻结！
    val playFraction by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(1500, easing = FastOutSlowInEasing),
        label = "playFraction"
    )

    // 通过数学插值计算最终状态
    val currentAlpha = 0.20f + (targetBreatheAlpha.value - 0.20f) * playFraction
    val currentOffsetX = 0.5f + (targetOffsetX.value - 0.5f) * playFraction
    val currentOffsetY = 0.5f + (targetOffsetY.value - 0.5f) * playFraction

    Box(modifier = modifier.drawBehind {
        val centerOffset = androidx.compose.ui.geometry.Offset(size.width * currentOffsetX, size.height * currentOffsetY)
        val brush = Brush.radialGradient(
            colors = listOf(
                dominantColor.copy(alpha = currentAlpha),
                dominantColor.copy(alpha = currentAlpha * 0.45f),
                surface.copy(alpha = 1f)
            ),
            center = centerOffset,
            radius = size.maxDimension * 0.85f
        )
        drawRect(brush = brush)
    }) { content() }
}

@Composable
private fun ReactiveGradientBackground(
    dominantColor: Color,
    surface: Color,
    isPlaying: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    var currentAmp by remember { mutableStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                currentAmp = VisualizerData.amplitude
                delay(16L) // 60fps 刷新率
            }
        } else {
            currentAmp = 0f
            VisualizerData.amplitude = 0f
        }
    }

    val smoothedAmplitude by animateFloatAsState(
        targetValue = currentAmp,
        animationSpec = tween(80, easing = LinearEasing),
        label = "amplitude"
    )

    val topAlpha = 0.30f + smoothedAmplitude * 0.55f
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