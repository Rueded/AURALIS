package com.auralis.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

object SpectrogramGenerator {
    private const val TAG = "SpectrogramGenerator"

    data class Result(
        val bitmap: Bitmap,
        val cutoffHz: Float,
        val verdict: String,
        val verdictDetails: String,
        val isLossless: Boolean,
        val sampleRate: Int,
        /** 0 = 没发现问题（绿）  1 = 需要留意（橙）  2 = 很可能有问题（红）。界面用它决定徽章颜色，不再自己拿截止频率去比。 */
        val level: Int = 0
    )

    // ───────────────────────── 判断用到的常量和小工具 ─────────────────────────

    // 满量程正弦经 Hann 窗 FFT 后的幅度是 N/4（N=2048 时为 512），以它作 0 dBFS，电平才有绝对意义。
    private const val REF_POWER = (2048.0 / 4.0) * (2048.0 / 4.0)
    // 每个 FFT bin 的电平低于它就算"空"。MediaCodec 解出来是 16 位 PCM，舍入噪声底约 -127 dBFS/bin，
    // 这里留 15 dB 余量（解码器可能带抖动噪声），所以不会被噪声底误判成"有内容"。
    private const val EMPTY_DB = -112.0
    // 相邻 500 Hz 的两段，电平差超过这个值才算"悬崖"。自然衰减（每倍频程几 dB）远达不到。
    private const val CLIFF_MIN_DB = 20.0

    private enum class FormatKind { LOSSY, LOSSLESS, UNKNOWN }

    private val LOSSY_MIMES = setOf(
        "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2", "audio/mp4a-latm", "audio/aac", "audio/vorbis", "audio/opus",
        "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4", "audio/3gpp", "audio/amr", "audio/amr-wb",
        "audio/qcelp", "audio/evrc", "audio/gsm", "audio/g711-alaw", "audio/g711-mlaw"
    )
    private val LOSSLESS_MIMES = setOf("audio/flac", "audio/alac", "audio/raw", "audio/x-wav")
    private val LOSSY_EXTENSIONS = setOf("mp3", "mp2", "aac", "ogg", "oga", "opus", "wma", "amr", "ac3")

    /** 先看文件本身的编码：MP3/AAC 这类本来就是有损格式，不该拿"频谱像不像无损"去问它。 */
    private fun classifyFormat(mime: String, fileName: String): FormatKind {
        val m = mime.lowercase()
        if (m in LOSSY_MIMES) return FormatKind.LOSSY
        if (m in LOSSLESS_MIMES) return FormatKind.LOSSLESS
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return if (ext in LOSSY_EXTENSIONS) FormatKind.LOSSY else FormatKind.UNKNOWN
    }

    private fun formatLabel(mime: String): String = when (mime.lowercase()) {
        "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> "MP3"
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/vorbis" -> "Vorbis"
        "audio/opus" -> "Opus"
        "audio/ac3", "audio/eac3", "audio/eac3-joc" -> "AC-3"
        "" -> "?"
        else -> mime.substringAfter('/').uppercase()
    }

    private class Evidence(
        /** 检测到"陡峭低通悬崖"的位置（Hz）；没有悬崖则为 null。 */
        val cliffHz: Float?,
        /** 界面显示用：平滑后仍高于"空"电平的最高频率。 */
        val displayCutoffHz: Float
    )

