package com.weighttrack.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.weighttrack.ui.components.SegmentButton
import com.weighttrack.ui.components.TrendChart
import com.weighttrack.ui.format.WeightFormatter
import com.weighttrack.ui.theme.LocalTrendColors
import com.weighttrack.ui.theme.rememberTrendChartColors
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToInt

@Composable
fun ChartsScreen(
    snapshot: ProgressSnapshot,
    activity: ActivityState,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    if (!snapshot.hasData) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ChartRange.entries.forEach { option ->
                    SegmentButton(
                        modifier = Modifier.weight(1f),
                        label = option.label,
                        selected = option == range,
                        onClick = { range = option },
                    )
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Weight trend", style = MaterialTheme.typography.titleMedium)
                    snapshot.series.latestTrendGrams?.roundToInt()?.let { trend ->
                        Text(
                            WeightFormatter.full(trend, unit),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChartLegend("Raw", MaterialTheme.colorScheme.onSurfaceVariant, dot = true)
                    ChartLegend("Trend", MaterialTheme.colorScheme.primary)
                    ChartLegend("Goal", MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(10.dp))
                TrendChart(
                    series = snapshot.series,
                    unit = unit,
                    colors = rememberTrendChartColors(),
                    range = range,
                    goalGrams = snapshot.goal?.targetGrams,
                    milestoneGrams = snapshot.milestones.map { it.grams },
                    today = today,
                    height = 220.dp,
                )
                Text(
                    text = "Drag to pan · Pinch to zoom · Tap a day",
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

        item { ActivityCard(activity) }

        item { ConsistencyCard(snapshot) }
    }
}

@Composable
private fun ChartLegend(label: String, color: Color, dot: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = if (dot) 6.dp else 20.dp, height = if (dot) 6.dp else 2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
        Spacer(Modifier.height(14.dp))
        SectionHeading("Week by week")
        Spacer(Modifier.height(10.dp))
        val best = weekly.minByOrNull { it.changeGrams }
        val average = weekly.map { it.changeGrams }.average()
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Average week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    WeightFormatter.delta(average, unit, decimals = 2),
                    style = MaterialTheme.typography.titleLarge,
                    color = goodColor,
                )
            }
            best?.let {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Biggest drop",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        WeightFormatter.delta(it.changeGrams, unit, decimals = 2),
                        style = MaterialTheme.typography.titleLarge,
                        color = goodColor,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val maxMagnitude = max(weekly.maxOf { abs(it.changeGrams) }, 1.0)
        Canvas(Modifier.fillMaxWidth().height(84.dp)) {
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    }
}

@Composable
private fun WeekdayCard(effects: List<WeekdayEffect>, unit: WeightUnit) {
    val locale = LocalConfiguration.current.locales[0]
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
                label = effect.day.getDisplayName(TextStyle.FULL, locale),
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
                    text = "You read heaviest on ${heaviest.day.getDisplayName(TextStyle.FULL, locale)} and lightest on ${lightest.day.getDisplayName(TextStyle.FULL, locale)}. Weigh on the same day each week and you will see a cleaner picture.",
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

/**
 * Movement alongside the weight, read from Health Connect.
 *
 * Days with no record are simply absent rather than drawn as zero, because a day the watch was
 * on the charger is not a day of no steps, and a chart that says otherwise is worse than no
 * chart at all.
 */
@Composable
private fun ActivityCard(activity: ActivityState) {
    SectionCard {
        SectionHeading("Movement")
        Spacer(Modifier.height(6.dp))
        when (activity.status) {
            ActivityStatus.LOADING -> Text(
                text = "Checking Health Connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityStatus.UNAVAILABLE -> Text(
                text = "Health Connect is not available on this device, so there are no step or calorie figures to show.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityStatus.NOT_PERMITTED -> Text(
                text = "Connect Health Connect in Settings and allow steps and active calories, and your movement will show up here against the weight trend.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityStatus.NO_DATA -> Text(
                text = "Nothing recorded in the last month. Steps come from whatever writes them, usually a watch or your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityStatus.READY -> {
                val accent = MaterialTheme.colorScheme.secondary
                val stepDays = activity.days.filter { it.steps != null }
                if (stepDays.isNotEmpty()) {
                    val maximum = max(stepDays.maxOf { it.steps ?: 0L }, 1L)
                    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                        val slot = size.width / stepDays.size
                        val barWidth = (slot * 0.6f).coerceAtMost(20f)
                        stepDays.forEachIndexed { index, day ->
                            val fraction = (day.steps ?: 0L).toFloat() / maximum
                            val barHeight = (fraction * size.height).coerceAtLeast(2f)
                            drawRoundRect(
                                color = accent,
                                topLeft = Offset(
                                    index * slot + (slot - barWidth) / 2f,
                                    size.height - barHeight,
                                ),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(3f, 3f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                activity.averageSteps?.let {
                    LabelledValue("Average steps", "%,d a day".format(it))
                }
                activity.averageActiveKilocalories?.let {
                    LabelledValue("Active calories", "${it.roundToInt()} a day")
                }
                LabelledValue("Days recorded", activity.days.size.toString())
                Text(
                    text = "Shown for context beside the trend. Steps are one input to the weight, not a cause of it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
