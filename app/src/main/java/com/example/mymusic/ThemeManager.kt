package com.example.mymusic

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
    JADE("翠玉"),
    AURORA("极光"),
    VIOLET("烟紫"),
    SOLAR("正午"),
    DUNE("沙漠"),
    CUSTOM("自定义")
}

object ThemeManager {

    private val _preset = MutableStateFlow(AuralisPreset.DYNAMIC)
    val preset: StateFlow<AuralisPreset> = _preset.asStateFlow()

    // 专辑封面提取色（DYNAMIC 模式）
    private val _artworkPrimary = MutableStateFlow(Color(0xFF80CBC4))
    val artworkPrimary: StateFlow<Color> = _artworkPrimary.asStateFlow()

    // 自定义色相 0..360（CUSTOM 模式）
    private val _customHue = MutableStateFlow(200f)
    val customHue: StateFlow<Float> = _customHue.asStateFlow()

    // 深色 / 浅色模式（跟随系统，可被用户覆盖）
    private val _forceDark = MutableStateFlow<Boolean?>(null) // null = 跟系统
    val forceDark: StateFlow<Boolean?> = _forceDark.asStateFlow()

    fun setPreset(preset: AuralisPreset, context: Context) {
        _preset.value = preset
        context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
            .edit().putString("theme_preset", preset.name).apply()
    }

    fun setCustomHue(hue: Float, context: Context) {
        _customHue.value = hue.coerceIn(0f, 360f)
        _preset.value = AuralisPreset.CUSTOM
        context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
            .edit().putFloat("custom_hue", hue).putString("theme_preset", AuralisPreset.CUSTOM.name).apply()
    }

    fun setForceDark(dark: Boolean?, context: Context) {
        _forceDark.value = dark
        val pref = when (dark) { true -> "dark"; false -> "light"; null -> "system" }
        context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
            .edit().putString("force_dark", pref).apply()
    }

    fun loadSaved(context: Context) {
        val prefs = context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val savedPreset = prefs.getString("theme_preset", AuralisPreset.DYNAMIC.name)
        _preset.value = AuralisPreset.entries.firstOrNull { it.name == savedPreset } ?: AuralisPreset.DYNAMIC
        _customHue.value = prefs.getFloat("custom_hue", 200f)
        _forceDark.value = when (prefs.getString("force_dark", "system")) {
            "dark" -> true; "light" -> false; else -> null
        }
    }

    // 从专辑封面提取主色
    suspend fun updateFromArtwork(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        try {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val argb = palette.getVibrantColor(0).takeIf { it != 0 }
                ?: palette.getDarkVibrantColor(0).takeIf { it != 0 }
                ?: palette.getMutedColor(0).takeIf { it != 0 }
                ?: palette.getDominantColor(0xFF80CBC4.toInt())

            // 确保颜色在深色背景上足够可见
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(argb, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.35f)
            hsv[2] = hsv[2].coerceIn(0.5f, 0.95f)
            _artworkPrimary.value = Color(android.graphics.Color.HSVToColor(hsv))
        } catch (_: Exception) {}
    }

    // 根据 preset + isDark 构建 ColorScheme
    fun buildScheme(
        preset: AuralisPreset,
        artworkPrimary: Color,
        customHue: Float,
        isDark: Boolean
    ): ColorScheme = when (preset) {
        AuralisPreset.DYNAMIC -> if (isDark) dynamicDark(artworkPrimary) else dynamicLight(artworkPrimary)
        AuralisPreset.CUSTOM  -> if (isDark) hueToSchemeDark(customHue) else hueToSchemeLight(customHue)
        AuralisPreset.OBSIDIAN -> if (isDark) obsidianDark() else obsidianLight()
        AuralisPreset.MIDNIGHT -> if (isDark) midnightDark() else midnightLight()
        AuralisPreset.AMBER   -> if (isDark) amberDark() else amberLight()
        AuralisPreset.ROSE    -> if (isDark) roseDark() else roseLight()
        AuralisPreset.JADE    -> if (isDark) jadeDark() else jadeLight()
        AuralisPreset.AURORA  -> if (isDark) auroraDark() else auroraLight()
        AuralisPreset.VIOLET  -> if (isDark) violetDark() else violetLight()
        AuralisPreset.SOLAR   -> if (isDark) solarDark() else solarLight()
        AuralisPreset.DUNE    -> if (isDark) duneDark() else duneLight()
    }

