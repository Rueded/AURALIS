package com.example.mymusic

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class AuralisPreset(val label: String) {
    DYNAMIC("专辑色"),
    OBSIDIAN("黑曜石"),
    MIDNIGHT("深海"),
    AMBER("琥珀"),
    ROSE("玫瑰"),
    JADE("翠玉")
}

object ThemeManager {

    private val _preset = MutableStateFlow(AuralisPreset.DYNAMIC)
    val preset: StateFlow<AuralisPreset> = _preset.asStateFlow()

    // 专辑封面提取出的主色（DYNAMIC 模式下使用）
    private val _artworkPrimary = MutableStateFlow(Color(0xFF80CBC4))
    val artworkPrimary: StateFlow<Color> = _artworkPrimary.asStateFlow()

    fun setPreset(preset: AuralisPreset, context: Context) {
        _preset.value = preset
        context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
            .edit().putString("theme_preset", preset.name).apply()
    }

    fun loadSavedPreset(context: Context) {
        val saved = context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
            .getString("theme_preset", AuralisPreset.DYNAMIC.name)
        _preset.value = AuralisPreset.entries.firstOrNull { it.name == saved } ?: AuralisPreset.DYNAMIC
    }

    // 从专辑封面 Bitmap 提取主色，切歌时调用
    suspend fun updateFromArtwork(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        try {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()

            // 按质量顺序取色：Vibrant > DarkVibrant > Muted > Dominant
            val argb = palette.getVibrantColor(0)
                .takeIf { it != 0 }
                ?: palette.getDarkVibrantColor(0).takeIf { it != 0 }
                ?: palette.getMutedColor(0).takeIf { it != 0 }
                ?: palette.getDominantColor(0xFF80CBC4.toInt())

            // 确保颜色足够亮，在深色背景上可辨认
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(argb, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.3f)   // 最低饱和度
            hsv[2] = hsv[2].coerceAtLeast(0.55f)   // 最低明度，太暗了看不清
            val boosted = android.graphics.Color.HSVToColor(hsv)

            _artworkPrimary.value = Color(boosted)
        } catch (_: Exception) {
            // 提取失败保持原色不变
        }
    }

    // 根据当前 preset + artworkPrimary 生成 ColorScheme
    fun buildScheme(preset: AuralisPreset, artworkPrimary: Color): ColorScheme = when (preset) {
        AuralisPreset.DYNAMIC -> buildDynamicScheme(artworkPrimary)
        AuralisPreset.OBSIDIAN -> obsidianScheme()
        AuralisPreset.MIDNIGHT -> midnightScheme()
        AuralisPreset.AMBER -> amberScheme()
        AuralisPreset.ROSE -> roseScheme()
        AuralisPreset.JADE -> jadeScheme()
    }

    // ── 专辑色：从提取主色推导整套配色 ──────────────────────────────────────
    private fun buildDynamicScheme(primary: Color): ColorScheme {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(primary.toArgb(), hsv)

        // 容器色：同色相，低饱和低明度
        val containerArgb = android.graphics.Color.HSVToColor(floatArrayOf(
            hsv[0], hsv[1] * 0.5f, (hsv[2] * 0.25f).coerceAtMost(0.22f)
        ))
        // 次级色：色相偏移 30°
        val secondaryArgb = android.graphics.Color.HSVToColor(floatArrayOf(
            (hsv[0] + 30f) % 360f, hsv[1] * 0.7f, hsv[2] * 0.8f
        ))
        // 深色背景：极低明度，同色相微染色
        val bgArgb = android.graphics.Color.HSVToColor(floatArrayOf(
            hsv[0], 0.08f, 0.06f
        ))
        val surfaceArgb = android.graphics.Color.HSVToColor(floatArrayOf(
            hsv[0], 0.07f, 0.10f
        ))

        return darkColorScheme(
            primary             = primary,
            onPrimary           = Color(0xFF0D0D0D),
            primaryContainer    = Color(containerArgb),
            onPrimaryContainer  = primary.copy(alpha = 0.9f),
            secondary           = Color(secondaryArgb),
            onSecondary         = Color(0xFF0D0D0D),
            secondaryContainer  = Color(containerArgb).copy(alpha = 0.7f),
            onSecondaryContainer = Color(secondaryArgb).copy(alpha = 0.9f),
            tertiary            = primary.copy(alpha = 0.6f),
            background          = Color(bgArgb),
            onBackground        = Color(0xFFEAEAEA),
            surface             = Color(surfaceArgb),
            onSurface           = Color(0xFFE0E0E0),
            surfaceVariant      = Color(surfaceArgb).copy(alpha = 0.7f),
            onSurfaceVariant    = Color(0xFFAAAAAA),
            outline             = Color(0xFF444444),
            outlineVariant      = Color(0xFF2A2A2A),
        )
    }

