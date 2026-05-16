// 加入到 PlaybackService.kt 或新建 AuralisVisualizerProcessor.kt
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.sqrt

// 这是一个全局的通道，用于把底层音频振幅传递给 UI
object VisualizerData {
    @Volatile var amplitude: Float = 0f
}

// 云糯特制的底层音频处理器：无损监听，完美透传！
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AuralisVisualizerProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat // 原样透传，不破坏任何音质
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT

        var sumSq = 0.0
        var count = 0
        val origPos = inputBuffer.position()

        // 强行读取 32-bit Float 或 16-bit PCM 数据来计算 RMS 音量
        if (isFloat) {
            val floatBuf = inputBuffer.asFloatBuffer()
            while (floatBuf.hasRemaining()) {
                val sample = floatBuf.get()
                sumSq += sample * sample
                count++
            }
        } else if (is16Bit) {
            val shortBuf = inputBuffer.asShortBuffer()
            while (shortBuf.hasRemaining()) {
                val sample = shortBuf.get() / 32768.0
                sumSq += sample * sample
                count++
            }
        }

        if (count > 0) {
            val rms = sqrt(sumSq / count).toFloat()
            // 放大一点，让极光律动更明显
            VisualizerData.amplitude = (rms * 2.5f).coerceIn(0f, 1f)
        }

        // 归位，把数据完整拷贝给输出，继续前往 DAC！
        inputBuffer.position(origPos)
        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }
}