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
    var amplitude by remember { mutableStateOf(0f) }

    // 💡 修复1：smoothedAmplitude 目标值由 isPlaying 控制，暂停时归零
    val smoothedAmplitude by animateFloatAsState(
        targetValue = if (isPlaying) amplitude else 0f,
        animationSpec = tween(80, easing = LinearEasing),
        label = "amplitude"
    )

    // 💡 修复2：key 加入 isPlaying，停止播放时立刻 release Visualizer
    DisposableEffect(audioSessionId, isPlaying) {
        var visualizer: Visualizer? = null

        if (isPlaying && audioSessionId != 0) {
            try {
                visualizer = Visualizer(audioSessionId).apply {
                    // 💡 修复3：使用最大 captureSize，数据更丰富，RMS 更准确
                    captureSize = Visualizer.getCaptureSizeRange()[1]

                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                v: Visualizer,
                                data: ByteArray,
                                samplingRate: Int
                            ) {
                                if (!isPlaying) {
                                    amplitude = 0f
                                    return
                                }
                                // 💡 修复4：避免 .map{}.map{} 链式操作产生大量临时对象
                                // 改成手动循环，零 GC 压力
                                var sumSq = 0.0
                                for (b in data) {
                                    val s = (b.toInt() and 0xFF) - 128
                                    sumSq += s.toLong() * s
                                }
                                val rms = Math.sqrt(sumSq / data.size)
                                amplitude = (rms / 128.0).toFloat().coerceIn(0f, 1f)
                            }

                            override fun onFftDataCapture(
                                v: Visualizer,
                                data: ByteArray,
                                samplingRate: Int
                            ) {}
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        true,
                        false
                    )
                    enabled = true
                }
                Log.d(TAG, "Visualizer 初始化成功，session=$audioSessionId")
            } catch (e: Exception) {
                // 💡 修复5：不再吞异常，明确打印，方便排查权限和 session 问题
                Log.e(TAG, "Visualizer 初始化失败（session=$audioSessionId）：${e.message}", e)
            }
        } else {
            // 💡 修复6：不播放时立刻把 amplitude 归零，不等 Compose 动画侧下一帧
            amplitude = 0f
        }

        onDispose {
            try {
                // 💡 修复7：先 disable 再 release，确保 AudioEffect 完全从 session 解绑
                // 这样 BitPerfect 模式才能正常获得纯净链路
                visualizer?.enabled = false
                visualizer?.release()
                Log.d(TAG, "Visualizer 已 release，session=$audioSessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Visualizer release 失败：${e.message}", e)
            }
            amplitude = 0f
        }
    }

    val topAlpha   = 0.30f + smoothedAmplitude * 0.55f
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