package com.quickstamp.timerecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val WebBg = Color(0xFF09080D)
internal val WebCard = Color(0xFF17131B)
internal val WebCard2 = Color(0xFF211A25)
internal val WebInk = Color(0xFFFFF7FB)
internal val WebMuted = Color(0xFFA99EAB)
internal val WebLine = Color(0xFF392C3A)
internal val WebAccent = Color(0xFFFF6FB5)      // rose pink
internal val WebAccent2 = Color(0xFFB98CFF)     // violet
internal val WebAccentSoft = Color(0xFF3A2030)
internal val WebBus = Color(0xFF4FE0CE)
internal val WebBusSoft = Color(0xFF142B2C)
internal val WebWarm = Color(0xFFFFBE63)
internal val WebWarmSoft = Color(0xFF312517)
internal val WebGood = Color(0xFF7EE3A8)
internal val WebGoodSoft = Color(0xFF173022)
internal val WebDanger = Color(0xFFFF718F)
internal val WebDangerSoft = Color(0xFF3A1B27)

private val RecorderColors = darkColorScheme(
    primary = WebAccent,
    onPrimary = Color(0xFF321021),
    primaryContainer = WebAccentSoft,
    onPrimaryContainer = Color(0xFFFFB8D9),
    secondary = WebBus,
    onSecondary = Color(0xFF082525),
    secondaryContainer = WebBusSoft,
    onSecondaryContainer = Color(0xFFB9FFF4),
    tertiary = WebAccent2,
    onTertiary = Color(0xFF211132),
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
    MaterialTheme(colorScheme = RecorderColors, content = content)
}
