package com.example.mymusic.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.example.mymusic.ThemeManager

// 对 ColorScheme 每个颜色字段做动画，切歌/切主题时平滑过渡
@Composable
private fun ColorScheme.animated(durationMs: Int = 600): ColorScheme {
    @Composable fun Color.anim() = animateColorAsState(this, tween(durationMs), label = "").value
    return this.copy(
        primary             = primary.anim(),
        onPrimary           = onPrimary.anim(),
        primaryContainer    = primaryContainer.anim(),
        onPrimaryContainer  = onPrimaryContainer.anim(),
        secondary           = secondary.anim(),
        onSecondary         = onSecondary.anim(),
        secondaryContainer  = secondaryContainer.anim(),
        onSecondaryContainer = onSecondaryContainer.anim(),
        tertiary            = tertiary.anim(),
        onTertiary          = onTertiary.anim(),
        tertiaryContainer   = tertiaryContainer.anim(),
        onTertiaryContainer = onTertiaryContainer.anim(),
        background          = background.anim(),
        onBackground        = onBackground.anim(),
        surface             = surface.anim(),
        onSurface           = onSurface.anim(),
        surfaceVariant      = surfaceVariant.anim(),
        onSurfaceVariant    = onSurfaceVariant.anim(),
        outline             = outline.anim(),
        outlineVariant      = outlineVariant.anim(),
        error               = error.anim(),
        onError             = onError.anim(),
    )
}

@Composable
fun AuralisTheme(content: @Composable () -> Unit) {
    val preset         by ThemeManager.preset.collectAsState()
    val artworkPrimary by ThemeManager.artworkPrimary.collectAsState()
    val customHue      by ThemeManager.customHue.collectAsState()
    val forceDark      by ThemeManager.forceDark.collectAsState()

    val systemDark = isSystemInDarkTheme()
    val isDark = forceDark ?: systemDark

    val colorScheme = ThemeManager
        .buildScheme(preset, artworkPrimary, customHue, isDark)
        .animated()

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
