package com.weighttrack.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.weighttrack.ui.components.TrendChartColors

/** Resolves the chart palette from the active scheme so the chart never hard-codes a colour. */
@Composable
fun rememberTrendChartColors(): TrendChartColors {
    val scheme = MaterialTheme.colorScheme
    val trend = LocalTrendColors.current
    return remember(scheme, trend) {
        TrendChartColors(
            trendLine = scheme.primary,
            rawPoint = scheme.onSurfaceVariant.copy(alpha = 0.45f),
            fill = scheme.primary,
            grid = scheme.outlineVariant.copy(alpha = 0.5f),
            axisText = scheme.onSurfaceVariant,
            goalLine = trend.steady,
            milestone = scheme.outline.copy(alpha = 0.7f),
            marker = scheme.onSurface.copy(alpha = 0.4f),
            markerSurface = scheme.inverseSurface,
            markerText = scheme.inverseOnSurface,
            waterBand = scheme.tertiary.copy(alpha = 0.14f),
            doseMark = scheme.secondary,
            sideEffectMark = scheme.error,
        )
    }
}
