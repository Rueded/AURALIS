package com.auralis.app

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
        val isLossless: Boolean
    )

    suspend fun generate(audioPath: String): Result? = withContext(Dispatchers.IO) {
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
            }
        }

        // Calculate average dB for each bin to detect cutoff
        // Detect highest bin with average dB > -65.0 dB
        val minDb = -100.0f
        val maxDb = 0.0f
        val activeThresholdDb = -65.0f
        var highestActiveBin = -1

        for (bin in 0 until numBins) {
            val avgMag = sumMagnitudes[bin] / numFrames
            val avgDb = 20.0f * log10(avgMag + 1e-8f)
            if (avgDb > activeThresholdDb) {
                highestActiveBin = bin
            }
        }

        val nyquistHz = sampleRate / 2.0f
        val cutoffHz = if (highestActiveBin != -1) {
            highestActiveBin.toFloat() * nyquistHz / numBins
        } else {
            0.0f
        }

        // Programmatic fake-FLAC audit
        val verdict: String
        val verdictDetails: String
        val isLossless: Boolean

        when {
            cutoffHz >= 20000.0f -> {
                verdict = "🌟 纯正高清母带 / 真无损 (True Lossless)"
                verdictDetails = "高频上限达到 ${String.format("%.1f", cutoffHz / 1000.0f)} kHz，声学特征完全符合高保真/真无损规格。"
                isLossless = true
            }
            cutoffHz >= 18000.0f -> {
                verdict = "⚠️ 疑似 MP3-320kbps 转制 (Upscaled Lossy)"
                verdictDetails = "高频上限在 ${String.format("%.1f", cutoffHz / 1000.0f)} kHz 处出现衰减，表现出典型的 320kbps 高频截断特征。"
                isLossless = false
            }
            else -> {
                verdict = "❌ 极高嫌疑高频欺诈 / MP3-128kbps 转制 (Fake Lossless)"
                verdictDetails = "高频上限严重锁死在 ${String.format("%.1f", cutoffHz / 1000.0f)} kHz，有极高嫌疑是低码率 MP3 强行扩容或假无损。"
                isLossless = false
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
        return@withContext Result(bmp, cutoffHz, verdict, verdictDetails, isLossless)
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