    /**
     * 在平均功率谱上找"有损编码特有的陡峭低通"。
     *
     * 以前的做法是：找"平均幅度高于 -65 的最高频率"，≥20 kHz 就判无损、18~20 kHz 判"升频有损"、更低判"假无损"。
     * 它的问题（我用真实的 LAME / AAC 编码结果测过）：
     *  · 取"最高的活跃 bin"：320 kbps 的 MP3 低通在 20.2 kHz，直接被判成"真无损"；
     *  · 自然衰减的真无损（老录音、人声）高频逐渐变弱，会被判成"升频有损/假无损"；
     *  · 只看一个绝对阈值，没有"悬崖"这个概念，所以既漏判又误判。
     * 现在看的是"有没有陡峭的悬崖，且悬崖之后一直是空的"，并且按悬崖的位置分档，不再用一刀切的阈值。
     */
    private fun analyzeSpectrum(sumPower: DoubleArray, numFrames: Int, sampleRate: Int): Evidence {
        val n = sumPower.size
        val p = DoubleArray(n) { sumPower[it] / numFrames }
        val binHz = (sampleRate / 2.0) / n
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + p[i]
        fun mean(from: Int, to: Int): Double = (prefix[to] - prefix[from]) / (to - from)   // [from, to)
        fun dbOf(power: Double): Double = 10.0 * log10(power / REF_POWER + 1e-20)

        // 显示用的截止频率：平滑（约 8 个 bin）后仍高于"空"电平的最高位置，单个尖峰不会把它拉高
        var lastActive = -1
        for (i in 0 until n) {
            val from = maxOf(0, i - 4)
            val to = minOf(n, i + 4)
            if (dbOf(mean(from, to)) > EMPTY_DB) lastActive = i
        }
        val displayCutoff = if (lastActive >= 0) ((lastActive + 0.5) * binHz).toFloat() else 0f

        // 采样率太低时（奈奎斯特 < 20 kHz）这套"高频悬崖"的判断没有意义
        if (sampleRate < 40000) return Evidence(null, displayCutoff)

        val w = maxOf(4, (500.0 / binHz).toInt())          // 500 Hz 宽的窗
        val lo = (8000.0 / binHz).toInt()                  // 只看 8 kHz 以上
        var bestDrop = -1e9
        var bestK = -1
        for (k in (lo + w) until (n - w)) {
            val drop = 10.0 * log10(mean(k - w, k) + 1e-30) - 10.0 * log10(mean(k, k + w) + 1e-30)
            if (drop > bestDrop) { bestDrop = drop; bestK = k }
        }
        if (bestK < 0) return Evidence(null, displayCutoff)

        val above = dbOf(mean(bestK, bestK + w))
        val tail = if (bestK + w < n) dbOf(mean(bestK + w, n)) else above
        // 是"悬崖"：落差够大，并且悬崖之后一直到奈奎斯特都是"空"的（接近 16 位舍入噪声底）
        val isCliff = bestDrop >= CLIFF_MIN_DB && above <= EMPTY_DB + 6 && tail <= EMPTY_DB + 6
        Log.d(TAG, "cliff drop=${"%.1f".format(bestDrop)}dB at ${"%.0f".format((bestK + 0.5) * binHz)}Hz above=${"%.1f".format(above)} tail=${"%.1f".format(tail)} -> $isCliff")
        return Evidence(if (isCliff) ((bestK + 0.5) * binHz).toFloat() else null, displayCutoff)
    }

