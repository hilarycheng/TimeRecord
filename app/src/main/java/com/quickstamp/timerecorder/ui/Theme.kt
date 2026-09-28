package com.quickstamp.timerecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Midnight Rose: dark without being flat black, with high-contrast text and restrained accents.
internal val WebBg = Color(0xFF0B0D12)
internal val WebCard = Color(0xFF141821)
internal val WebCard2 = Color(0xFF1B202C)
internal val WebInk = Color(0xFFF5F3F7)
internal val WebMuted = Color(0xFFBBB6C5)
internal val WebDim = Color(0xFF898391)
internal val WebLine = Color(0xFF2A3140)
internal val WebAccent = Color(0xFFFF6FAE)
internal val WebAccent2 = Color(0xFFA98BFF)
internal val WebAccentSoft = Color(0xFF3A1B2B)
internal val WebBus = Color(0xFF4DD7C0)
internal val WebBusSoft = Color(0xFF15302C)
internal val WebWarm = Color(0xFFF3C76B)
internal val WebWarmSoft = Color(0xFF302817)
internal val WebGood = Color(0xFF79D7A2)
internal val WebGoodSoft = Color(0xFF173025)
internal val WebDanger = Color(0xFFFF7D91)
internal val WebDangerSoft = Color(0xFF3A1C25)

private val RecorderColors = darkColorScheme(
    primary = WebAccent,
    onPrimary = Color(0xFF2C0C1A),
    primaryContainer = WebAccentSoft,
    onPrimaryContainer = Color(0xFFFFD8E8),
    secondary = WebBus,
    onSecondary = Color(0xFF062522),
    secondaryContainer = WebBusSoft,
    onSecondaryContainer = Color(0xFFC6FFF5),
    tertiary = WebAccent2,
    onTertiary = Color(0xFF21113A),
    tertiaryContainer = Color(0xFF2A2140),
    onTertiaryContainer = Color(0xFFE9DEFF),
    background = WebBg,
    onBackground = WebInk,
    surface = WebCard,
    onSurface = WebInk,
    surfaceVariant = WebCard2,
    onSurfaceVariant = WebMuted,
    outline = WebLine,
    outlineVariant = Color(0xFF222834),
    error = WebDanger,
    onError = Color(0xFF320811),
    errorContainer = WebDangerSoft,
    onErrorContainer = Color(0xFFFFD8DE),
    inverseSurface = WebInk,
    inverseOnSurface = WebBg,
    inversePrimary = Color(0xFF9B315F),
    scrim = Color.Black,
    surfaceTint = Color.Transparent,
)

@Composable
fun TimeRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RecorderColors, content = content)
}
