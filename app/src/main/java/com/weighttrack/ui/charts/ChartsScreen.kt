package com.weighttrack.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import com.weighttrack.R
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
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.ui.theme.LocalTrendColors
import com.weighttrack.ui.theme.rememberTrendChartColors
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ChartsScreen(
    snapshot: ProgressSnapshot,
    activity: ActivityState,
    associations: AssociationState = AssociationState(),
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    if (!snapshot.hasData) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
            title = stringResource(R.string.charts_nothing_to_plot_yet),
            message = stringResource(R.string.charts_log_a_few_readings_and_the),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .selectableGroup()
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            ) {
                ChartRange.entries.forEachIndexed { index, option ->
                    val selected = option == range
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .then(
                                if (selected) {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    Modifier
                                },
                            )
                            .selectable(
                                selected = selected,
                                onClick = { range = option },
                                role = Role.RadioButton,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(option.label),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (index != ChartRange.entries.lastIndex) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.charts_weight_trend), style = MaterialTheme.typography.titleMedium)
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
                    height = 250.dp,
                )
                Text(
                    text = stringResource(R.string.charts_drag_to_pan_pinch_to_zoom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (weekly.isNotEmpty()) {
            item { WeeklyChangeCard(weekly, unit, snapshot.goal?.direction, today) }
        }

        if (weekdays.size >= 3) {
            item { WeekdayCard(weekdays, unit) }
        }

        item { ActivityCard(activity) }

        item { AssociationCard(associations) }

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
                .clip(if (dot) CircleShape else RectangleShape)
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
    today: LocalDate,
) {
    val trendColors = LocalTrendColors.current
    val goodColor = trendColors.losing
    val badColor = trendColors.gaining
    val gaining = direction == com.weighttrack.core.model.GoalDirection.GAIN

    val displayed = weekly.takeLast(4)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
        Spacer(Modifier.height(14.dp))
        SectionHeading(stringResource(R.string.charts_week_by_week))
        Spacer(Modifier.height(10.dp))
        val best = displayed.minByOrNull { it.changeGrams }
        val average = displayed.map { it.changeGrams }.average()
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.charts_average_week),
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
                        stringResource(R.string.charts_biggest_drop),
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
        val maxMagnitude = max(displayed.maxOf { abs(it.changeGrams) }, 1.0)
        val weeklyDescription = stringResource(R.string.chart_weekly_description, displayed.size)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .semantics { contentDescription = weeklyDescription },
        ) {
            val slot = size.width / displayed.size
            val barWidth = (slot * 0.6f).coerceAtMost(28f)
            val midY = size.height / 2f
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1f,
            )
            displayed.forEachIndexed { index, week ->
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
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            displayed.forEach { week ->
                Text(
                    text = DateFormatters.shortDate(week.weekEnd, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
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
        SectionHeading(stringResource(R.string.charts_weekday_pattern))
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.charts_how_far_each_day_usually_sits),
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
                    text = stringResource(R.string.charts_you_read_heaviest_on_and_lightest, heaviest.day.getDisplayName(TextStyle.FULL, locale), lightest.day.getDisplayName(TextStyle.FULL, locale)),
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
        SectionHeading(stringResource(R.string.charts_logging))
        Spacer(Modifier.height(8.dp))
        LabelledValue(stringResource(R.string.chartsscreen_days_weighed), stringResource(R.string.chartsscreen_last, logged, total))
        LabelledValue(stringResource(R.string.chartsscreen_current_streak), if (streak == 0) stringResource(R.string.chartsscreen_none_right_now) else stringResource(R.string.chartsscreen_streak_days, streak))
        Column {
            Text(
                text = stringResource(R.string.charts_the_trend_copes_fine_with_missed),
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
        SectionHeading(stringResource(R.string.charts_movement))
        Spacer(Modifier.height(6.dp))
        when (activity.status) {
            ActivityStatus.LOADING -> Text(
                text = stringResource(R.string.charts_checking_health_connect),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityStatus.UNAVAILABLE -> Text(
                text = stringResource(R.string.charts_health_connect_is_not_available_on),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityStatus.NOT_PERMITTED -> Text(
                text = stringResource(R.string.charts_connect_health_connect_in_settings_and),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityStatus.NO_DATA -> Text(
                text = stringResource(R.string.charts_nothing_recorded_in_the_last_month),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Not the same as having nothing recorded, and saying so would be telling somebody
            // who walks every day that they do not.
            ActivityStatus.FAILED -> Text(
                text = stringResource(R.string.charts_could_not_read_movement),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityStatus.READY -> {
                val accent = MaterialTheme.colorScheme.secondary
                val stepDays = activity.days.filter { it.steps != null }
                if (stepDays.isNotEmpty()) {
                    val maximum = max(stepDays.maxOf { it.steps ?: 0L }, 1L)
                    val stepsDescription =
                        stringResource(R.string.chart_steps_description, stepDays.size)
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .semantics { contentDescription = stepsDescription },
                    ) {
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
                    LabelledValue(stringResource(R.string.chartsscreen_average_steps), stringResource(R.string.chartsscreen_steps_a_day, it))
                }
                activity.averageActiveKilocalories?.let {
                    LabelledValue(stringResource(R.string.chartsscreen_active_calories), stringResource(R.string.chartsscreen_day, it.roundToInt()))
                }
                LabelledValue(stringResource(R.string.chartsscreen_days_recorded), activity.days.size.toString())
                Text(
                    text = stringResource(R.string.charts_shown_for_context_beside_the_trend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
