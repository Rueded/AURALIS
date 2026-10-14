package com.auralis.app

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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

enum class BackgroundMode(val label: String) {
    STATIC("静态渐变"),
    BREATHING("呼吸律动"),
    FLUID("流体漫游"),
    HORIZON("深邃地平线"),
    CLASSIC_EQ("极细 EQ (Pro)"),
    STARDUST("星云耀斑 (Flare)"),
    AURORA("极光绸缎 (Veil)")
}

@Composable
fun ReactiveBackground(
    dominantColor: Color,
    palette: AlbumPalette?,
    mode: BackgroundMode,
    isPlaying: Boolean,
    audioSessionId: Int,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface
    when (mode) {
        BackgroundMode.STATIC     -> StaticGradientBackground(palette, dominantColor, surface, modifier, content)
        BackgroundMode.BREATHING  -> BreathingGradientBackground(palette, dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.FLUID      -> AppleFluidBackground(palette, dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.HORIZON    -> PremiumHorizonBackground(palette, dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.CLASSIC_EQ -> ClassicLineEqBackground(palette, dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.STARDUST   -> StardustBackground(palette, dominantColor, surface, isPlaying, modifier, content)
        BackgroundMode.AURORA     -> AuroraVeilBackground(palette, dominantColor, surface, isPlaying, modifier, content)
    }
}

/**
 * smooth：连续包络，跟着音量本身连续起伏（攻击稍快、回落更慢），没有"从 0 忽然炸到顶"的观感。
 *         给流体漫游、极光绸缎这类"面积型"效果用——面积越大，瞬时尖峰造成的视觉冲击也越大，
 *         这类效果必须用连续量驱动才不会看起来像打雷闪电。
 * burst： 瞬态峰值，只在突然显著变响时才冒出来，衰减很快。给星云耀斑的射线闪光、
 *         地平线的强调波纹、EQ 柱子顶端的小亮点这类"小面积点缀"用，这类效果本来就该是
 *         偶发的强调，不是持续驱动。
 * 以前只有一个 displayAmp（其实就是现在的 burst），所有效果不分青红皂白全用这一个瞬态量，
 * 这就是"流体漫游""极细 EQ"看起来发飘/发假的根本原因——不是数据不好，是拿错了数据。
 */
private data class AudioLevels(val smooth: Float, val burst: Float)

@Composable
private fun rememberAudioLevels(isPlaying: Boolean): AudioLevels {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("MusicSyncPrefs", android.content.Context.MODE_PRIVATE) }
    var smoothLevel by remember { mutableFloatStateOf(0f) }
    var burstLevel by remember { mutableFloatStateOf(0f) }
    var movingAverage by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                val bassEnergy   = VisualizerData.fftBands.slice(0..5).average().toFloat() * 6f
                val midEnergy    = VisualizerData.fftBands.slice(6..45).average().toFloat() * 4f
                val trebleEnergy = VisualizerData.fftBands.slice(46..110).average().toFloat() * 3f

                val combinedAmp = (bassEnergy * 0.6f + midEnergy * 0.3f + trebleEnergy * 0.1f)
                    .coerceIn(0f, 1f)

                val raw = combinedAmp
                val sens = prefs.getFloat("reactive_sensitivity", 1.5f)

                smoothLevel += (if (raw > smoothLevel) 0.22f else 0.05f) * (raw - smoothLevel)

                movingAverage += 0.05f * (raw - movingAverage)
                val threshold = movingAverage * 1.10f
                val burst = if (raw > threshold && threshold > 0.001f) (raw - threshold) * 10.0f * sens else 0f
                val target = burst.coerceIn(0f, 1f)
                if (target > burstLevel) burstLevel = target else {
                    burstLevel *= 0.88f
                    if (burstLevel < 0.01f) burstLevel = 0f
                }
                delay(16L)
            }
        } else { smoothLevel = 0f; burstLevel = 0f; movingAverage = 0f }
    }
    return AudioLevels(smooth = smoothLevel, burst = burstLevel)
}

