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
 *
 * 首页和全屏播放器都从这里 collectAsState()，不再各自维护独立的 dominantColor。
 * 只要封面来源（本地/网络）更新，调一次 updateBitmap() 即可全局同步。
 *
 * 使用方式（Compose 侧）：
 *   val dominant by PlayerStateHolder.dominantColor.collectAsState()
 *   val cover   by PlayerStateHolder.coverBitmap.collectAsState()
 */
object PlayerStateHolder {

    // ── 封面 Bitmap（ImageBitmap 供 Compose Image 直接使用）──────────────────
    private val _coverBitmap = MutableStateFlow<ImageBitmap?>(null)
    val coverBitmap: StateFlow<ImageBitmap?> = _coverBitmap.asStateFlow()

    // ── 从封面提取的主色（没有封面时为 null，UI 侧用 colorScheme.primaryContainer 兜底）
    private val _dominantColor = MutableStateFlow<Color?>(null)
    val dominantColor: StateFlow<Color?> = _dominantColor.asStateFlow()

    // ── 当前正在显示封面的音频路径（用于防止跨曲竞态）────────────────────────
    @Volatile
    private var _currentPath: String = ""
    val currentPath: String get() = _currentPath

    // ─────────────────────────────────────────────────────────────────────────
    // 从 ByteArray（MediaMetadata.artworkData）更新
    // 在 onMediaMetadataChanged 里调用
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun updateFromArtworkBytes(bytes: ByteArray?, audioPath: String) = withContext(Dispatchers.IO) {
        _currentPath = audioPath
        if (bytes == null) {
            // 🚨 核心防御：如果 ExoPlayer 传来 null，千万不要 clear！
            // 否则会把爬虫刚下载好的网络封面瞬间抹杀，导致主题色变成黑屏！
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
        _currentPath = audioPath // 🚨 强行同步路径，不再拒绝网络封面的更新
        applyBitmap(bitmap, audioPath)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 清除（切歌/停止时调用，可选）
    // ─────────────────────────────────────────────────────────────────────────
    fun clear() {
        _currentPath = ""
        _coverBitmap.value = null
        _dominantColor.value = null
    }

    // ── 内部：提取颜色 + 更新 Flow ─────────────────────────────────────────
    private suspend fun applyBitmap(bitmap: Bitmap, audioPath: String) {
        // 再次校验：如果提取颜色期间已经切歌，直接丢弃
        if (audioPath != _currentPath) return

        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val rgb = palette.vibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb

        // 确保颜色足够饱和
        val color: Color? = rgb?.let {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(it, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.30f)
            hsv[2] = hsv[2].coerceIn(0.45f, 0.95f)
            Color(android.graphics.Color.HSVToColor(hsv))
        }

        withContext(Dispatchers.Main) {
            if (audioPath == _currentPath) {               // 最后一道竞态防线
                _coverBitmap.value = bitmap.asImageBitmap()
                _dominantColor.value = color
                // 同步通知 ThemeManager，保持主题配色也跟网络封面联动
                ThemeManager.updateFromArtwork(bitmap)
            }
        }
    }
}
