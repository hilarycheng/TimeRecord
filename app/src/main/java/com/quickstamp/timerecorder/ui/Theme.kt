package com.quickstamp.timerecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Original web-theme palette: cool, dark, understated, with teal/purple/yellow accents.
internal val WebBg = Color(0xFF090B10)
internal val WebCard = Color(0xFF151923)
internal val WebCard2 = Color(0xFF1B202C)
internal val WebInk = Color(0xFFF4F7FF)
internal val WebMuted = Color(0xFF9AA6BC)
internal val WebDim = Color(0xFF6F7A90)
internal val WebLine = Color(0xFF2A3342)
internal val WebAccent = Color(0xFF8B7CFF)
internal val WebAccent2 = Color(0xFFA99CFF)
internal val WebAccentSoft = Color(0xFF252142)
internal val WebBus = Color(0xFF41D6C3)
internal val WebBusSoft = Color(0xFF15332F)
internal val WebWarm = Color(0xFFFFBD5B)
internal val WebWarmSoft = Color(0xFF3A2D18)
internal val WebGood = Color(0xFF79D7A2)
internal val WebGoodSoft = Color(0xFF173025)
internal val WebDanger = Color(0xFFFF7D91)
internal val WebDangerSoft = Color(0xFF3A1C25)

private val RecorderColors = darkColorScheme(
    primary = WebAccent,
    onPrimary = Color(0xFF11101B),
    primaryContainer = WebAccentSoft,
    onPrimaryContainer = Color(0xFFE7E1FF),
    secondary = WebBus,
    onSecondary = Color(0xFF062522),
    secondaryContainer = WebBusSoft,
    onSecondaryContainer = Color(0xFFC6FFF5),
    tertiary = WebWarm,
    onTertiary = Color(0xFF2E2108),
    tertiaryContainer = WebWarmSoft,
    onTertiaryContainer = Color(0xFFFFE3AF),
    background = WebBg,
    onBackground = WebInk,
    surface = WebCard,
    onSurface = WebInk,
    surfaceVariant = WebCard2,
    onSurfaceVariant = WebMuted,
    outline = WebLine,
    outlineVariant = Color(0xFF222A36),
    error = WebDanger,
    onError = Color(0xFF320811),
    errorContainer = WebDangerSoft,
    onErrorContainer = Color(0xFFFFD8DE),
    inverseSurface = WebInk,
    inverseOnSurface = WebBg,
    inversePrimary = Color(0xFF6457CC),
    scrim = Color.Black,
    surfaceTint = Color.Transparent,
)

@Composable
fun TimeRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RecorderColors, content = content)
}
