package com.example.mymusic

import android.media.audiofx.Visualizer
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

// ── 静态渐变（原有效果）────────────────────────────────────────────────────────
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

    // 播放中：0.35 ↔ 0.70 循环；停止时固定 0.40
    val topAlpha by if (isPlaying) {
        infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue  = 0.70f,
            animationSpec = infiniteRepeatable(
                animation  = tween(2800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breatheAlpha"
        )
    } else {
        remember { mutableStateOf(0.40f) }
    }

    // 播放中：轻微径向膨胀 0.7 ↔ 1.0
    val radialScale by if (isPlaying) {
        infiniteTransition.animateFloat(
            initialValue = 0.70f,
            targetValue  = 1.00f,
            animationSpec = infiniteRepeatable(
                animation  = tween(2800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breatheScale"
        )
    } else {
        remember { mutableStateOf(0.70f) }
    }

    val brush = Brush.radialGradient(
        colors = listOf(
            dominantColor.copy(alpha = topAlpha),
            dominantColor.copy(alpha = topAlpha * 0.45f),
            surface.copy(alpha = 1f)
        ),
        radius = 1800f * radialScale
    )

    Box(modifier = modifier.background(brush)) { content() }
}

// ── 音频响应：Visualizer 驱动，需要 RECORD_AUDIO 权限 ─────────────────────────
@Composable
private fun ReactiveGradientBackground(
    dominantColor: Color,
    surface: Color,
    isPlaying: Boolean,
    audioSessionId: Int,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    // 音频振幅 0..1
    var amplitude by remember { mutableStateOf(0f) }

    // 平滑后的振幅（避免画面抖动）
    val smoothedAmplitude by animateFloatAsState(
        targetValue = if (isPlaying) amplitude else 0f,
        animationSpec = tween(80, easing = LinearEasing),
        label = "amplitude"
    )

    // Visualizer 生命周期绑定到 Composable
    DisposableEffect(audioSessionId, isPlaying) {
        var visualizer: Visualizer? = null

        if (isPlaying && audioSessionId != 0) {
            try {
                visualizer = Visualizer(audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[0] // 最小捕获大小，降低开销
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer, data: ByteArray, samplingRate: Int) {
                                // 计算 RMS 振幅，归一化到 0..1
                                val rms = data.map { (it.toInt() and 0xFF) - 128 }
                                    .map { it * it.toDouble() }
                                    .average()
                                amplitude = (Math.sqrt(rms) / 128f).toFloat().coerceIn(0f, 1f)
                            }
                            override fun onFftDataCapture(v: Visualizer, data: ByteArray, samplingRate: Int) {}
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        true, false
                    )
                    enabled = true
                }
            } catch (_: Exception) {
                // 权限未授予或设备不支持，回退到静态渐变
            }
        }

        onDispose {
            try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
            amplitude = 0f
        }
    }

    // 振幅驱动：顶部 alpha 0.30 ~ 0.85，径向范围 1200 ~ 2400
    val topAlpha  = 0.30f + smoothedAmplitude * 0.55f
    val gradRadius = 1200f + smoothedAmplitude * 1200f

    // 第二色：同色相偏移 30°，给渐变增加层次
    val hsvBuf = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.argb(
            (dominantColor.alpha * 255).toInt(),
            (dominantColor.red   * 255).toInt(),
            (dominantColor.green * 255).toInt(),
            (dominantColor.blue  * 255).toInt()
        ), hsvBuf
    )
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