    // ── 黑曜石：近 AMOLED 纯黑，白色主色 ───────────────────────────────────
    private fun obsidianScheme() = darkColorScheme(
        primary             = Color(0xFFE2E2E2),
        onPrimary           = Color(0xFF0A0A0A),
        primaryContainer    = Color(0xFF1E1E1E),
        onPrimaryContainer  = Color(0xFFDDDDDD),
        secondary           = Color(0xFFAAAAAA),
        onSecondary         = Color(0xFF0A0A0A),
        secondaryContainer  = Color(0xFF181818),
        onSecondaryContainer = Color(0xFFCCCCCC),
        background          = Color(0xFF080808),
        onBackground        = Color(0xFFEEEEEE),
        surface             = Color(0xFF0F0F0F),
        onSurface           = Color(0xFFE8E8E8),
        surfaceVariant      = Color(0xFF161616),
        onSurfaceVariant    = Color(0xFF999999),
        outline             = Color(0xFF333333),
        outlineVariant      = Color(0xFF222222),
    )

    // ── 深海：深邃海军蓝 ────────────────────────────────────────────────────
    private fun midnightScheme() = darkColorScheme(
        primary             = Color(0xFF7EB8F7),
        onPrimary           = Color(0xFF003060),
        primaryContainer    = Color(0xFF0D2040),
        onPrimaryContainer  = Color(0xFFADD5FF),
        secondary           = Color(0xFF5B9BD5),
        onSecondary         = Color(0xFF002050),
        secondaryContainer  = Color(0xFF081830),
        onSecondaryContainer = Color(0xFF90C4EE),
        background          = Color(0xFF060C14),
        onBackground        = Color(0xFFD8E8F8),
        surface             = Color(0xFF0A1220),
        onSurface           = Color(0xFFCCDFF0),
        surfaceVariant      = Color(0xFF0E1A2C),
        onSurfaceVariant    = Color(0xFF8AAAC8),
        outline             = Color(0xFF1E3050),
        outlineVariant      = Color(0xFF121E30),
    )

    // ── 琥珀：暖金深棕 ──────────────────────────────────────────────────────
    private fun amberScheme() = darkColorScheme(
        primary             = Color(0xFFFFB74D),
        onPrimary           = Color(0xFF3A1C00),
        primaryContainer    = Color(0xFF2A1800),
        onPrimaryContainer  = Color(0xFFFFD699),
        secondary           = Color(0xFFDD9930),
        onSecondary         = Color(0xFF2A1200),
        secondaryContainer  = Color(0xFF1E1000),
        onSecondaryContainer = Color(0xFFEEBB70),
        background          = Color(0xFF0C0804),
        onBackground        = Color(0xFFF5E8D0),
        surface             = Color(0xFF121008),
        onSurface           = Color(0xFFEEDFBE),
        surfaceVariant      = Color(0xFF1A150A),
        onSurfaceVariant    = Color(0xFFBB9960),
        outline             = Color(0xFF4A3010),
        outlineVariant      = Color(0xFF2A1C08),
    )

    // ── 玫瑰：深玫红 ────────────────────────────────────────────────────────
    private fun roseScheme() = darkColorScheme(
        primary             = Color(0xFFF48FB1),
        onPrimary           = Color(0xFF3A0020),
        primaryContainer    = Color(0xFF280018),
        onPrimaryContainer  = Color(0xFFFFBBD4),
        secondary           = Color(0xFFCC6688),
        onSecondary         = Color(0xFF280018),
        secondaryContainer  = Color(0xFF1C0010),
        onSecondaryContainer = Color(0xFFEE99BB),
        background          = Color(0xFF0C0508),
        onBackground        = Color(0xFFF5DDE6),
        surface             = Color(0xFF130810),
        onSurface           = Color(0xFFEDD0DC),
        surfaceVariant      = Color(0xFF1A0C16),
        onSurfaceVariant    = Color(0xFFBB7799),
        outline             = Color(0xFF4A1830),
        outlineVariant      = Color(0xFF2A0C1C),
    )

    // ── 翠玉：深青绿 ────────────────────────────────────────────────────────
    private fun jadeScheme() = darkColorScheme(
        primary             = Color(0xFF80CBC4),
        onPrimary           = Color(0xFF003830),
        primaryContainer    = Color(0xFF062820),
        onPrimaryContainer  = Color(0xFFAAEDE6),
        secondary           = Color(0xFF4DB6AC),
        onSecondary         = Color(0xFF003028),
        secondaryContainer  = Color(0xFF042018),
        onSecondaryContainer = Color(0xFF88D5CE),
        background          = Color(0xFF040D0C),
        onBackground        = Color(0xFFD0EEEC),
        surface             = Color(0xFF081412),
        onSurface           = Color(0xFFC0E8E4),
        surfaceVariant      = Color(0xFF0C1C1A),
        onSurfaceVariant    = Color(0xFF80AAA8),
        outline             = Color(0xFF1A3C38),
        outlineVariant      = Color(0xFF0E2420),
    )
}