// ==========================================
// 真·星云耀斑 (修复了鬼畜摇摆) —— 哥哥满意，原样保留，只是换了取值方式
// ==========================================
private data class StarParticle(
    val xRatio: Float,
    val yOffset: Float,
    val speed: Float,
    val baseSize: Float,
    val reactSensitivity: Float,
    val flareType: Int,
    val initialAngle: Float,
    val rotationSpeed: Float,
    val rayProportions: FloatArray,
    val twinkleSpeed: Float,
    val driftSpeed: Float
)

@Composable
private fun StardustBackground(palette: AlbumPalette?, dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val displayAmp = rememberAudioLevels(isPlaying).burst
    val infiniteTransition = rememberInfiniteTransition(label = "stardust")
    val phase by infiniteTransition.animateFloat(0f, 1000f, infiniteRepeatable(tween(30000, easing = LinearEasing)), label = "phase")

    val accent = palette?.accent ?: palette?.secondary ?: dominantColor

    val particles = remember {
        List(100) {
            val rand = Math.random()
            val type = when {
                rand > 0.75 -> 8
                rand > 0.45 -> 6
                rand > 0.15 -> 4
                else -> 0
            }

            val lineCount = type / 2
            val proportions = FloatArray(lineCount)

            when (type) {
                8 -> {
                    proportions[0] = 1.0f
                    proportions[1] = 0.45f + Math.random().toFloat() * 0.15f
                    proportions[2] = 0.80f + Math.random().toFloat() * 0.15f
                    proportions[3] = 0.45f + Math.random().toFloat() * 0.15f
                }
                6 -> {
                    proportions[0] = 1.0f
                    proportions[1] = 0.6f + Math.random().toFloat() * 0.3f
                    proportions[2] = 0.6f + Math.random().toFloat() * 0.3f
                }
                4 -> {
                    proportions[0] = 1.0f
                    proportions[1] = 0.6f + Math.random().toFloat() * 0.35f
                }
            }

            StarParticle(
                xRatio = Math.random().toFloat(),
                yOffset = Math.random().toFloat(),
                speed = 0.05f + Math.random().toFloat() * 0.15f,
                baseSize = 0.5f + Math.random().toFloat() * 1.2f,
                reactSensitivity = if (type == 8) 1.2f + Math.random().toFloat() * 1.5f else 0.5f + Math.random().toFloat() * 1.5f,
                flareType = type,
                initialAngle = (Math.random() * 360).toFloat(),
                rotationSpeed = ((Math.random() - 0.5f) * 8f).toFloat(),
                rayProportions = proportions,
                twinkleSpeed = 0.5f + Math.random().toFloat() * 1.5f,
                driftSpeed = 0.05f + Math.random().toFloat() * 0.5f
            )
        }
    }

    Box(modifier = modifier.drawBehind {
        drawRect(dominantColor.copy(alpha = 0.06f))

        particles.forEach { star ->
            val twinkle = (sin(phase * star.twinkleSpeed + star.xRatio * 100f) * 0.5f + 0.5f).toFloat()

            val yPos = size.height - ((phase * star.speed * 8f + star.yOffset * size.height) % size.height)

            val xPos = (star.xRatio * size.width) + sin(phase * star.driftSpeed + star.yOffset * 50f) * 1f

            val audioReact = displayAmp * star.reactSensitivity
            val react = audioReact + (twinkle * 0.25f)

            val pRadius = star.baseSize + (react * 0.8f)
            val baseAlpha = 0.15f + (react * 1.2f)
            val fadeOut = (yPos / size.height).coerceIn(0f, 1f)
            val finalAlpha = (baseAlpha * fadeOut).coerceIn(0f, 1f)

            val centerOffset = Offset(xPos, yPos)

            val pColor = if (star.flareType == 8) accent else dominantColor

            if (react > 0.05f) {
                val glowRadius = pRadius * 3.5f
                drawCircle(Brush.radialGradient(listOf(pColor.copy(alpha = finalAlpha * 0.35f), Color.Transparent), center = centerOffset, radius = glowRadius), radius = glowRadius, center = centerOffset)
            }

            drawCircle(color = pColor.copy(alpha = finalAlpha), radius = pRadius, center = centerOffset)

            if (star.flareType > 0) {
                val baseFlareLen = star.baseSize * (if (star.flareType == 8) 12f else 8f) * (react + 0.3f)
                val flareStroke = 0.6f + (react * 0.6f)

                val lineCount = star.flareType / 2
                val angleStep = 180f / lineCount

                for (i in 0 until lineCount) {
                    val currentAngle = star.initialAngle + phase * star.rotationSpeed + (i * angleStep)
                    val rad = Math.toRadians(currentAngle.toDouble()).toFloat()

                    val prop = star.rayProportions[i]
                    val actualLen = baseFlareLen * prop
                    val actualAlpha = finalAlpha * (0.5f + 0.5f * prop)
                    val actualStroke = flareStroke * (0.6f + 0.4f * prop)

                    val dx = cos(rad) * actualLen
                    val dy = sin(rad) * actualLen

                    val startOffset = Offset(xPos - dx, yPos - dy)
                    val endOffset = Offset(xPos + dx, yPos + dy)

                    val centerColor = pColor.copy(alpha = actualAlpha.coerceIn(0f, 1f))
                    val flareBrush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, centerColor, Color.Transparent),
                        start = startOffset,
                        end = endOffset
                    )

                    drawLine(brush = flareBrush, start = startOffset, end = endOffset, strokeWidth = actualStroke * 2.5f, cap = StrokeCap.Butt)
                    drawLine(brush = flareBrush, start = startOffset, end = endOffset, strokeWidth = actualStroke * 0.5f, cap = StrokeCap.Butt)
                }
            }
        }
    }) { content() }
}

