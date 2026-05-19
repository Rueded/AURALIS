package com.example.mymusic

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
 * 单一状态源：当前正在播放的封面 Bitmap + 提取出的主色。
 */
object PlayerStateHolder {

    private val _coverBitmap = MutableStateFlow<ImageBitmap?>(null)
    val coverBitmap: StateFlow<ImageBitmap?> = _coverBitmap.asStateFlow()

    private val _dominantColor = MutableStateFlow<Color?>(null)
    val dominantColor: StateFlow<Color?> = _dominantColor.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPathState: StateFlow<String> = _currentPath.asStateFlow()

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
    }

    /** 切歌时调用：仅更新路径并递增代数，不立刻清空封面（避免闪烁/主题丢失） */
    fun onTrackChanged(audioPath: String) {
        loadGeneration++
        setCurrentPath(audioPath)
    }

    private suspend fun applyBitmap(bitmap: Bitmap, audioPath: String) {
        val gen = loadGeneration
        if (audioPath != _currentPath.value) return

        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val rgb = palette.vibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb

        val color: Color? = rgb?.let {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(it, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.30f)
            hsv[2] = hsv[2].coerceIn(0.45f, 0.95f)
            Color(android.graphics.Color.HSVToColor(hsv))
        }

        withContext(Dispatchers.Main) {
            if (gen == loadGeneration && audioPath == _currentPath.value) {
                _coverBitmap.value = bitmap.asImageBitmap()
                _dominantColor.value = color
                ThemeManager.updateFromArtwork(bitmap)
            }
        }
    }
}
