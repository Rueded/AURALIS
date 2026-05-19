package com.example.mymusic.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import com.example.mymusic.ThemeManager

@Composable
fun AuralisTheme(content: @Composable () -> Unit) {
    val preset by ThemeManager.preset.collectAsState()
    val artworkPrimary by ThemeManager.artworkPrimary.collectAsState()

    val colorScheme = ThemeManager.buildScheme(preset, artworkPrimary)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
