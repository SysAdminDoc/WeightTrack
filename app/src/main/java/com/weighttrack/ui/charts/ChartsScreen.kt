package com.weighttrack.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.weighttrack.core.math.Analytics
import com.weighttrack.core.math.WeeklyChange
import com.weighttrack.core.math.WeekdayEffect
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.domain.ProgressSnapshot
import com.weighttrack.ui.components.ChartRange
import com.weighttrack.ui.components.EmptyState
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.TrendChart
import com.weighttrack.ui.format.WeightFormatter
import com.weighttrack.ui.theme.LocalTrendColors
import com.weighttrack.ui.theme.rememberTrendChartColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@Composable
fun ChartsScreen(
    snapshot: ProgressSnapshot,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    if (!snapshot.hasData) {
        EmptyState(
            icon = Icons.Filled.ShowChart,
            title = "Nothing to plot yet",
            message = "Log a few readings and the trend line, the weekly changes and your weekday pattern all appear here.",
            modifier = modifier.fillMaxSize().padding(top = 64.dp),
        )
        return
    }

    var range by remember { mutableStateOf(ChartRange.MONTH) }
    val unit = snapshot.settings.weightUnit
    val weekly = remember(snapshot.series) { Analytics.weeklyChanges(snapshot.series) }
    val weekdays = remember(snapshot.series) { Analytics.weekdayEffects(snapshot.series) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartRange.entries.forEach { option ->
                    FilterChip(
                        selected = option == range,
                        onClick = { range = option },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        item {
            SectionCard {
                SectionHeading("Weight")
                Spacer(Modifier.height(8.dp))
                TrendChart(
                    series = snapshot.series,
                    unit = unit,
                    colors = rememberTrendChartColors(),
                    range = range,
                    goalGrams = snapshot.goal?.targetGrams,
                    milestoneGrams = snapshot.milestones.map { it.grams },
                    today = today,
                    height = 280.dp,
                )
                Text(
                    text = "Drag to pan, pinch to zoom, tap a day to read it off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (weekly.isNotEmpty()) {
            item { WeeklyChangeCard(weekly, unit, snapshot.goal?.direction) }
        }

        if (weekdays.size >= 3) {
            item { WeekdayCard(weekdays, unit) }
        }

        item { ConsistencyCard(snapshot) }
    }
}

@Composable
private fun WeeklyChangeCard(
    weekly: List<WeeklyChange>,
    unit: WeightUnit,
    direction: com.weighttrack.core.model.GoalDirection?,
) {
    val trendColors = LocalTrendColors.current
    val goodColor = trendColors.losing
    val badColor = trendColors.gaining
    val gaining = direction == com.weighttrack.core.model.GoalDirection.GAIN

    SectionCard {
        SectionHeading("Week by week")
        Spacer(Modifier.height(12.dp))
        val maxMagnitude = max(weekly.maxOf { abs(it.changeGrams) }, 1.0)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / weekly.size
            val barWidth = (slot * 0.6f).coerceAtMost(28f)
            val midY = size.height / 2f
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1f,
            )
            weekly.forEachIndexed { index, week ->
                val fraction = (week.changeGrams / maxMagnitude).toFloat()
                val barHeight = abs(fraction) * (midY - 6f)
                val left = index * slot + (slot - barWidth) / 2f
                val losingWeight = week.changeGrams < 0
                val helpful = if (gaining) !losingWeight else losingWeight
                drawRoundRect(
                    color = if (helpful) goodColor else badColor,
                    topLeft = Offset(left, if (losingWeight) midY else midY - barHeight),
                    size = Size(barWidth, barHeight.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val best = weekly.minByOrNull { it.changeGrams }
        val average = weekly.map { it.changeGrams }.average()
        LabelledValue("Average week", WeightFormatter.delta(average, unit, decimals = 2))
        best?.let {
            LabelledValue("Biggest drop", WeightFormatter.delta(it.changeGrams, unit, decimals = 2))
        }
        Text(
            text = "Bars below the line are weeks the trend fell. ${weekly.size} weeks shown, oldest on the left.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekdayCard(effects: List<WeekdayEffect>, unit: WeightUnit) {
    SectionCard {
        SectionHeading("Weekday pattern")
        Spacer(Modifier.height(4.dp))
        Text(
            text = "How far each day usually sits from the trend line. This is the weekly rhythm with the underlying loss or gain taken out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        effects.sortedByDescending { it.averageDeviationGrams }.forEach { effect ->
            LabelledValue(
                label = effect.day.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                value = WeightFormatter.delta(effect.averageDeviationGrams, unit, decimals = 2),
            )
        }
        val heaviest = effects.maxByOrNull { it.averageDeviationGrams }
        val lightest = effects.minByOrNull { it.averageDeviationGrams }
        if (heaviest != null && lightest != null && heaviest.day != lightest.day) {
            val spread = heaviest.averageDeviationGrams - lightest.averageDeviationGrams
            if (spread > 300) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "You read heaviest on ${heaviest.day.getDisplayName(TextStyle.FULL, Locale.getDefault())} and lightest on ${lightest.day.getDisplayName(TextStyle.FULL, Locale.getDefault())}. Weigh on the same day each week and you will see a cleaner picture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ConsistencyCard(snapshot: ProgressSnapshot) {
    val (logged, total) = remember(snapshot.series) {
        Analytics.loggingConsistency(snapshot.series)
    }
    val streak = remember(snapshot.series) { Analytics.currentStreak(snapshot.series) }
    SectionCard {
        SectionHeading("Logging")
        Spacer(Modifier.height(8.dp))
        LabelledValue("Days weighed", "$logged of the last $total")
        LabelledValue("Current streak", if (streak == 0) "None right now" else "$streak days")
        Column {
            Text(
                text = "The trend copes fine with missed days. More readings just make it steadier.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