    suspend fun generate(audioPath: String, context: Context): Result? = withContext(Dispatchers.IO) {
        val file = File(audioPath)
        if (!file.exists()) {
            Log.e(TAG, "Audio file does not exist: $audioPath")
            return@withContext null
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set data source: ${e.message}")
            extractor.release()
            return@withContext null
        }

        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                break
            }
        }

        if (trackIndex == -1) {
            Log.e(TAG, "No audio track found in file")
            extractor.release()
            return@withContext null
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        // We target 20 seconds of audio sample
        val targetSeconds = 20
        val targetSamples = sampleRate * targetSeconds
        val sampleData = FloatArray(targetSamples)
        var totalSamplesDecoded = 0

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create decoder for MIME $mime: ${e.message}")
            extractor.release()
            return@withContext null
        }

        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            Log.e(TAG, "Codec configuration failed: ${e.message}")
            codec.release()
            extractor.release()
            return@withContext null
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var isInputEOS = false
        var isOutputEOS = false

        // Seek to 1/3 of the song duration to get a representative sample of the song
        if (durationUs > 0) {
            extractor.seekTo(durationUs / 3, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        try {
            while (!isOutputEOS && totalSamplesDecoded < targetSamples) {
                if (!isInputEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val shortBuffer = outputBuffer.asShortBuffer()
                        val chunkSamples = shortBuffer.remaining()

                        var i = 0
                        while (i < chunkSamples && totalSamplesDecoded < targetSamples) {
                            var sum = 0f
                            var count = 0
                            for (c in 0 until channelCount) {
                                if (shortBuffer.hasRemaining()) {
                                    sum += shortBuffer.get().toFloat() / 32768.0f
                                    count++
                                }
                            }
                            if (count > 0) {
                                sampleData[totalSamplesDecoded] = sum / count
                                totalSamplesDecoded++
                            }
                            i += channelCount
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during decoding: ${e.message}")
        } finally {
            try {
                codec.stop()
            } catch (ignored: Exception) {}
            codec.release()
            extractor.release()
        }

        val actualSamples = totalSamplesDecoded
        val fftSize = 2048
        val hopSize = 1024
        val numFrames = (actualSamples - fftSize) / hopSize

        if (numFrames <= 0) {
            Log.e(TAG, "Audio sample is too short to generate spectrogram")
            return@withContext null
        }

        // Hann window coefficients
        val hannWindow = FloatArray(fftSize) { i ->
            0.5f * (1.0f - Math.cos(2.0 * Math.PI * i / (fftSize - 1)).toFloat())
        }

        val numBins = fftSize / 2 // 1024 bins
        val magnitudeMatrix = Array(numFrames) { FloatArray(numBins) }
        val sumMagnitudes = FloatArray(numBins)
        val sumPower = DoubleArray(numBins)   // 平均功率谱（用来找有损编码的低通悬崖）

        // Process frames
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)

        for (f in 0 until numFrames) {
            val startIdx = f * hopSize
            for (i in 0 until fftSize) {
                re[i] = sampleData[startIdx + i] * hannWindow[i]
                im[i] = 0f
            }

            fft(re, im)

            for (bin in 0 until numBins) {
                val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                magnitudeMatrix[f][bin] = mag
                sumMagnitudes[bin] += mag
                sumPower[bin] += (mag * mag).toDouble()
            }
        }

        // 频谱色阶（画频谱图用）
        val minDb = -100.0f
        val maxDb = 0.0f

        // ───────── 有损编码痕迹检测 ─────────
        val evidence = analyzeSpectrum(sumPower, numFrames, sampleRate)
        val cutoffHz = evidence.displayCutoffHz
        val cutKhz = String.format("%.1f", cutoffHz / 1000.0f)
        val kind = classifyFormat(mime, file.name)

        val verdict: String
        val verdictDetails: String
        val isLossless: Boolean
        val level: Int

        if (kind == FormatKind.LOSSY) {
            // 文件本身就是有损格式：不拿"频谱像不像无损"去判，直接如实说明，再补充频谱上看到的情况。
            val label = formatLabel(mime)
            val bitrateKbps: Int? = runCatching {
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                else if (durationUs > 0) (file.length() * 8.0 / (durationUs / 1_000_000.0) / 1000.0).toInt()
                else 0
            }.getOrDefault(0).takeIf { it > 0 }
            val br = if (bitrateKbps != null) context.getString(R.string.verdict_bitrate_approx, bitrateKbps)
                     else context.getString(R.string.verdict_bitrate_unknown)
            val note = evidence.cliffHz?.let { context.getString(R.string.verdict_note_cliff, String.format("%.1f", it / 1000.0f)) }
                ?: context.getString(R.string.verdict_note_nocliff, cutKhz)
            verdict = context.getString(R.string.verdict_lossy_format, label)
            verdictDetails = context.getString(R.string.verdict_lossy_format_detail, label, br, note)
            isLossless = false
            level = 1
        } else {
            // 无损容器（FLAC / ALAC / WAV），或格式未知：看频谱里有没有"有损转码"的悬崖
            val fc = evidence.cliffHz
            when {
                fc != null && sampleRate >= 88200 && fc in 21500f..24500f -> {
                    verdict = context.getString(R.string.verdict_upsampled)
                    verdictDetails = context.getString(R.string.verdict_upsampled_detail,
                        String.format("%.1f", sampleRate / 2000.0f), String.format("%.1f", fc / 1000.0f))
                    isLossless = false; level = 1
                }
                fc != null && fc < 19000f -> {
                    val klass = context.getString(if (fc < 17500f) R.string.verdict_class_128 else R.string.verdict_class_192)
                    verdict = context.getString(R.string.verdict_likely_lossy)
                    verdictDetails = context.getString(R.string.verdict_likely_lossy_detail, String.format("%.1f", fc / 1000.0f), klass)
                    isLossless = false; level = 2
                }
                fc != null && fc < 20700f -> {
                    // 256~320 kbps 的 MP3 低通正好在这一带，但 CD 年代母带本身的滤波也常在这里：不下结论
                    verdict = context.getString(R.string.verdict_ambiguous)
                    verdictDetails = context.getString(R.string.verdict_ambiguous_detail, String.format("%.1f", fc / 1000.0f))
                    isLossless = false; level = 1
                }
                fc == null && cutoffHz < 16000f -> {
                    // 高频内容少，但不是悬崖：更像是录音本身（人声、原声乐器、老录音）
                    verdict = context.getString(R.string.verdict_inconclusive)
                    verdictDetails = context.getString(R.string.verdict_inconclusive_detail, cutKhz)
                    isLossless = true; level = 0
                }
                else -> {
                    verdict = context.getString(R.string.verdict_no_trace)
                    verdictDetails = context.getString(R.string.verdict_no_trace_detail, cutKhz)
                    isLossless = true; level = 0
                }
            }
        }

        // Generate the gorgeous Spek spectrogram Bitmap
        // width = numFrames, height = numBins (1024)
        val bmp = Bitmap.createBitmap(numFrames, numBins, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(numFrames * numBins)

        for (y in 0 until numBins) {
            val binIdx = numBins - 1 - y // Bin 0 (DC) at bottom, Nyquist at top
            for (x in 0 until numFrames) {
                val mag = magnitudeMatrix[x][binIdx]
                val db = 20.0f * log10(mag + 1e-8f)
                val ratio = ((db - minDb) / (maxDb - minDb)).coerceIn(0.0f, 1.0f)

                pixels[y * numFrames + x] = getColorForRatio(ratio)
            }
        }

        bmp.setPixels(pixels, 0, numFrames, 0, 0, numFrames, numBins)

        Log.d(TAG, "Acoustic detection completed: $cutoffHz Hz -> $verdict")
        return@withContext Result(bmp, cutoffHz, verdict, verdictDetails, isLossless, sampleRate, level)
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        if (n == 0) return
        var i = 0
        var j = 0
        while (i < n) {
            if (i < j) {
                var temp = re[i]
                re[i] = re[j]
                re[j] = temp
                temp = im[i]
                im[i] = im[j]
                im[j] = temp
            }
            var m = n shr 1
            while (m >= 2 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
            i++
        }

        var len = 2
        while (len <= n) {
            val ang = 2 * Math.PI / len
            val wpr = Math.cos(ang).toFloat()
            val wpi = -Math.sin(ang).toFloat()
            var iVal = 0
            while (iVal < n) {
                var wr = 1.0f
                var wi = 0.0f
                for (k in 0 until len / 2) {
                    val idx1 = iVal + k
                    val idx2 = iVal + k + len / 2
                    val tr = re[idx2] * wr - im[idx2] * wi
                    val ti = re[idx2] * wi + im[idx2] * wr
                    re[idx2] = re[idx1] - tr
                    im[idx2] = im[idx1] - ti
                    re[idx1] += tr
                    im[idx1] += ti
                    val nextWr = wr * wpr - wi * wpi
                    wi = wr * wpi + wi * wpr
                    wr = nextWr
                }
                iVal += len
            }
            len = len shl 1
        }
    }

    private fun getColorForRatio(ratio: Float): Int {
        val r = ratio.coerceIn(0.0f, 1.0f)
        val colors = arrayOf(
            intArrayOf(10, 10, 30),      // 0.0: Almost black/deep blue
            intArrayOf(40, 0, 120),      // 0.2: Deep purple
            intArrayOf(0, 100, 200),     // 0.4: Bright blue
            intArrayOf(0, 200, 100),     // 0.6: Green
            intArrayOf(240, 220, 0),     // 0.8: Yellow
            intArrayOf(255, 30, 30)      // 1.0: Red
        )
        val segment = r * (colors.size - 1)
        val index = segment.toInt().coerceIn(0, colors.size - 2)
        val localRatio = segment - index

        val c1 = colors[index]
        val c2 = colors[index + 1]

        val red = (c1[0] + (c2[0] - c1[0]) * localRatio).toInt()
        val green = (c1[1] + (c2[1] - c1[1]) * localRatio).toInt()
        val blue = (c1[2] + (c2[2] - c1[2]) * localRatio).toInt()

        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