    // 预览色（设置页配色卡片用）
    fun previewColor(preset: AuralisPreset, artworkPrimary: Color, customHue: Float): Color = when (preset) {
        AuralisPreset.DYNAMIC -> artworkPrimary
        AuralisPreset.CUSTOM  -> hueToColor(customHue)
        AuralisPreset.OBSIDIAN -> Color(0xFFE2E2E2)
        AuralisPreset.MIDNIGHT -> Color(0xFF7EB8F7)
        AuralisPreset.AMBER   -> Color(0xFFFFB74D)
        AuralisPreset.ROSE    -> Color(0xFFF48FB1)
        AuralisPreset.JADE    -> Color(0xFF80CBC4)
        AuralisPreset.AURORA  -> Color(0xFF69F0AE)
        AuralisPreset.VIOLET  -> Color(0xFFCE93D8)
        AuralisPreset.SOLAR   -> Color(0xFFFFF176)
        AuralisPreset.DUNE    -> Color(0xFFD7A96B)
    }

    private fun hueToColor(hue: Float): Color {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.85f))
        return Color(argb)
    }

    // ── 专辑色 ─────────────────────────────────────────────────────────────
    private fun dynamicDark(primary: Color): ColorScheme {
        val hsv = primary.toHsv()
        return darkColorScheme(
            primary             = primary,
            onPrimary           = Color(0xFF0D0D0D),
            primaryContainer    = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.5f, (hsv[2] * 0.22f).coerceAtMost(0.20f)))),
            onPrimaryContainer  = primary.copy(alpha = 0.9f),
            secondary           = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 30f) % 360f, hsv[1] * 0.65f, hsv[2] * 0.75f))),
            onSecondary         = Color(0xFF0D0D0D),
            secondaryContainer  = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.4f, 0.14f))),
            onSecondaryContainer = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 30f) % 360f, 0.5f, 0.85f))),
            tertiary            = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 60f) % 360f, hsv[1] * 0.6f, hsv[2] * 0.7f))),
            background          = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.08f, 0.06f))),
            onBackground        = Color(0xFFEAEAEA),
            surface             = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.07f, 0.10f))),
            onSurface           = Color(0xFFE0E0E0),
            surfaceVariant      = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.06f, 0.13f))),
            onSurfaceVariant    = Color(0xFFAAAAAA),
            outline             = Color(0xFF3A3A3A),
            outlineVariant      = Color(0xFF252525),
        )
    }

    private fun dynamicLight(primary: Color): ColorScheme {
        val hsv = primary.toHsv()
        val darkPrimary = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceAtLeast(0.5f), (hsv[2] * 0.6f).coerceAtMost(0.55f))))
        return lightColorScheme(
            primary             = darkPrimary,
            onPrimary           = Color.White,
            primaryContainer    = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.3f, 0.94f))),
            onPrimaryContainer  = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 0.25f))),
            secondary           = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 30f) % 360f, 0.45f, 0.45f))),
            onSecondary         = Color.White,
            secondaryContainer  = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 30f) % 360f, 0.2f, 0.95f))),
            onSecondaryContainer = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 30f) % 360f, 0.6f, 0.25f))),
            background          = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.03f, 0.98f))),
            onBackground        = Color(0xFF1A1A1A),
            surface             = Color.White,
            onSurface           = Color(0xFF1A1A1A),
            surfaceVariant      = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.06f, 0.94f))),
            onSurfaceVariant    = Color(0xFF555555),
            outline             = Color(0xFFBBBBBB),
            outlineVariant      = Color(0xFFDDDDDD),
        )
    }

    // ── 自定义色相 ──────────────────────────────────────────────────────────
    private fun hueToSchemeDark(hue: Float): ColorScheme {
        val primary = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.82f)))
        return dynamicDark(primary)
    }
    private fun hueToSchemeLight(hue: Float): ColorScheme {
        val primary = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.82f)))
        return dynamicLight(primary)
    }

    // ── 黑曜石 ─────────────────────────────────────────────────────────────
    private fun obsidianDark() = darkColorScheme(
        primary = Color(0xFFE2E2E2), onPrimary = Color(0xFF0A0A0A),
        primaryContainer = Color(0xFF1E1E1E), onPrimaryContainer = Color(0xFFDDDDDD),
        secondary = Color(0xFFAAAAAA), onSecondary = Color(0xFF0A0A0A),
        secondaryContainer = Color(0xFF181818), onSecondaryContainer = Color(0xFFCCCCCC),
        background = Color(0xFF080808), onBackground = Color(0xFFEEEEEE),
        surface = Color(0xFF0F0F0F), onSurface = Color(0xFFE8E8E8),
        surfaceVariant = Color(0xFF161616), onSurfaceVariant = Color(0xFF999999),
        outline = Color(0xFF333333), outlineVariant = Color(0xFF202020),
    )
    private fun obsidianLight() = lightColorScheme(
        primary = Color(0xFF2A2A2A), onPrimary = Color.White,
        primaryContainer = Color(0xFFEEEEEE), onPrimaryContainer = Color(0xFF1A1A1A),
        secondary = Color(0xFF555555), onSecondary = Color.White,
        secondaryContainer = Color(0xFFF5F5F5), onSecondaryContainer = Color(0xFF333333),
        background = Color(0xFFFAFAFA), onBackground = Color(0xFF1A1A1A),
        surface = Color.White, onSurface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF555555),
        outline = Color(0xFFCCCCCC), outlineVariant = Color(0xFFE5E5E5),
    )

    // ── 深海 ────────────────────────────────────────────────────────────────
    private fun midnightDark() = darkColorScheme(
        primary = Color(0xFF7EB8F7), onPrimary = Color(0xFF003060),
        primaryContainer = Color(0xFF0D2040), onPrimaryContainer = Color(0xFFADD5FF),
        secondary = Color(0xFF5B9BD5), onSecondary = Color(0xFF002050),
        secondaryContainer = Color(0xFF081830), onSecondaryContainer = Color(0xFF90C4EE),
        background = Color(0xFF060C14), onBackground = Color(0xFFD8E8F8),
        surface = Color(0xFF0A1220), onSurface = Color(0xFFCCDFF0),
        surfaceVariant = Color(0xFF0E1A2C), onSurfaceVariant = Color(0xFF8AAAC8),
        outline = Color(0xFF1E3050), outlineVariant = Color(0xFF121E30),
    )
    private fun midnightLight() = lightColorScheme(
        primary = Color(0xFF1A5FA8), onPrimary = Color.White,
        primaryContainer = Color(0xFFD4E8FF), onPrimaryContainer = Color(0xFF003060),
        secondary = Color(0xFF3A7BBB), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE0F0FF), onSecondaryContainer = Color(0xFF00346A),
        background = Color(0xFFF4F8FF), onBackground = Color(0xFF0D1A28),
        surface = Color.White, onSurface = Color(0xFF0D1A28),
        surfaceVariant = Color(0xFFE4EEF8), onSurfaceVariant = Color(0xFF3A5570),
        outline = Color(0xFFB0C8E0), outlineVariant = Color(0xFFD8E8F5),
    )

    // ── 琥珀 ────────────────────────────────────────────────────────────────
    private fun amberDark() = darkColorScheme(
        primary = Color(0xFFFFB74D), onPrimary = Color(0xFF3A1C00),
        primaryContainer = Color(0xFF2A1800), onPrimaryContainer = Color(0xFFFFD699),
        secondary = Color(0xFFDD9930), onSecondary = Color(0xFF2A1200),
        secondaryContainer = Color(0xFF1E1000), onSecondaryContainer = Color(0xFFEEBB70),
        background = Color(0xFF0C0804), onBackground = Color(0xFFF5E8D0),
        surface = Color(0xFF121008), onSurface = Color(0xFFEEDFBE),
        surfaceVariant = Color(0xFF1A150A), onSurfaceVariant = Color(0xFFBB9960),
        outline = Color(0xFF4A3010), outlineVariant = Color(0xFF2A1C08),
    )
    private fun amberLight() = lightColorScheme(
        primary = Color(0xFFB56800), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFE0AA), onPrimaryContainer = Color(0xFF3A1C00),
        secondary = Color(0xFF9C6800), onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFEECC), onSecondaryContainer = Color(0xFF2A1800),
        background = Color(0xFFFFF8F0), onBackground = Color(0xFF1E1200),
        surface = Color.White, onSurface = Color(0xFF1E1200),
        surfaceVariant = Color(0xFFFFF0DA), onSurfaceVariant = Color(0xFF5A3A00),
        outline = Color(0xFFDDAA50), outlineVariant = Color(0xFFFFDDA0),
    )

    // ── 玫瑰 ────────────────────────────────────────────────────────────────
    private fun roseDark() = darkColorScheme(
        primary = Color(0xFFF48FB1), onPrimary = Color(0xFF3A0020),
        primaryContainer = Color(0xFF280018), onPrimaryContainer = Color(0xFFFFBBD4),
        secondary = Color(0xFFCC6688), onSecondary = Color(0xFF280018),
        secondaryContainer = Color(0xFF1C0010), onSecondaryContainer = Color(0xFFEE99BB),
        background = Color(0xFF0C0508), onBackground = Color(0xFFF5DDE6),
        surface = Color(0xFF130810), onSurface = Color(0xFFEDD0DC),
        surfaceVariant = Color(0xFF1A0C16), onSurfaceVariant = Color(0xFFBB7799),
        outline = Color(0xFF4A1830), outlineVariant = Color(0xFF2A0C1C),
    )
    private fun roseLight() = lightColorScheme(
        primary = Color(0xFFAA2255), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD8E6), onPrimaryContainer = Color(0xFF3A0020),
        secondary = Color(0xFF992244), onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFE8F0), onSecondaryContainer = Color(0xFF280018),
        background = Color(0xFFFFF5F8), onBackground = Color(0xFF200010),
        surface = Color.White, onSurface = Color(0xFF200010),
        surfaceVariant = Color(0xFFFFEAF2), onSurfaceVariant = Color(0xFF6A2040),
        outline = Color(0xFFE0A0BB), outlineVariant = Color(0xFFFFCCDD),
    )

    // ── 翠玉 ────────────────────────────────────────────────────────────────
    private fun jadeDark() = darkColorScheme(
        primary = Color(0xFF80CBC4), onPrimary = Color(0xFF003830),
        primaryContainer = Color(0xFF062820), onPrimaryContainer = Color(0xFFAAEDE6),
        secondary = Color(0xFF4DB6AC), onSecondary = Color(0xFF003028),
        secondaryContainer = Color(0xFF042018), onSecondaryContainer = Color(0xFF88D5CE),
        background = Color(0xFF040D0C), onBackground = Color(0xFFD0EEEC),
        surface = Color(0xFF081412), onSurface = Color(0xFFC0E8E4),
        surfaceVariant = Color(0xFF0C1C1A), onSurfaceVariant = Color(0xFF80AAA8),
        outline = Color(0xFF1A3C38), outlineVariant = Color(0xFF0E2420),
    )
    private fun jadeLight() = lightColorScheme(
        primary = Color(0xFF00766A), onPrimary = Color.White,
        primaryContainer = Color(0xFFBCEDE8), onPrimaryContainer = Color(0xFF003830),
        secondary = Color(0xFF00695C), onSecondary = Color.White,
        secondaryContainer = Color(0xFFCCF5F0), onSecondaryContainer = Color(0xFF003028),
        background = Color(0xFFF2FDFB), onBackground = Color(0xFF041412),
        surface = Color.White, onSurface = Color(0xFF041412),
        surfaceVariant = Color(0xFFE0F5F3), onSurfaceVariant = Color(0xFF2A5550),
        outline = Color(0xFF9ACCC8), outlineVariant = Color(0xFFCCEEEB),
    )

    // ── 极光 ────────────────────────────────────────────────────────────────
    private fun auroraDark() = darkColorScheme(
        primary = Color(0xFF69F0AE), onPrimary = Color(0xFF003820),
        primaryContainer = Color(0xFF052818), onPrimaryContainer = Color(0xFFAAFFCC),
        secondary = Color(0xFF40C4AA), onSecondary = Color(0xFF003028),
        secondaryContainer = Color(0xFF042018), onSecondaryContainer = Color(0xFF80EED5),
        background = Color(0xFF04100A), onBackground = Color(0xFFD0F5E5),
        surface = Color(0xFF081810), onSurface = Color(0xFFC0F0DA),
        surfaceVariant = Color(0xFF0C2018), onSurfaceVariant = Color(0xFF70C0A0),
        outline = Color(0xFF184030), outlineVariant = Color(0xFF0C2818),
    )
    private fun auroraLight() = lightColorScheme(
        primary = Color(0xFF007A45), onPrimary = Color.White,
        primaryContainer = Color(0xFFBBF5DA), onPrimaryContainer = Color(0xFF003820),
        secondary = Color(0xFF006B55), onSecondary = Color.White,
        secondaryContainer = Color(0xFFCCF8EE), onSecondaryContainer = Color(0xFF003028),
        background = Color(0xFFF2FFF8), onBackground = Color(0xFF04180C),
        surface = Color.White, onSurface = Color(0xFF04180C),
        surfaceVariant = Color(0xFFDEF5EB), onSurfaceVariant = Color(0xFF205A40),
        outline = Color(0xFF9AD5BB), outlineVariant = Color(0xFFCCEEDC),
    )

    // ── 烟紫 ────────────────────────────────────────────────────────────────
    private fun violetDark() = darkColorScheme(
        primary = Color(0xFFCE93D8), onPrimary = Color(0xFF2A0038),
        primaryContainer = Color(0xFF1E0028), onPrimaryContainer = Color(0xFFEABBF5),
        secondary = Color(0xFFAB67B8), onSecondary = Color(0xFF1E0028),
        secondaryContainer = Color(0xFF16001C), onSecondaryContainer = Color(0xFFCC99DD),
        background = Color(0xFF0A060C), onBackground = Color(0xFFEEDDF5),
        surface = Color(0xFF110A14), onSurface = Color(0xFFE5D0EE),
        surfaceVariant = Color(0xFF18101C), onSurfaceVariant = Color(0xFFAA88BB),
        outline = Color(0xFF3A1848), outlineVariant = Color(0xFF220C2C),
    )
    private fun violetLight() = lightColorScheme(
        primary = Color(0xFF7B1FA2), onPrimary = Color.White,
        primaryContainer = Color(0xFFEDD8FF), onPrimaryContainer = Color(0xFF2A0038),
        secondary = Color(0xFF6A1B8A), onSecondary = Color.White,
        secondaryContainer = Color(0xFFF3E5FF), onSecondaryContainer = Color(0xFF1E0028),
        background = Color(0xFFFDF5FF), onBackground = Color(0xFF180820),
        surface = Color.White, onSurface = Color(0xFF180820),
        surfaceVariant = Color(0xFFF5E8FF), onSurfaceVariant = Color(0xFF5A1870),
        outline = Color(0xFFCCA0DD), outlineVariant = Color(0xFFEAD0FF),
    )

    // ── 正午 ────────────────────────────────────────────────────────────────
    private fun solarDark() = darkColorScheme(
        primary = Color(0xFFFFF176), onPrimary = Color(0xFF2A2000),
        primaryContainer = Color(0xFF201800), onPrimaryContainer = Color(0xFFFFEDAA),
        secondary = Color(0xFFDDCC44), onSecondary = Color(0xFF201800),
        secondaryContainer = Color(0xFF181200), onSecondaryContainer = Color(0xFFFFDD88),
        background = Color(0xFF0C0C04), onBackground = Color(0xFFF5F0D0),
        surface = Color(0xFF141408), onSurface = Color(0xFFEEE8C0),
        surfaceVariant = Color(0xFF1C1C0C), onSurfaceVariant = Color(0xFFBBB060),
        outline = Color(0xFF404010), outlineVariant = Color(0xFF282808),
    )
    private fun solarLight() = lightColorScheme(
        primary = Color(0xFF7A6800), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFF5AA), onPrimaryContainer = Color(0xFF2A2000),
        secondary = Color(0xFF6A5C00), onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFF0CC), onSecondaryContainer = Color(0xFF201800),
        background = Color(0xFFFFFFF0), onBackground = Color(0xFF1A1800),
        surface = Color.White, onSurface = Color(0xFF1A1800),
        surfaceVariant = Color(0xFFFFFBE0), onSurfaceVariant = Color(0xFF5A5000),
        outline = Color(0xFFDDD060), outlineVariant = Color(0xFFFFEEAA),
    )

    // ── 沙漠 ────────────────────────────────────────────────────────────────
    private fun duneDark() = darkColorScheme(
        primary = Color(0xFFD7A96B), onPrimary = Color(0xFF301800),
        primaryContainer = Color(0xFF241200), onPrimaryContainer = Color(0xFFEFCC9A),
        secondary = Color(0xFFBB8848), onSecondary = Color(0xFF221000),
        secondaryContainer = Color(0xFF1A0C00), onSecondaryContainer = Color(0xFFDDAA70),
        background = Color(0xFF0E0A06), onBackground = Color(0xFFF2EAD8),
        surface = Color(0xFF16100A), onSurface = Color(0xFFE8DCC8),
        surfaceVariant = Color(0xFF201810), onSurfaceVariant = Color(0xFFBB9966),
        outline = Color(0xFF4A3018), outlineVariant = Color(0xFF2E1E0C),
    )
    private fun duneLight() = lightColorScheme(
        primary = Color(0xFF8B5A00), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDFAA), onPrimaryContainer = Color(0xFF301800),
        secondary = Color(0xFF7A5000), onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFEECC), onSecondaryContainer = Color(0xFF221000),
        background = Color(0xFFFFF8F0), onBackground = Color(0xFF1E1200),
        surface = Color.White, onSurface = Color(0xFF1E1200),
        surfaceVariant = Color(0xFFFFF0DC), onSurfaceVariant = Color(0xFF6A4010),
        outline = Color(0xFFDDBB80), outlineVariant = Color(0xFFFFDDAA),
    )

    // ── 工具 ─────────────────────────────────────────────────────────────────
    private fun Color.toHsv(): FloatArray {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this.toArgb(), hsv)
        return hsv
    }
}