// ==========================================
// 极细 EQ —— 重新设计：真实频谱数据 + 逐柱平滑 + 统一配色
// ==========================================
@Composable
private fun ClassicLineEqBackground(palette: AlbumPalette?, dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val levels = rememberAudioLevels(isPlaying)
    val accent = palette?.secondary ?: dominantColor

    // 【重新设计】：以前每根柱子的高度是拿同一个整体响度值配上正弦波"伪造"出看起来随机的
    // 高低变化——柱子之间的差异跟音乐真实的频率内容毫无关系，这是它显得不高级的主要原因。
    // 现在直接采样 VisualizerData.fftBands 里真实的 128 段频谱（这个数组本身已经按人耳感知
    // 的对数频率分好段了，见 PlaybackService.updateFftBands），柱子高低就是这首歌此刻真实的样子。
    val barCount = 56
    val barHeights = remember { FloatArray(barCount) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                for (i in 0 until barCount) {
                    val bandIndex = (i * VisualizerData.fftBands.size / barCount).coerceIn(0, VisualizerData.fftBands.size - 1)
                    val target = (VisualizerData.fftBands[bandIndex] * 5f).coerceIn(0f, 1f)
                    barHeights[i] += (if (target > barHeights[i]) 0.5f else 0.12f) * (target - barHeights[i])
                }
                delay(16L)
            }
        } else {
            for (i in 0 until barCount) barHeights[i] = 0f
        }
    }

    Box(modifier = modifier.drawBehind {
        drawRect(dominantColor.copy(alpha = 0.05f))
        val spacing = size.width / barCount
        val maxBarHeight = size.height * 0.24f

        for (i in 0 until barCount) {
            val x = spacing * i + spacing / 2f
            val h = barHeights[i]
            val barHeight = 3f + maxBarHeight * h

            val sweep = sin((i / barCount.toFloat()) * PI).toFloat()
            val barColor = lerp(dominantColor, accent, sweep)

            val tipAlpha = (0.35f + h * 0.65f).coerceIn(0f, 1f)
            val brush = Brush.verticalGradient(
                colors = listOf(barColor.copy(alpha = tipAlpha), barColor.copy(alpha = tipAlpha * 0.15f)),
                startY = (size.height - barHeight).coerceAtLeast(0f),
                endY = size.height
            )

            drawLine(color = barColor.copy(alpha = tipAlpha * 0.22f), start = Offset(x, size.height), end = Offset(x, size.height - barHeight), strokeWidth = 10f, cap = StrokeCap.Round)
            drawLine(brush = brush, start = Offset(x, size.height), end = Offset(x, size.height - barHeight), strokeWidth = 2f, cap = StrokeCap.Round)

            if (levels.burst > 0.3f && h > 0.5f) {
                drawCircle(barColor.copy(alpha = (levels.burst * 0.8f).coerceIn(0f, 1f)), radius = 2.5f, center = Offset(x, size.height - barHeight))
            }
        }
    }) { content() }
}

