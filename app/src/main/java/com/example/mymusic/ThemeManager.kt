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
    CUSTOM("自定义")
}

object ThemeManager {

    private val _preset = MutableStateFlow(AuralisPreset.DYNAMIC)
    val preset: StateFlow<AuralisPreset> = _preset.asStateFlow()

    private val _artworkPrimary = MutableStateFlow(Color(0xFF80CBC4))
    val artworkPrimary: StateFlow<Color> = _artworkPrimary.asStateFlow()

    private val _customHue = MutableStateFlow(200f)
    val customHue: StateFlow<Float> = _customHue.asStateFlow()

    private val _forceDark = MutableStateFlow<Boolean?>(null)
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

    suspend fun updateFromArtwork(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        try {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val argb = palette.getVibrantColor(0).takeIf { it != 0 }
                ?: palette.getDarkVibrantColor(0).takeIf { it != 0 }
                ?: palette.getMutedColor(0).takeIf { it != 0 }
                ?: palette.getDominantColor(0xFF80CBC4.toInt())

            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(argb, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.35f)
            hsv[2] = hsv[2].coerceIn(0.5f, 0.95f)
            _artworkPrimary.value = Color(android.graphics.Color.HSVToColor(hsv))
        } catch (_: Exception) {}
    }

    fun getHue(color: Color): Float {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        return hsv[0]
    }

    fun buildScheme(preset: AuralisPreset, artworkPrimary: Color, customHue: Float, isDark: Boolean): ColorScheme = when (preset) {
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
    }

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
    }

    private fun hueToColor(hue: Float): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.85f)))

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
            background          = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.08f, 0.06f))),
            surface             = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.07f, 0.10f))),
            surfaceVariant      = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.06f, 0.13f))),
        )
    }

    private fun dynamicLight(primary: Color): ColorScheme {
        val hsv = primary.toHsv()
        val darkPrimary = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceAtLeast(0.5f), (hsv[2] * 0.6f).coerceAtMost(0.55f))))
        return lightColorScheme(
            primary             = darkPrimary,
            primaryContainer    = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.3f, 0.94f))),
            secondary           = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 30f) % 360f, 0.45f, 0.45f))),
            secondaryContainer  = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 30f) % 360f, 0.2f, 0.95f))),
            background          = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.03f, 0.98f))),
            surface             = Color.White,
            surfaceVariant      = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.06f, 0.94f))),
        )
    }

    private fun hueToSchemeDark(hue: Float): ColorScheme = dynamicDark(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.82f))))
    private fun hueToSchemeLight(hue: Float): ColorScheme = dynamicLight(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.82f))))

    private fun obsidianDark() = darkColorScheme(primary = Color(0xFFE2E2E2), primaryContainer = Color(0xFF1E1E1E), secondary = Color(0xFFAAAAAA), background = Color(0xFF080808), surface = Color(0xFF0F0F0F), surfaceVariant = Color(0xFF161616))
    private fun obsidianLight() = lightColorScheme(primary = Color(0xFF2A2A2A), primaryContainer = Color(0xFFEEEEEE), secondary = Color(0xFF555555), background = Color(0xFFFAFAFA), surface = Color.White, surfaceVariant = Color(0xFFF0F0F0))
    private fun midnightDark() = darkColorScheme(primary = Color(0xFF7EB8F7), primaryContainer = Color(0xFF0D2040), secondary = Color(0xFF5B9BD5), background = Color(0xFF060C14), surface = Color(0xFF0A1220), surfaceVariant = Color(0xFF0E1A2C))
    private fun midnightLight() = lightColorScheme(primary = Color(0xFF1A5FA8), primaryContainer = Color(0xFFD4E8FF), secondary = Color(0xFF3A7BBB), background = Color(0xFFF4F8FF), surface = Color.White, surfaceVariant = Color(0xFFE4EEF8))
    private fun amberDark() = darkColorScheme(primary = Color(0xFFFFB74D), primaryContainer = Color(0xFF2A1800), secondary = Color(0xFFDD9930), background = Color(0xFF0C0804), surface = Color(0xFF121008), surfaceVariant = Color(0xFF1A150A))
    private fun amberLight() = lightColorScheme(primary = Color(0xFFB56800), primaryContainer = Color(0xFFFFE0AA), secondary = Color(0xFF9C6800), background = Color(0xFFFFF8F0), surface = Color.White, surfaceVariant = Color(0xFFFFF0DA))
    private fun roseDark() = darkColorScheme(primary = Color(0xFFF48FB1), primaryContainer = Color(0xFF280018), secondary = Color(0xFFCC6688), background = Color(0xFF0C0508), surface = Color(0xFF130810), surfaceVariant = Color(0xFF1A0C16))
    private fun roseLight() = lightColorScheme(primary = Color(0xFFAA2255), primaryContainer = Color(0xFFFFD8E6), secondary = Color(0xFF992244), background = Color(0xFFFFF5F8), surface = Color.White, surfaceVariant = Color(0xFFFFEAF2))
    private fun jadeDark() = darkColorScheme(primary = Color(0xFF80CBC4), primaryContainer = Color(0xFF062820), secondary = Color(0xFF4DB6AC), background = Color(0xFF040D0C), surface = Color(0xFF081412), surfaceVariant = Color(0xFF0C1C1A))
    private fun jadeLight() = lightColorScheme(primary = Color(0xFF00766A), primaryContainer = Color(0xFFBCEDE8), secondary = Color(0xFF00695C), background = Color(0xFFF2FDFB), surface = Color.White, surfaceVariant = Color(0xFFE0F5F3))
    private fun auroraDark() = darkColorScheme(primary = Color(0xFF69F0AE), primaryContainer = Color(0xFF052818), secondary = Color(0xFF40C4AA), background = Color(0xFF04100A), surface = Color(0xFF081810), surfaceVariant = Color(0xFF0C2018))
    private fun auroraLight() = lightColorScheme(primary = Color(0xFF007A45), primaryContainer = Color(0xFFBBF5DA), secondary = Color(0xFF006B55), background = Color(0xFFF2FFF8), surface = Color.White, surfaceVariant = Color(0xFFDEF5EB))
    private fun violetDark() = darkColorScheme(primary = Color(0xFFCE93D8), primaryContainer = Color(0xFF1E0028), secondary = Color(0xFFAB67B8), background = Color(0xFF0A060C), surface = Color(0xFF110A14), surfaceVariant = Color(0xFF18101C))
    private fun violetLight() = lightColorScheme(primary = Color(0xFF7B1FA2), primaryContainer = Color(0xFFEDD8FF), secondary = Color(0xFF6A1B8A), background = Color(0xFFFDF5FF), surface = Color.White, surfaceVariant = Color(0xFFF5E8FF))
    private fun solarDark() = darkColorScheme(primary = Color(0xFFFFF176), primaryContainer = Color(0xFF201800), secondary = Color(0xFFDDCC44), background = Color(0xFF0C0C04), surface = Color(0xFF141408), surfaceVariant = Color(0xFF1C1C0C))
    private fun solarLight() = lightColorScheme(primary = Color(0xFF7A6800), primaryContainer = Color(0xFFFFF5AA), secondary = Color(0xFF6A5C00), background = Color(0xFFFFFFF0), surface = Color.White, surfaceVariant = Color(0xFFFFFBE0))

    private fun Color.toHsv(): FloatArray {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this.toArgb(), hsv)
        return hsv
    }
}