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
import androidx.compose.material3.TextButton
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
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ChartsScreen(
    snapshot: ProgressSnapshot,
    activity: ActivityState,
    associations: AssociationState = AssociationState(),
    /**
     * Days a period covered, shaded on the chart and left out of the weekday averages.
     *
     * Empty unless somebody has granted the cycle permission, which is the whole of what
     * refusing it costs.
     */
    cycleDays: Set<LocalDate> = emptySet(),
    /**
     * Injections and how somebody felt, marked along the bottom of the trend.
     *
     * Empty unless the injection log is on, which is the whole of what leaving it off costs.
     */
    medicationDays: MedicationDays = MedicationDays(),
    /**
     * A day the chart counts from, when somebody has picked one. Null leaves the fixed spans in
     * charge, which is where everybody starts.
     */
    since: LocalDate? = null,
    onSinceChange: (LocalDate?) -> Unit = {},
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
    var pickingSince by remember { mutableStateOf(false) }
    val newest = snapshot.series.points.lastOrNull()?.date ?: today
    // A chosen day is a window in days, which is the only thing the chart understands. Bounded
    // below at two, because a window of one day has nothing to draw a line between.
    val sinceDays = since?.let {
        (ChronoUnit.DAYS.between(it, newest) + 1).toInt().coerceAtLeast(2)
    }
    // The same window the chart draws, worked out once. Read separately, the header described a
    // stretch a day longer than the one on screen whenever a chosen day was the newest reading.
    val windowStart = (sinceDays ?: range.days)?.let { newest.minusDays(it - 1L) }
        ?: (snapshot.series.points.firstOrNull()?.date ?: today)
    val comparison = remember(snapshot.series, windowStart, newest) {
        Analytics.changeOverRange(snapshot.series, windowStart, newest)
    }
    val unit = snapshot.settings.weightUnit
    val weekRule = snapshot.settings.weekRule
    val weekly = remember(snapshot.series, weekRule) {
        Analytics.weeklyChanges(snapshot.series, rule = weekRule)
    }
    val weekdays = remember(snapshot.series, cycleDays) {
        Analytics.weekdayEffects(snapshot.series, excluded = cycleDays)
    }

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
                    // A chosen day wins over the chips while it is set, so nothing on the row
                    // claims to be showing a span the chart is not showing.
                    val selected = option == range && since == null
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
                                onClick = {
                                    range = option
                                    onSinceChange(null)
                                },
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

        // Its own line rather than a seventh chip. Seven of them on a phone leaves a label a few
        // characters wide, and at twice the font size there is nothing left to read at all.
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { pickingSince = true }) {
                    Text(
                        text = if (since == null) {
                            stringResource(R.string.charts_since_a_date)
                        } else {
                            stringResource(
                                R.string.charts_since,
                                DateFormatters.fullDate(since),
                            )
                        },
                    )
                }
                if (since != null) {
                    TextButton(onClick = { onSinceChange(null) }) {
                        Text(stringResource(R.string.charts_since_clear))
                    }
                }
            }
        }

        // What the window actually did, beside the same length of time before it. A change on
        // its own is a number nobody can place.
        item {
            val change = comparison.changeGrams
            Text(
                text = when {
                    change == null -> stringResource(R.string.charts_range_nothing)
                    comparison.previousChangeGrams == null -> stringResource(
                        R.string.charts_range_change,
                        WeightFormatter.delta(change, unit),
                        comparison.days,
                    )
                    else -> stringResource(
                        R.string.charts_range_change_against,
                        WeightFormatter.delta(change, unit),
                        comparison.days,
                        WeightFormatter.delta(comparison.previousChangeGrams!!, unit),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    // Only when there is something shaded. A key to a band nobody has is a line
                    // about periods on the screen of somebody who never asked for one.
                    if (cycleDays.isNotEmpty()) {
                        ChartLegend(
                            stringResource(R.string.charts_period_legend),
                            MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    // Only when there is something marked, for the same reason: a key to an
                    // injection nobody logs is a word about medication on a screen that has
                    // nothing to do with it.
                    if (medicationDays.doses.isNotEmpty()) {
                        ChartLegend(
                            stringResource(R.string.charts_dose_legend),
                            MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (medicationDays.sideEffects.isNotEmpty()) {
                        ChartLegend(
                            stringResource(R.string.charts_effect_legend),
                            MaterialTheme.colorScheme.error,
                            dot = true,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                TrendChart(
                    series = snapshot.series,
                    unit = unit,
                    colors = rememberTrendChartColors(),
                    range = range,
                    sinceDays = sinceDays,
                    goalGrams = snapshot.goal?.targetGrams,
                    milestoneGrams = snapshot.milestones.map { it.grams },
                    waterDays = cycleDays,
                    doseDays = medicationDays.doses,
                    sideEffectDays = medicationDays.sideEffects,
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

    if (pickingSince) {
        // The picker works in UTC milliseconds wherever somebody is, so the day goes through UTC
        // midnight rather than through the device zone, which would move it by one.
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = (since ?: today)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickingSince = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val picked = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC)
                            .toLocalDate()
                        // A day in the future leaves nothing to draw. Taken as today instead of
                        // refused, which is what somebody who scrolled one month too far meant.
                        onSinceChange(minOf(picked, today))
                    }
                    pickingSince = false
                }) { Text(stringResource(R.string.common_set)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingSince = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            androidx.compose.material3.DatePicker(state = state, showModeToggle = false)
        }
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