// ==========================================
// 深邃地平线 —— 哥哥觉得还行，只修定位 bug，视觉效果不动
// ==========================================
@Composable
private fun PremiumHorizonBackground(palette: AlbumPalette?, dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val displayAmp = rememberAudioLevels(isPlaying).burst
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "phase")

    val accent = palette?.secondary ?: dominantColor

    Box(modifier = modifier.drawBehind {
        drawRect(dominantColor.copy(alpha = 0.08f))

        // 【修复】：以前用 size.height * 0.695f 定位地平线——"画布高度的固定比例"。
        // 不同设备屏幕宽高比不一样，但下面的播放控件（进度条、播放按钮那一排）是按
        // 固定 dp 堆出来的，不会跟着屏幕比例缩放；比例定位和固定 dp 布局这两套度量
        // 对不上，就会导致有的设备上这条线正好穿过控件区。改成"离底部固定 dp 距离"，
        // 因为下面的控件本身也是从底部往上用固定 dp 堆的，两边用同一套度量单位，
        // 才能在不同设备上稳定错开。
        // 260dp 是按常见的"进度条 + 播放按钮区"预留高度估的，如果在你实际设备上
        // 看还是贴得有点近，把这一个数字调大就行，其它都不用动。
        val bottomReservedPx = 260.dp.toPx()
        val centerY = (size.height - bottomReservedPx).coerceAtLeast(size.height * 0.45f)

        val glowHeight = size.height * 0.15f + (displayAmp * size.height * 0.35f)

        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.1f + displayAmp * 0.2f)), startY = centerY - glowHeight, endY = centerY), topLeft = Offset(0f, centerY - glowHeight), size = Size(size.width, glowHeight))
        drawRect(brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.1f + displayAmp * 0.2f), Color.Transparent), startY = centerY, endY = centerY + glowHeight), topLeft = Offset(0f, centerY), size = Size(size.width, glowHeight))

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
// 流体漫游 —— 重新设计：小范围、多光斑、跟着连续包络呼吸，不再是两坨巨型闪光
// ==========================================
private data class FluidOrb(
    val baseXRatio: Float, val baseYRatio: Float,
    val orbitXRatio: Float, val orbitYRatio: Float,
    val periodMs: Int, val phaseOffsetDeg: Float,
    val sizeRatio: Float,
    val useLeftColor: Boolean
)

