package com.stockpile.volume.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1F4E5F),
    onPrimary = Color.White,
    secondary = Color(0xFFC4842F),
    tertiary = Color(0xFF2E7D32),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8CC7DA),
    secondary = Color(0xFFE0A44A),
    tertiary = Color(0xFF81C784),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
