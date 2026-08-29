package com.weighttrack.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Emerald reads as progress without shouting, and stays legible on both black and white.
private val Emerald200 = Color(0xFFA7F3D0)
private val Emerald300 = Color(0xFF6EE7B7)
private val Emerald400 = Color(0xFF34D399)
private val Emerald700 = Color(0xFF047857)

private val Sky200 = Color(0xFFBAE6FD)
private val Sky300 = Color(0xFF7DD3FC)
private val Sky700 = Color(0xFF0369A1)

private val Amber200 = Color(0xFFFDE68A)
private val Amber300 = Color(0xFFFCD34D)
private val Amber800 = Color(0xFF92400E)

private val Slate50 = Color(0xFFF8FAFC)
private val Slate200 = Color(0xFFE2E8F0)
private val Slate300 = Color(0xFFCBD5E1)
private val Slate400 = Color(0xFF94A3B8)
private val Slate600 = Color(0xFF475569)
private val Slate700 = Color(0xFF334155)
private val Slate800 = Color(0xFF1E293B)
private val Slate850 = Color(0xFF172033)
private val Slate900 = Color(0xFF0F172A)
private val Slate950 = Color(0xFF090F1D)

/** Trend and reading colours, used by charts and by the delta figures. */
object WeightTrackAccents {
    val losing = Emerald400
    val gaining = Color(0xFFFB923C)
    val steady = Sky300
    val losingLight = Emerald700
    val gainingLight = Color(0xFFC2410C)
    val steadyLight = Sky700
}

val DarkColors = darkColorScheme(
    primary = Emerald400,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513A),
    onPrimaryContainer = Emerald200,
    inversePrimary = Emerald700,

    secondary = Sky300,
    onSecondary = Color(0xFF003548),
    secondaryContainer = Color(0xFF004D66),
    onSecondaryContainer = Sky200,

    tertiary = Amber300,
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Amber200,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Slate900,
    onBackground = Slate200,
    surface = Slate900,
    onSurface = Slate200,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate300,
    surfaceTint = Emerald400,

    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate850,
    surfaceContainer = Slate800,
    surfaceContainerHigh = Color(0xFF243044),
    surfaceContainerHighest = Color(0xFF2C3A50),

    inverseSurface = Slate200,
    inverseOnSurface = Slate900,
    outline = Slate600,
    outlineVariant = Slate700,
    scrim = Color(0xFF000000),
)

/**
 * Pure black for OLED panels. Only the background layers change; every accent stays identical
 * to the dark scheme so the app does not look like a different product.
 */
val AmoledColors = DarkColors.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0C),
    surfaceContainer = Color(0xFF121216),
    surfaceContainerHigh = Color(0xFF1A1A20),
    surfaceContainerHighest = Color(0xFF22222A),
    surfaceVariant = Color(0xFF16161B),
    outline = Color(0xFF54545E),
    outlineVariant = Color(0xFF2A2A32),
)

val LightColors = lightColorScheme(
    primary = Emerald700,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Emerald200,
    onPrimaryContainer = Color(0xFF002114),
    inversePrimary = Emerald300,

    secondary = Sky700,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Sky200,
    onSecondaryContainer = Color(0xFF001E2C),

    tertiary = Amber800,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Amber200,
    onTertiaryContainer = Color(0xFF2A1800),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFBFDFB),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDFB),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDCE5DE),
    onSurfaceVariant = Color(0xFF404944),
    surfaceTint = Emerald700,

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Slate50,
    surfaceContainer = Color(0xFFEFF3EF),
    surfaceContainerHigh = Color(0xFFE9EEEA),
    surfaceContainerHighest = Color(0xFFE3E9E4),

    inverseSurface = Color(0xFF2E312F),
    inverseOnSurface = Color(0xFFEFF1EE),
    outline = Slate400,
    outlineVariant = Color(0xFFC0C9C2),
    scrim = Color(0xFF000000),
)
