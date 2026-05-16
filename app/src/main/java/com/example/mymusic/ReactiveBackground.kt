package com.example.mymusic

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform // 🚨 引入矩阵变换魔法
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

enum class BackgroundMode(val label: String) {
    STATIC("静态渐变"),
    BREATHING("呼吸律动"),
    FLUID("流体漫游"),
    HORIZON("深邃地平线"),
    CLASSIC_EQ("极细 EQ"),
    STARDUST("星云耀斑")
}

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
        BackgroundMode.STATIC     -> StaticGradientBackground(dominantColor, surface, modifier, content)
        BackgroundMode.BREATHING  -> BreathingGradientBackground(dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.FLUID      -> AppleFluidBackground(dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.HORIZON    -> PremiumHorizonBackground(dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.CLASSIC_EQ -> ClassicLineEqBackground(dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.STARDUST   -> StardustBackground(dominantColor, surface, isPlaying, modifier, content)
    }
}

@Composable
private fun rememberAudioAmplitude(isPlaying: Boolean): Float {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("MusicSyncPrefs", android.content.Context.MODE_PRIVATE) }
    var displayAmp by remember { mutableFloatStateOf(0f) }
    var movingAverage by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                val raw = VisualizerData.amplitude
                val sens = prefs.getFloat("reactive_sensitivity", 1.5f)
                movingAverage += 0.05f * (raw - movingAverage)
                val threshold = movingAverage * 1.10f
                val burst = if (raw > threshold && threshold > 0.001f) (raw - threshold) * 10.0f * sens else 0f
                val target = burst.coerceIn(0f, 1f)
                if (target > displayAmp) displayAmp = target else {
                    displayAmp *= 0.88f
                    if (displayAmp < 0.01f) displayAmp = 0f
                }
                delay(16L)
            }
        } else { displayAmp = 0f; movingAverage = 0f }
    }
    return displayAmp
}

// ==========================================
// ✨ 真·星云耀斑 (带独立自转引擎)
// ==========================================
private data class StarParticle(
    val xRatio: Float,
    val yOffset: Float,
    val speed: Float,
    val baseSize: Float,
    val reactSensitivity: Float,
    val hasFlare: Boolean,
    val isSuperStar: Boolean,
    val initialAngle: Float, // 🚨 新增：出生时的随机角度 (0-360)
    val rotationSpeed: Float // 🚨 新增：自转速度 (有正有负)
)

@Composable
private fun StardustBackground(dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val displayAmp = rememberAudioAmplitude(isPlaying)
    val infiniteTransition = rememberInfiniteTransition(label = "stardust")
    val phase by infiniteTransition.animateFloat(0f, 1000f, infiniteRepeatable(tween(30000, easing = LinearEasing)), label = "phase")

    val particles = remember {
        List(120) {
            StarParticle(
                xRatio = Math.random().toFloat(),
                yOffset = Math.random().toFloat(),
                speed = 0.05f + Math.random().toFloat() * 0.25f,
                baseSize = 0.5f + Math.random().toFloat() * 1.2f,
                reactSensitivity = 0.5f + Math.random().toFloat() * 2.5f,
                hasFlare = Math.random() > 0.40,
                isSuperStar = Math.random() > 0.85,
                // 💡 随机角度 (不再全是竖直的十字)，以及随机自转速度！
                initialAngle = (Math.random() * 360).toFloat(),
                rotationSpeed = ((Math.random() - 0.5f) * 12f).toFloat()
            )
        }
    }

    Box(modifier = modifier.drawBehind {
        drawRect(dominantColor.copy(alpha = 0.06f))

        particles.forEach { star ->
            val yPos = size.height - ((phase * star.speed * 8f + star.yOffset * size.height) % size.height)
            val xPos = (star.xRatio * size.width) + sin(yPos * 0.003f + star.yOffset * 100f) * 20f

            val react = displayAmp * star.reactSensitivity
            val pRadius = star.baseSize + (react * 0.8f)
            val baseAlpha = 0.15f + (react * 1.5f)
            val fadeOut = (yPos / size.height).coerceIn(0f, 1f)
            val finalAlpha = (baseAlpha * fadeOut).coerceIn(0f, 1f)

            val centerOffset = Offset(xPos, yPos)

            // 1. 散景微光
            if (react > 0.1f) {
                val glowRadius = pRadius * 3.5f
                drawCircle(
                    brush = Brush.radialGradient(listOf(dominantColor.copy(alpha = finalAlpha * 0.4f), Color.Transparent), center = centerOffset, radius = glowRadius),
                    radius = glowRadius, center = centerOffset
                )
            }

            // 2. 实心星核
            drawCircle(color = dominantColor.copy(alpha = finalAlpha), radius = pRadius, center = centerOffset)

            // 3. 🚨 会旋转的光学耀斑！
            if (star.hasFlare && react > 0.15f) {
                // 计算当前这颗星星的旋转角度 (初始角度 + 随着时间推移的自转)
                val currentRotation = star.initialAngle + phase * star.rotationSpeed

                // 利用 drawBehind 的 withTransform 魔法，将这颗星星的光芒倾斜！
                withTransform({
                    rotate(degrees = currentRotation, pivot = centerOffset)
                }) {
                    val flareLen = star.baseSize * 15f * react
                    val flareStroke = 0.8f + (react * 0.5f)

                    // 画出来的虽然还是十字，但是整个画布被旋转了，所以看起来就是倾斜的星芒！
                    drawLine(color = dominantColor.copy(alpha = finalAlpha * 0.8f), start = Offset(xPos - flareLen, yPos), end = Offset(xPos + flareLen, yPos), strokeWidth = flareStroke)
                    drawLine(color = dominantColor.copy(alpha = finalAlpha * 0.8f), start = Offset(xPos, yPos - flareLen), end = Offset(xPos, yPos + flareLen), strokeWidth = flareStroke)

                    if (star.isSuperStar) {
                        val subFlare = flareLen * 0.4f
                        val subStroke = 0.5f
                        drawLine(color = dominantColor.copy(alpha = finalAlpha * 0.5f), start = Offset(xPos - subFlare, yPos - subFlare), end = Offset(xPos + subFlare, yPos + subFlare), strokeWidth = subStroke)
                        drawLine(color = dominantColor.copy(alpha = finalAlpha * 0.5f), start = Offset(xPos - subFlare, yPos + subFlare), end = Offset(xPos + subFlare, yPos - subFlare), strokeWidth = subStroke)
                    }
                }
            }
        }
    }) { content() }
}