@Composable
private fun AppleFluidBackground(palette: AlbumPalette?, dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val levels = rememberAudioLevels(isPlaying)
    val infiniteTransition = rememberInfiniteTransition(label = "fluidOrbs")

    val colorLeft = palette?.secondary ?: run {
        val hsvBuf = FloatArray(3)
        android.graphics.Color.colorToHSV(dominantColor.toArgb(), hsvBuf)
        val baseHue = hsvBuf[0]; val baseSat = hsvBuf[1]
        hsvBuf[0] = (baseHue - 25f + 360f) % 360f; hsvBuf[1] = (baseSat + 0.1f).coerceAtMost(1f)
        Color(android.graphics.Color.HSVToColor(hsvBuf))
    }
    val colorRight = palette?.accent ?: run {
        val hsvBuf = FloatArray(3)
        android.graphics.Color.colorToHSV(dominantColor.toArgb(), hsvBuf)
        val baseHue = hsvBuf[0]; val baseSat = hsvBuf[1]
        hsvBuf[0] = (baseHue + 25f) % 360f; hsvBuf[1] = (baseSat + 0.1f).coerceAtMost(1f)
        Color(android.graphics.Color.HSVToColor(hsvBuf))
    }

    // 【重新设计】：以前只有 2 个巨大的光斑，覆盖屏幕相当大一片区域，响度一冲高，
    // 半径和透明度直接乘上去，相当于全屏幅度的一大片同时"炸"一下，看起来就是打雷闪电，
    // 而且驱动它的是瞬态尖峰量（说变就变、说没就没），调灵敏度只是改变"多久炸一次"，
    // 炸的那一下有多突兀是不变的，所以调了也没用。
    // 现在换成 5 个小得多的光斑（sizeRatio 只有原来的 1/3～1/4），各自沿独立的椭圆轨迹
    // 缓慢漂移——"漂移带来的流动感"这个哥哥喜欢的特质完全保留；响度改用连续包络
    // （levels.smooth，真实跟着音乐能量连续起伏）驱动"较小范围"的亮度/半径微调，
    // 瞬态量只贡献一点点额外点缀，听感上是"跟着节奏一起收缩舒张"，不再是忽然炸开。
    val orbs = remember {
        listOf(
            FluidOrb(0.20f, 0.25f, 0.12f, 0.08f, 14000, 0f,   0.15f, true),
            FluidOrb(0.75f, 0.30f, 0.10f, 0.10f, 18000, 90f,  0.13f, false),
            FluidOrb(0.35f, 0.68f, 0.14f, 0.09f, 21000, 180f, 0.12f, true),
            FluidOrb(0.80f, 0.72f, 0.09f, 0.12f, 16000, 260f, 0.14f, false),
            FluidOrb(0.55f, 0.45f, 0.11f, 0.11f, 24000, 45f,  0.10f, true)
        )
    }
    val orbPhases = orbs.map { orb ->
        infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(orb.periodMs, easing = LinearEasing)), label = "orbPhase")
    }

    Box(modifier = modifier.drawBehind {
        drawRect(dominantColor.copy(alpha = 0.10f))

        val pulse = 0.8f + levels.smooth * 0.35f + levels.burst * 0.10f

        orbs.forEachIndexed { idx, orb ->
            val rad = Math.toRadians((orbPhases[idx].value + orb.phaseOffsetDeg).toDouble())
            val cx = size.width * orb.baseXRatio + sin(rad).toFloat() * size.width * orb.orbitXRatio
            val cy = size.height * orb.baseYRatio + cos(rad).toFloat() * size.height * orb.orbitYRatio
            val radius = size.maxDimension * orb.sizeRatio * pulse
            val alpha = (0.16f + levels.smooth * 0.18f + levels.burst * 0.08f).coerceIn(0f, 0.55f)
            val color = if (orb.useLeftColor) colorLeft else colorRight
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = Offset(cx, cy), radius = radius),
                radius, Offset(cx, cy)
            )
        }
    }) { content() }
}

// ==========================================
// 极光绸缎（新方案）—— 几条柔光丝带缓慢流动，连续包络驱动亮度/幅度，不用瞬态尖峰
// ==========================================
private data class AuroraRibbon(
    val baseYRatio: Float, val amplitudeRatio: Float, val frequency: Float,
    val periodMs: Int, val thicknessRatio: Float, val colorIndex: Int
)

