package com.constx.onvyra.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Neutral light surfaces keep wallpaper accents out of large reading areas. */
internal fun ColorScheme.withSoftWhiteSurfaces(): ColorScheme = copy(
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF161616),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF161616),
    surfaceVariant = Color(0xFFEFEFEF),
    onSurfaceVariant = Color(0xFF686868),
    // Material can replace tint alpha for tonal elevation, so use the same neutral
    // surface color rather than transparent black to keep raised surfaces soft white.
    surfaceTint = Color(0xFFFAFAFA),
    surfaceDim = Color(0xFFE9E9E9),
    surfaceBright = Color(0xFFFCFCFC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEEEEEE),
    surfaceContainerHighest = Color(0xFFE6E6E6),
    outline = Color(0xFF868686),
    outlineVariant = Color(0xFFDADADA),
)