// ==========================================
// 📊 顶级极细 EQ (原有完美效果保持不变)
// ==========================================
@Composable
private fun ClassicLineEqBackground(dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val displayAmp = rememberAudioAmplitude(isPlaying)
    val infiniteTransition = rememberInfiniteTransition(label = "eq")
    val phase by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "phase")

    Box(modifier = modifier.drawBehind {
        drawRect(dominantColor.copy(alpha = 0.05f))
        val spacing = 16f
        val barCount = (size.width / spacing).toInt().coerceAtLeast(10)
        val startX = (size.width - (barCount * spacing)) / 2f + spacing / 2f
        val maxBarHeight = size.height * 0.22f
        val radPhase = Math.toRadians(phase.toDouble()).toFloat()

        for (i in 0 until barCount) {
            val nx = i / barCount.toFloat()
            val x = startX + i * spacing
            val bellCurve = sin(nx * PI).toFloat()
            val freq1 = sin(nx * 40f + radPhase) * 0.5f + 0.5f
            val freq2 = sin(nx * 15f - radPhase * 1.5f) * 0.5f + 0.5f
            val randomRipple = freq1 * 0.6f + freq2 * 0.4f
            val barHeight = 4f + (maxBarHeight * displayAmp * bellCurve * randomRipple * 1.5f)
            val baseAlpha = 0.2f + (displayAmp * 0.8f * bellCurve)

            drawLine(color = dominantColor.copy(alpha = (baseAlpha * 0.25f).coerceIn(0f, 1f)), start = Offset(x, size.height), end = Offset(x, size.height - barHeight), strokeWidth = 8f, cap = StrokeCap.Round)
            drawLine(color = dominantColor.copy(alpha = baseAlpha.coerceIn(0f, 1f)), start = Offset(x, size.height), end = Offset(x, size.height - barHeight), strokeWidth = 1.5f, cap = StrokeCap.Round)
        }
    }) { content() }
}

