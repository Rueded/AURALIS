package com.auralis.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 提取出的专辑调色盘：包含主色、辅助色、背景装饰色。
 */
data class AlbumPalette(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val accent: Color
)

/**
 * 单一状态源：当前正在播放的封面 Bitmap + 提取出的主色调色盘。
 */
object PlayerStateHolder {

    private val _coverBitmap = MutableStateFlow<ImageBitmap?>(null)
    val coverBitmap: StateFlow<ImageBitmap?> = _coverBitmap.asStateFlow()

    private val _dominantColor = MutableStateFlow<Color?>(null)
    val dominantColor: StateFlow<Color?> = _dominantColor.asStateFlow()

    private val _albumPalette = MutableStateFlow<AlbumPalette?>(null)
    val albumPalette: StateFlow<AlbumPalette?> = _albumPalette.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPathState: StateFlow<String> = _currentPath.asStateFlow()

    private val _openPlayerRequest = MutableStateFlow(false)
    val openPlayerRequest: StateFlow<Boolean> = _openPlayerRequest.asStateFlow()

    fun requestOpenPlayer() {
        _openPlayerRequest.value = true
    }

    fun consumeOpenPlayerRequest() {
        _openPlayerRequest.value = false
    }

    @Volatile
    private var loadGeneration = 0

    val currentPath: String get() = _currentPath.value

    private fun setCurrentPath(path: String) {
        _currentPath.value = path
    }

    suspend fun updateFromArtworkBytes(bytes: ByteArray?, audioPath: String) = withContext(Dispatchers.IO) {
        setCurrentPath(audioPath)
        if (bytes == null) {
            // ExoPlayer 无内嵌图时不要 clear，保留磁盘/网络封面与主题色
            return@withContext
        }
        try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext
            applyBitmap(bmp, audioPath)
        } catch (e: Exception) {
            android.util.Log.e("PlayerStateHolder", "updateFromArtworkBytes 失败", e)
        }
    }

    suspend fun updateFromBitmap(bitmap: Bitmap, audioPath: String) = withContext(Dispatchers.IO) {
        setCurrentPath(audioPath)
        applyBitmap(bitmap, audioPath)
    }

    fun clear() {
        loadGeneration++
        setCurrentPath("")
        _coverBitmap.value = null
        _dominantColor.value = null
        _albumPalette.value = null
    }

    /** 切歌时调用：仅更新路径并递增代数，清空旧的状态避免颜色闪烁/串色 */
    fun onTrackChanged(audioPath: String) {
        loadGeneration++
        setCurrentPath(audioPath)
        _coverBitmap.value = null
        _dominantColor.value = null
        _albumPalette.value = null
    }

    private suspend fun applyBitmap(bitmap: Bitmap, audioPath: String) {
        val gen = loadGeneration
        if (audioPath != _currentPath.value) return

        // 👇 核心修复 1：统一色彩提取的取样基准！
        // 抹平外部列表低清图(565)和内部全屏高清图(8888)之间的细微解析差异，保证内外提取绝对一致
        val scaledBitmap = if (bitmap.width > 400 || bitmap.height > 400) {
            // 不使用 filter 抗锯齿以节省性能，仅作色彩提取用
            Bitmap.createScaledBitmap(bitmap, 300, 300 * bitmap.height / bitmap.width, false)
        } else bitmap

        val palette = Palette.from(scaledBitmap).maximumColorCount(32).generate()

        // 👇 核心修复 2：智能主色提取算法！平衡“大面积”与“鲜艳度”
        var bestSwatch: Palette.Swatch? = palette.dominantSwatch
        var maxScore = 0f

        palette.swatches.forEach { swatch ->
            val hsl = swatch.hsl
            val s = hsl[1] // 饱和度 0~1
            val l = hsl[2] // 亮度 0~1
            val pop = swatch.population.toFloat() // 该颜色的像素面积

            // 过滤掉极度暗淡或几乎纯灰白的颜色（除非没得选）
            if (l < 0.1f || l > 0.9f || (s < 0.15f && l < 0.25f)) return@forEach

            // 🌟 独家评分公式：面积是基石，鲜艳度给予适度加成。
            // 极其鲜艳的颜色最多只能获得 2.5 倍的面积加成，
            // 这样一来，占 5% 面积的红点，得分绝对不可能打败占 80% 面积的蓝色或米色！
            val satMultiplier = 1f + (s * 1.5f)
            val score = pop * satMultiplier

            if (score > maxScore) {
                maxScore = score
                bestSwatch = swatch
            }
        }

        // 如果经过上述严苛筛选还是没选出来，回退到系统公认面积最大的 dominantSwatch
        val primaryRgb = bestSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: 0xFF80CBC4.toInt()

        // 2. 提取更多维度的辅助色
        val secondaryRgb = palette.lightVibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.getVibrantColor(primaryRgb)

        val backgroundRgb = palette.darkMutedSwatch?.rgb
            ?: palette.darkVibrantSwatch?.rgb
            ?: palette.getDominantColor(primaryRgb)

        val accentRgb = palette.getLightMutedColor(secondaryRgb)

        // 3. 智能 HSV 约束逻辑：增强饱和度平衡
        fun adjustColor(rgb: Int, minSat: Float = 0.2f, minVal: Float = 0.4f, maxVal: Float = 0.95f, isBackground: Boolean = false): Color {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(rgb, hsv)

            if (isBackground) {
                hsv[2] = hsv[2].coerceAtMost(0.2f)
            } else {
                // 如果原色饱和度太低，进行适度拉升
                if (hsv[1] < minSat) hsv[1] = (hsv[1] + minSat) / 2f
                // 亮度过暗或过亮时进行微调
                hsv[2] = hsv[2].coerceIn(minVal, maxVal)
            }
            return Color(android.graphics.Color.HSVToColor(hsv))
        }

        val primaryColor = adjustColor(primaryRgb, 0.35f, 0.5f, 0.9f)
        val albumPaletteObj = AlbumPalette(
            primary = primaryColor,
            secondary = adjustColor(secondaryRgb, 0.25f, 0.4f, 0.85f),
            background = adjustColor(backgroundRgb, isBackground = true),
            accent = adjustColor(accentRgb, 0.3f, 0.6f, 0.95f)
        )

        withContext(Dispatchers.Main) {
            if (gen == loadGeneration && audioPath == _currentPath.value) {
                // ⚠️ 重点：虽然提取用的是缩小图，但显示回传给 UI 的依然是最原始的高清 bitmap！
                _coverBitmap.value = bitmap.asImageBitmap()
                _dominantColor.value = primaryColor
                _albumPalette.value = albumPaletteObj
                ThemeManager.updateFromPalette(albumPaletteObj)
            }
        }
    }
}
