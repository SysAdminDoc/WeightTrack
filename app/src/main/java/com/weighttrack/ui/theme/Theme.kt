package com.weighttrack.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.weighttrack.core.model.ThemeMode

/** Colours that carry meaning rather than decoration, resolved for the active scheme. */
data class TrendColors(
    val losing: Color,
    val gaining: Color,
    val steady: Color,
)

val LocalTrendColors = staticCompositionLocalOf {
    TrendColors(
        losing = WeightTrackAccents.losing,
        gaining = WeightTrackAccents.gaining,
        steady = WeightTrackAccents.steady,
    )
}

@Composable
fun WeightTrackTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    // SYSTEM follows the phone, and resolves its dark side to true black. A person who
    // deliberately picked light or dark keeps that choice whatever the system does.
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val amoled = themeMode == ThemeMode.AMOLED || (themeMode == ThemeMode.SYSTEM && systemDark)

    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        !dark -> LightColors
        amoled -> AmoledColors
        else -> DarkColors
    }

    val trendColors = if (dark) {
        TrendColors(
            losing = WeightTrackAccents.losing,
            gaining = WeightTrackAccents.gaining,
            steady = WeightTrackAccents.steady,
        )
    } else {
        TrendColors(
            losing = WeightTrackAccents.losingLight,
            gaining = WeightTrackAccents.gainingLight,
            steady = WeightTrackAccents.steadyLight,
        )
    }

    CompositionLocalProvider(LocalTrendColors provides trendColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = WeightTrackTypography,
            shapes = WeightTrackShapes,
            content = content,
        )
    }
}
