package com.quickstamp.timerecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val WebBg = Color(0xFF090B10)
internal val WebCard = Color(0xFF151923)
internal val WebCard2 = Color(0xFF1B202C)
internal val WebInk = Color(0xFFF5F7FB)
internal val WebMuted = Color(0xFF8C95A8)
internal val WebLine = Color(0xFF272D3A)
internal val WebAccent = Color(0xFF8B7CFF)
internal val WebAccentSoft = Color(0xFF26233F)
internal val WebBus = Color(0xFF41D6C3)
internal val WebBusSoft = Color(0xFF12272A)
internal val WebWarm = Color(0xFFFFBD5B)
internal val WebWarmSoft = Color(0xFF2D2519)
internal val WebDanger = Color(0xFFFF7188)
internal val WebDangerSoft = Color(0xFF351C25)

private val RecorderColors = darkColorScheme(
    primary = WebAccent,
    onPrimary = Color.White,
    primaryContainer = WebAccentSoft,
    onPrimaryContainer = Color(0xFFC9C3FF),
    secondary = WebBus,
    onSecondary = Color(0xFF0B2424),
    secondaryContainer = WebBusSoft,
    onSecondaryContainer = Color(0xFFA5F5E9),
    tertiary = WebWarm,
    onTertiary = Color(0xFF2B1A00),
    background = WebBg,
    onBackground = WebInk,
    surface = WebCard,
    onSurface = WebInk,
    surfaceVariant = WebCard2,
    onSurfaceVariant = WebMuted,
    outline = WebLine,
    error = WebDanger,
    errorContainer = WebDangerSoft,
)

@Composable
fun TimeRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RecorderColors,
        content = content,
    )
}