// ==========================================
// 🌌 Premium Horizon V2 (原有效果保持不变)
// ==========================================
@Composable
private fun PremiumHorizonBackground(dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val displayAmp = rememberAudioAmplitude(isPlaying)
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "phase")

    Box(modifier = modifier.drawBehind {
        drawRect(dominantColor.copy(alpha = 0.08f))
        val centerY = size.height * 0.68f
        val glowHeight = size.height * 0.15f + (displayAmp * size.height * 0.35f)
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, dominantColor.copy(alpha = 0.1f + displayAmp * 0.2f)), startY = centerY - glowHeight, endY = centerY), topLeft = Offset(0f, centerY - glowHeight), size = Size(size.width, glowHeight))
        drawRect(brush = Brush.verticalGradient(listOf(dominantColor.copy(alpha = 0.1f + displayAmp * 0.2f), Color.Transparent), startY = centerY, endY = centerY + glowHeight), topLeft = Offset(0f, centerY), size = Size(size.width, glowHeight))

        val wavePath = Path()
        wavePath.moveTo(0f, centerY)
        val radPhase = Math.toRadians(phase.toDouble()).toFloat()
        for (x in 0..size.width.toInt() step 15) {
            val nx = x / size.width
            val mask = 1f - (nx * 2f - 1f).pow(2)
            val yOffset = sin(nx * 15f + radPhase) * (20f * displayAmp) * mask
            wavePath.lineTo(x.toFloat(), centerY + yOffset)
        }
        drawPath(path = wavePath, color = dominantColor.copy(alpha = 0.3f + displayAmp * 0.4f), style = Stroke(width = 2f))
        drawLine(color = dominantColor.copy(alpha = 0.5f + displayAmp * 0.5f), start = Offset(0f, centerY), end = Offset(size.width, centerY), strokeWidth = 1f + displayAmp * 2f)
    }) { content() }
}

// ==========================================
// 🍎 Apple Fluid (原有效果保持不变)
// ==========================================
@Composable
private fun AppleFluidBackground(dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val displayAmp = rememberAudioAmplitude(isPlaying)
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
        val leftRadius = size.maxDimension * (0.40f + displayAmp * 0.18f)
        val alphaL = 0.20f + displayAmp * 0.35f
        val leftX = size.width * 0.25f + sin(rad1).toFloat() * size.width * 0.15f
        val leftY = size.height * 0.3f + kotlin.math.cos(rad1 * 0.8).toFloat() * size.height * 0.1f
        drawCircle(Brush.radialGradient(listOf(colorLeft.copy(alpha = alphaL), Color.Transparent), center = Offset(leftX, leftY), radius = leftRadius), leftRadius, Offset(leftX, leftY))

        val rightRadius = size.maxDimension * (0.45f + displayAmp * 0.22f)
        val alphaR = 0.20f + displayAmp * 0.35f
        val rightX = size.width * 0.75f + kotlin.math.cos(rad2).toFloat() * size.width * 0.15f
        val rightY = size.height * 0.6f + sin(rad2 * 1.1).toFloat() * size.height * 0.1f
        drawCircle(Brush.radialGradient(listOf(colorRight.copy(alpha = alphaR), Color.Transparent), center = Offset(rightX, rightY), radius = rightRadius), rightRadius, Offset(rightX, rightY))
    }) { content() }
}

@Composable
private fun StaticGradientBackground(dominantColor: Color, surface: Color, modifier: Modifier, content: @Composable () -> Unit) {
    val brush = Brush.verticalGradient(listOf(dominantColor.copy(alpha = 0.55f), dominantColor.copy(alpha = 0.15f), surface))
    Box(modifier = modifier.background(brush)) { content() }
}

@Composable
private fun BreathingGradientBackground(dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val targetBreatheAlpha = infiniteTransition.animateFloat(0.20f, 0.45f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha")
    val targetOffsetX = infiniteTransition.animateFloat(0.2f, 0.8f, infiniteRepeatable(tween(8500, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    val targetOffsetY = infiniteTransition.animateFloat(0.1f, 0.6f, infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Reverse), label = "y")

    val playFraction by animateFloatAsState(if (isPlaying) 1f else 0f, tween(1500, easing = FastOutSlowInEasing), label = "playFraction")
    val currentAlpha = 0.20f + (targetBreatheAlpha.value - 0.20f) * playFraction
    val currentOffsetX = 0.5f + (targetOffsetX.value - 0.5f) * playFraction
    val currentOffsetY = 0.5f + (targetOffsetY.value - 0.5f) * playFraction

    Box(modifier = modifier.drawBehind {
        val centerOffset = Offset(size.width * currentOffsetX, size.height * currentOffsetY)
        drawRect(Brush.radialGradient(listOf(dominantColor.copy(alpha = currentAlpha), dominantColor.copy(alpha = currentAlpha * 0.45f), surface.copy(alpha = 1f)), center = centerOffset, radius = size.maxDimension * 0.85f))
    }) { content() }
}