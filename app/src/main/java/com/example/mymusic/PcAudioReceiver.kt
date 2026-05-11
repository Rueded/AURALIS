import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object PcAudioReceiver {
    private var audioTrack: AudioTrack? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    @Volatile
    var isReceiving = false

    suspend fun startListening(startPort: Int = 8899) {
        if (isReceiving) stop()
        isReceiving = true

        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            audioTrack?.play()

            withContext(Dispatchers.IO) {
                var bindSuccess = false
                var currentPort = startPort

                // 🚀 自动探测：从 startPort 开始寻找 10 个端口，直到成功
                while (currentPort < startPort + 10 && !bindSuccess && isReceiving) {
                    try {
                        serverSocket = ServerSocket().apply {
                            reuseAddress = true
                            soTimeout = 0 // 无限等待连接
                            bind(InetSocketAddress(currentPort))
                        }
                        bindSuccess = true
                    } catch (e: Exception) {
                        Log.w("PcAudio", "端口 $currentPort 被占用，尝试下一个...")
                        currentPort++
                        delay(100)
                    }
                }

                if (!bindSuccess) {
                    Log.e("PcAudio", "❌ 错误：无法找到可用端口，请重启手机。")
                    isReceiving = false
                    return@withContext
                }

                // 📢 这里的 Log 非常重要，你要根据这个数字去电脑端配置！
                Log.d("PcAudio", "✅ 成功监听端口: $currentPort")
                Log.d("PcAudio", "👉 请执行: adb -s zltkqgibu8qso7e6 reverse tcp:$currentPort tcp:$currentPort")

                try {
                    clientSocket = serverSocket!!.accept()
                    Log.d("PcAudio", "🔗 电脑已连接到端口 $currentPort！音频流开启...")

                    val inputStream = clientSocket!!.getInputStream()
                    val buffer = ByteArray(bufferSize * 2)

                    while (isReceiving) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            audioTrack?.write(buffer, 0, bytesRead, AudioTrack.WRITE_BLOCKING)
                        } else if (bytesRead == -1) break
                    }
                } catch (e: Exception) {
                    if (isReceiving) Log.e("PcAudio", "传输异常: ${e.message}")
                } finally {
                    stop()
                }
            }
        } catch (e: Exception) {
            Log.e("PcAudio", "初始化失败: ${e.message}")
            isReceiving = false
        }
    }

    fun stop() {
        isReceiving = false
        try {
            clientSocket?.apply {
                shutdownInput()
                close()
            }
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("PcAudio", "Socket 清理失败: ${e.message}")
        } finally {
            clientSocket = null
            serverSocket = null
        }

        try {
            audioTrack?.apply {
                if (state != AudioTrack.STATE_UNINITIALIZED) {
                    stop()
                    flush()
                    release()
                }
            }
        } catch (e: Exception) {
            Log.e("PcAudio", "AudioTrack 释放失败: ${e.message}")
        } finally {
            audioTrack = null
        }
        Log.d("PcAudio", "📴 接收器资源已彻底释放")
    }
}