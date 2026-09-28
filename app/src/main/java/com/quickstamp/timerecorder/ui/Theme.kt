package com.quickstamp.timerecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RecorderColors = darkColorScheme(
    primary = Color(0xFF8EA2FF),
    onPrimary = Color(0xFF101528),
    secondary = Color(0xFF56D7D2),
    tertiary = Color(0xFFFFC56D),
    background = Color(0xFF090B10),
    onBackground = Color(0xFFF3F4F8),
    surface = Color(0xFF11141B),
    onSurface = Color(0xFFF3F4F8),
    surfaceVariant = Color(0xFF1A1E28),
    onSurfaceVariant = Color(0xFFB9C0D0),
    outline = Color(0xFF353B49),
    error = Color(0xFFFF8A8A),
)

@Composable
fun TimeRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RecorderColors,
        content = content,
    )
}
