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
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("MusicSyncPrefs", android.content.Context.MODE_PRIVATE) }

    var displayAmp by remember { mutableFloatStateOf(0f) }
    var movingAverage by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                val raw = VisualizerData.amplitude
                val sens = prefs.getFloat("reactive_sensitivity", 1.5f)

                // 自适应底噪阈值
                movingAverage += 0.05f * (raw - movingAverage)
                val threshold = movingAverage * 1.05f

                // 🚨 史诗级修复：将基础倍率下调至 4.5f！
                // 这样 0.5x 会像微风一样柔和，而 3.0x 会像狂风暴雨！
                val burst = if (raw > threshold && threshold > 0.001f) {
                    (raw - threshold) * 4.5f * sens
                } else 0f

                val target = burst.coerceIn(0f, 1f)

                // 爆发与回落算法
                if (target > displayAmp) {
                    displayAmp = target
                } else {
                    displayAmp *= 0.85f
                    if (displayAmp < 0.01f) displayAmp = 0f
                }
                delay(16L)
            }
        } else {
            displayAmp = 0f
            movingAverage = 0f
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "ambient")
    val phase1 by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(13000, easing = LinearEasing)), label = "p1")
    val phase2 by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(17000, easing = LinearEasing)), label = "p2")

    val hsvBuf = FloatArray(3)
    android.graphics.Color.colorToHSV(dominantColor.toArgb(), hsvBuf)
    val baseHue = hsvBuf[0]; val baseSat = hsvBuf[1]

    hsvBuf[0] = (baseHue - 25f + 360f) % 360f; hsvBuf[1] = (baseSat + 0.1f).coerceAtMost(1f)
    val colorLeft = Color(android.graphics.Color.HSVToColor(hsvBuf))

    hsvBuf[0] = (baseHue + 25f) % 360f; hsvBuf[1] = (baseSat + 0.1f).coerceAtMost(1f)
    val colorRight = Color(android.graphics.Color.HSVToColor(hsvBuf))

    Box(modifier = modifier.drawBehind {
        drawRect(dominantColor.copy(alpha = 0.12f))

        val rad1 = Math.toRadians(phase1.toDouble()); val rad2 = Math.toRadians(phase2.toDouble())

        val leftRadius = size.maxDimension * (0.40f + displayAmp * 0.15f)
        val alphaL = 0.20f + displayAmp * 0.35f
        val leftX = size.width * 0.25f + kotlin.math.sin(rad1).toFloat() * size.width * 0.15f
        val leftY = size.height * 0.3f + kotlin.math.cos(rad1 * 0.8).toFloat() * size.height * 0.1f
        drawCircle(
            brush = Brush.radialGradient(listOf(colorLeft.copy(alpha = alphaL), Color.Transparent), center = androidx.compose.ui.geometry.Offset(leftX, leftY), radius = leftRadius),
            radius = leftRadius, center = androidx.compose.ui.geometry.Offset(leftX, leftY)
        )

        val rightRadius = size.maxDimension * (0.45f + displayAmp * 0.20f)
        val alphaR = 0.20f + displayAmp * 0.35f
        val rightX = size.width * 0.75f + kotlin.math.cos(rad2).toFloat() * size.width * 0.15f
        val rightY = size.height * 0.6f + kotlin.math.sin(rad2 * 1.1).toFloat() * size.height * 0.1f
        drawCircle(
            brush = Brush.radialGradient(listOf(colorRight.copy(alpha = alphaR), Color.Transparent), center = androidx.compose.ui.geometry.Offset(rightX, rightY), radius = rightRadius),
            radius = rightRadius, center = androidx.compose.ui.geometry.Offset(rightX, rightY)
        )
    }) { content() }
}