@Composable
private fun AuroraVeilBackground(palette: AlbumPalette?, dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val levels = rememberAudioLevels(isPlaying)
    val infiniteTransition = rememberInfiniteTransition(label = "aurora")

    val colors = listOf(
        dominantColor,
        palette?.secondary ?: dominantColor,
        palette?.accent ?: dominantColor
    )

    val ribbons = remember {
        listOf(
            AuroraRibbon(0.30f, 0.06f, 1.6f, 22000, 0.10f, 0),
            AuroraRibbon(0.48f, 0.08f, 1.1f, 28000, 0.14f, 1),
            AuroraRibbon(0.65f, 0.05f, 2.0f, 19000, 0.09f, 2)
        )
    }
    val ribbonPhases = ribbons.map { r ->
        infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(r.periodMs, easing = LinearEasing)), label = "ribbonPhase")
    }

    Box(modifier = modifier.drawBehind {
        drawRect(dominantColor.copy(alpha = 0.06f))

        val glow = 0.55f + levels.smooth * 0.5f

        ribbons.forEachIndexed { idx, ribbon ->
            val radPhase = Math.toRadians(ribbonPhases[idx].value.toDouble()).toFloat()
            val color = colors[ribbon.colorIndex % colors.size]
            val baseY = size.height * ribbon.baseYRatio
            val amp = size.height * ribbon.amplitudeRatio * (0.7f + levels.smooth * 0.5f)
            val thickness = size.height * ribbon.thicknessRatio * (0.8f + levels.smooth * 0.3f)

            val path = Path()
            var started = false
            var x = 0f
            while (x <= size.width) {
                val nx = x / size.width
                val y = baseY +
                        sin(nx * ribbon.frequency * 2f * PI.toFloat() + radPhase) * amp +
                        sin(nx * ribbon.frequency * 5f * PI.toFloat() - radPhase * 1.7f) * amp * 0.25f
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                x += 12f
            }

            val brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, color.copy(alpha = (0.35f * glow).coerceIn(0f, 1f)), Color.Transparent),
                startY = baseY - thickness, endY = baseY + thickness
            )
            drawPath(path, brush = brush, style = Stroke(width = thickness))

            if (levels.burst > 0.35f) {
                val sparkleNx = (ribbonPhases[idx].value % 100f) / 100f
                val sparkleX = size.width * sparkleNx
                val sparkleY = baseY + sin(sparkleNx * ribbon.frequency * 2f * PI.toFloat() + radPhase) * amp
                drawCircle(
                    color.copy(alpha = (levels.burst * 0.6f).coerceIn(0f, 1f)),
                    radius = 3f + levels.burst * 4f,
                    center = Offset(sparkleX, sparkleY)
                )
            }
        }
    }) { content() }
}

@Composable
private fun StaticGradientBackground(palette: AlbumPalette?, dominantColor: Color, surface: Color, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val topColor = dominantColor
    val midColor = palette?.secondary ?: dominantColor
    val brush = Brush.verticalGradient(listOf(topColor.copy(alpha = 0.55f), midColor.copy(alpha = 0.25f), surface))
    Box(modifier = modifier.background(brush)) { content() }
}

@Composable
private fun BreathingGradientBackground(palette: AlbumPalette?, dominantColor: Color, surface: Color, isPlaying: Boolean, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val targetBreatheAlpha = infiniteTransition.animateFloat(0.20f, 0.45f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha")
    val targetOffsetX = infiniteTransition.animateFloat(0.2f, 0.8f, infiniteRepeatable(tween(8500, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    val targetOffsetY = infiniteTransition.animateFloat(0.1f, 0.6f, infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Reverse), label = "y")

    val playFraction by animateFloatAsState(if (isPlaying) 1f else 0f, tween(1500, easing = FastOutSlowInEasing), label = "playFraction")
    val currentAlpha = 0.20f + (targetBreatheAlpha.value - 0.20f) * playFraction
    val currentOffsetX = 0.5f + (targetOffsetX.value - 0.5f) * playFraction
    val currentOffsetY = 0.5f + (targetOffsetY.value - 0.5f) * playFraction

    val accent = palette?.secondary ?: dominantColor

    Box(modifier = modifier.drawBehind {
        val centerOffset = Offset(size.width * currentOffsetX, size.height * currentOffsetY)
        drawRect(Brush.radialGradient(listOf(dominantColor.copy(alpha = currentAlpha), accent.copy(alpha = currentAlpha * 0.6f), surface.copy(alpha = 1f)), center = centerOffset, radius = size.maxDimension * 0.85f))
    }) { content() }
}