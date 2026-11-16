package com.auralis.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
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
        val level: Int = 0,
        val isFullTrack: Boolean = false,
        val durationUs: Long = 0L
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

    /**
     * 针对 Spek 频谱检测：优先选用 Google 官方 AOSP 标准音频解码器 (c2.android.* / OMX.google.*)，
     * 避免三星私有硬件解码器 (c2.sec.*) 在 seek 后因缺少初始关键帧而报错 0x80000000 或卡死丢帧。
     */
    private fun createSafeAudioDecoder(mime: String): MediaCodec {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val preferredName = codecList.codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                .sortedBy { info ->
                    val name = info.name.lowercase()
                    when {
                        name.startsWith("c2.android.") || name.startsWith("omx.google.") -> 0
                        name.contains(".sec.") || name.contains("samsung") -> 2
                        else -> 1
                    }
                }
                .firstOrNull()?.name

            if (preferredName != null) {
                Log.d(TAG, "Using preferred safe decoder: $preferredName for $mime")
                MediaCodec.createByCodecName(preferredName)
            } else {
                MediaCodec.createDecoderByType(mime)
            }
        } catch (_: Exception) {
            MediaCodec.createDecoderByType(mime)
        }
    }

    suspend fun generate(
        audioPath: String,
        context: Context,
        fullTrack: Boolean = false,
        onProgress: ((Float) -> Unit)? = null
    ): Result? = withContext(Dispatchers.IO) {
        val file = File(audioPath)
        if (!file.exists()) {
            Log.e(TAG, "Audio file does not exist: $audioPath")
            return@withContext null
        }

        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { fis ->
                extractor.setDataSource(fis.fd)
            }
        } catch (e: Exception) {
            try {
                extractor.setDataSource(audioPath)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to set data source: ${e2.message}")
                extractor.release()
                return@withContext null
            }
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
        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 2
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        // 防止部分三星手机解码器在 KEY_MAX_INPUT_SIZE 为 0 时直接抛异常
        if (!format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }

        val fftSize = 2048
        val numBins = fftSize / 2 // 1024 bins
        val hannWindow = FloatArray(fftSize) { i ->
            0.5f * (1.0f - Math.cos(2.0 * Math.PI * i / (fftSize - 1)).toFloat())
        }
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)

        // 快速模式（20秒） vs 全曲深度模式缓冲参数
        val targetSeconds = 20
        val targetSamples = sampleRate * targetSeconds
        val sampleData = if (!fullTrack) FloatArray(targetSamples) else FloatArray(0)
        var totalSamplesDecoded = 0

        // 全曲模式：流式环形缓冲 + 自适应 HopStep，避免大内存分配与 GPU 纹理溢出
        val estimatedTotalSamples = if (durationUs > 0) {
            (durationUs * sampleRate / 1_000_000L).coerceAtLeast(sampleRate.toLong() * 5L)
        } else {
            sampleRate.toLong() * 240L
        }
        val targetColumns = 2000
        val hopStep = maxOf(512L, estimatedTotalSamples / targetColumns)
        val ringSize = 4096
        val ringMask = ringSize - 1
        val ringBuffer = if (fullTrack) FloatArray(ringSize) else FloatArray(0)
        var ringWritePos = 0
        var totalAudioSamplesSeen = 0L
        var nextFftTargetSample = 0L
        val magnitudeColumns = if (fullTrack) ArrayList<FloatArray>(2400) else ArrayList<FloatArray>(0)
        val sumMagnitudes = FloatArray(numBins)
        val sumPower = DoubleArray(numBins)

        var codec = try {
            createSafeAudioDecoder(mime)
        } catch (e: Exception) {
            MediaCodec.createDecoderByType(mime)
        }

        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            Log.w(TAG, "Safe decoder configuration failed: ${e.message}, falling back to default decoder")
            try {
                codec.release()
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e2: Exception) {
                Log.e(TAG, "All decoders failed for $mime: ${e2.message}")
                extractor.release()
                return@withContext null
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var isInputEOS = false
        var isOutputEOS = false
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var emptyCycles = 0

        // 解码从文件头部（0ms）自然开始：绝不对未内嵌 SeekTable 的 44.1kHz FLAC / CD 音轨进行粗暴的线性 seekTo()，
        // 彻底根除三星 One UI 独有的 SecFLACExtractor 线性插值丢失同步帧 (0xFFF8) 导致的解析失败！
        try {
            while (!isOutputEOS && (fullTrack || totalSamplesDecoded < targetSamples)) {
                if (!coroutineContext.isActive) {
                    Log.d(TAG, "Spectrogram generation cancelled by user")
                    return@withContext null
                }

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
                when {
                    outputBufferIndex >= 0 -> {
                        emptyCycles = 0
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                            when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val fb = outputBuffer.asFloatBuffer()
                                    val frames = fb.remaining() / channelCount
                                    for (f in 0 until frames) {
                                        var sum = 0f
                                        for (c in 0 until channelCount) {
                                            val v = fb.get()
                                            sum += if (v.isNaN() || v.isInfinite()) 0f else v
                                        }
                                        val mono = sum / channelCount
                                        if (!fullTrack) {
                                            if (totalSamplesDecoded < targetSamples) {
                                                sampleData[totalSamplesDecoded++] = mono
                                            }
                                        } else {
                                            ringBuffer[ringWritePos and ringMask] = mono
                                            ringWritePos++
                                            totalAudioSamplesSeen++
                                            if (totalAudioSamplesSeen >= nextFftTargetSample + fftSize && ringWritePos >= fftSize) {
                                                val startPos = ringWritePos - fftSize
                                                for (i in 0 until fftSize) {
                                                    re[i] = ringBuffer[(startPos + i) and ringMask] * hannWindow[i]
                                                    im[i] = 0f
                                                }
                                                fft(re, im)
                                                val mags = FloatArray(numBins)
                                                for (bin in 0 until numBins) {
                                                    val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                                                    mags[bin] = mag
                                                    sumMagnitudes[bin] += mag
                                                    sumPower[bin] += (mag * mag).toDouble()
                                                }
                                                magnitudeColumns.add(mags)
                                                nextFftTargetSample += hopStep
                                            }
                                        }
                                    }
                                }
                                21 /* AudioFormat.ENCODING_PCM_24BIT_PACKED */ -> {
                                    val frames = outputBuffer.remaining() / (3 * channelCount)
                                    for (f in 0 until frames) {
                                        var sum = 0f
                                        for (c in 0 until channelCount) {
                                            val b0 = outputBuffer.get().toInt() and 0xFF
                                            val b1 = outputBuffer.get().toInt() and 0xFF
                                            val b2 = outputBuffer.get().toInt()
                                            val s24 = (b2 shl 16) or (b1 shl 8) or b0
                                            sum += s24 / 8388608.0f
                                        }
                                        val mono = sum / channelCount
                                        if (!fullTrack) {
                                            if (totalSamplesDecoded < targetSamples) {
                                                sampleData[totalSamplesDecoded++] = mono
                                            }
                                        } else {
                                            ringBuffer[ringWritePos and ringMask] = mono
                                            ringWritePos++
                                            totalAudioSamplesSeen++
                                            if (totalAudioSamplesSeen >= nextFftTargetSample + fftSize && ringWritePos >= fftSize) {
                                                val startPos = ringWritePos - fftSize
                                                for (i in 0 until fftSize) {
                                                    re[i] = ringBuffer[(startPos + i) and ringMask] * hannWindow[i]
                                                    im[i] = 0f
                                                }
                                                fft(re, im)
                                                val mags = FloatArray(numBins)
                                                for (bin in 0 until numBins) {
                                                    val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                                                    mags[bin] = mag
                                                    sumMagnitudes[bin] += mag
                                                    sumPower[bin] += (mag * mag).toDouble()
                                                }
                                                magnitudeColumns.add(mags)
                                                nextFftTargetSample += hopStep
                                            }
                                        }
                                    }
                                }
                                22 /* AudioFormat.ENCODING_PCM_32BIT */ -> {
                                    val ib = outputBuffer.asIntBuffer()
                                    val frames = ib.remaining() / channelCount
                                    for (f in 0 until frames) {
                                        var sum = 0f
                                        for (c in 0 until channelCount) {
                                            sum += ib.get() / 2147483648.0f
                                        }
                                        val mono = sum / channelCount
                                        if (!fullTrack) {
                                            if (totalSamplesDecoded < targetSamples) {
                                                sampleData[totalSamplesDecoded++] = mono
                                            }
                                        } else {
                                            ringBuffer[ringWritePos and ringMask] = mono
                                            ringWritePos++
                                            totalAudioSamplesSeen++
                                            if (totalAudioSamplesSeen >= nextFftTargetSample + fftSize && ringWritePos >= fftSize) {
                                                val startPos = ringWritePos - fftSize
                                                for (i in 0 until fftSize) {
                                                    re[i] = ringBuffer[(startPos + i) and ringMask] * hannWindow[i]
                                                    im[i] = 0f
                                                }
                                                fft(re, im)
                                                val mags = FloatArray(numBins)
                                                for (bin in 0 until numBins) {
                                                    val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                                                    mags[bin] = mag
                                                    sumMagnitudes[bin] += mag
                                                    sumPower[bin] += (mag * mag).toDouble()
                                                }
                                                magnitudeColumns.add(mags)
                                                nextFftTargetSample += hopStep
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    val shortBuffer = outputBuffer.asShortBuffer()
                                    val frames = shortBuffer.remaining() / channelCount
                                    for (f in 0 until frames) {
                                        var sum = 0f
                                        for (c in 0 until channelCount) {
                                            sum += shortBuffer.get().toFloat() / 32768.0f
                                        }
                                        val mono = sum / channelCount
                                        if (!fullTrack) {
                                            if (totalSamplesDecoded < targetSamples) {
                                                sampleData[totalSamplesDecoded++] = mono
                                            }
                                        } else {
                                            ringBuffer[ringWritePos and ringMask] = mono
                                            ringWritePos++
                                            totalAudioSamplesSeen++
                                            if (totalAudioSamplesSeen >= nextFftTargetSample + fftSize && ringWritePos >= fftSize) {
                                                val startPos = ringWritePos - fftSize
                                                for (i in 0 until fftSize) {
                                                    re[i] = ringBuffer[(startPos + i) and ringMask] * hannWindow[i]
                                                    im[i] = 0f
                                                }
                                                fft(re, im)
                                                val mags = FloatArray(numBins)
                                                for (bin in 0 until numBins) {
                                                    val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                                                    mags[bin] = mag
                                                    sumMagnitudes[bin] += mag
                                                    sumPower[bin] += (mag * mag).toDouble()
                                                }
                                                magnitudeColumns.add(mags)
                                                nextFftTargetSample += hopStep
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEOS = true
                        }

                        if (fullTrack && durationUs > 0) {
                            val curUs = extractor.sampleTime
                            if (curUs > 0) {
                                val p = (curUs.toFloat() / durationUs).coerceIn(0f, 0.98f)
                                onProgress?.invoke(p)
                            }
                        }
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            val sr = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (sr > 0) sampleRate = sr
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            val cc = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (cc > 0) channelCount = cc
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (isInputEOS) {
                            emptyCycles++
                            if (emptyCycles > 40) isOutputEOS = true
                        }
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

        val numFrames: Int
        val magnitudeMatrix: Array<FloatArray>
        val analysisPower: DoubleArray

        if (!fullTrack) {
            var actualSamples = totalSamplesDecoded
            if (actualSamples < 2048) {
                Log.e(TAG, "Audio sample is too short to generate spectrogram (actualSamples=$actualSamples)")
                return@withContext null
            }

            // 跳过曲首静音片段（最多跳过前 3 秒）：让频谱图展示真实的音乐声学特征，而不是开头的静音
            val maxSkip = minOf(sampleRate * 3, actualSamples - 2048)
            var firstAudible = 0
            while (firstAudible < maxSkip && kotlin.math.abs(sampleData[firstAudible]) < 0.003f) {
                firstAudible++
            }
            if (firstAudible in 1 until maxSkip) {
                System.arraycopy(sampleData, firstAudible, sampleData, 0, actualSamples - firstAudible)
                actualSamples -= firstAudible
            }

            val hopSize = 1024
            numFrames = (actualSamples - fftSize) / hopSize
            if (numFrames <= 0) {
                Log.e(TAG, "Audio sample is too short to generate spectrogram")
                return@withContext null
            }

            magnitudeMatrix = Array(numFrames) { FloatArray(numBins) }
            val quickSumPower = DoubleArray(numBins)

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
                    quickSumPower[bin] += (mag * mag).toDouble()
                }
            }
            analysisPower = quickSumPower
        } else {
            numFrames = magnitudeColumns.size
            if (numFrames < 4) {
                Log.e(TAG, "Full track audio sample is too short to generate spectrogram (columns=$numFrames)")
                return@withContext null
            }
            magnitudeMatrix = magnitudeColumns.toTypedArray()
            analysisPower = sumPower
            onProgress?.invoke(1f)
        }

        // 频谱色阶（画频谱图用）
        val minDb = -100.0f
        val maxDb = 0.0f

        // ───────── 有损编码痕迹检测 ─────────
        val evidence = analyzeSpectrum(analysisPower, numFrames, sampleRate)
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
        return@withContext Result(bmp, cutoffHz, verdict, verdictDetails, isLossless, sampleRate, level, fullTrack, durationUs)
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
