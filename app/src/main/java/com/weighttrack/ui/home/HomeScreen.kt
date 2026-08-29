package com.weighttrack.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weighttrack.core.math.BmiCategory
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.math.WaistToHeightCategory
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.domain.BodyFatSource
import com.weighttrack.domain.ProgressSnapshot
import com.weighttrack.ui.components.ChartRange
import com.weighttrack.ui.components.EmptyState
import com.weighttrack.ui.components.GoalProgressBar
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.Sparkline
import com.weighttrack.ui.components.StatTile
import com.weighttrack.ui.components.TrendChart
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.ui.format.WeightFormatter
import com.weighttrack.ui.theme.HeroNumberStyle
import com.weighttrack.ui.theme.HeroUnitStyle
import com.weighttrack.ui.theme.LocalTrendColors
import com.weighttrack.ui.theme.rememberTrendChartColors
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    snapshot: ProgressSnapshot,
    onLogWeight: () -> Unit,
    onOpenGoal: () -> Unit,
    onOpenMeasurements: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    if (!snapshot.hasData) {
        EmptyState(
            icon = Icons.Filled.MonitorWeight,
            title = "No readings yet",
            message = "Log your weight once and WeightTrack starts drawing the trend. A few days in, it can tell you which way you are actually going.",
            modifier = modifier.fillMaxSize().padding(top = 64.dp),
            action = {
                Button(onClick = onLogWeight) {
                    androidx.compose.material3.Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  Log your first weight")
                }
            },
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TrendHeroCard(snapshot, today) }
        item { RateCard(snapshot) }
        snapshot.goal?.let { item { GoalCard(snapshot, onOpenGoal, today) } }
        item { ChartCard(snapshot, today) }
        item { BodyStatsCard(snapshot, onOpenMeasurements) }
    }
}

@Composable
private fun TrendHeroCard(snapshot: ProgressSnapshot, today: LocalDate) {
    val unit = snapshot.settings.weightUnit
    val trendColors = LocalTrendColors.current
    val trendGrams = snapshot.series.latestTrendGrams?.roundToInt()
    val weekChange = snapshot.series.changeOverDays(7)

    SectionCard {
        SectionHeading("Trend weight")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = trendGrams?.let { WeightFormatter.value(it, unit) } ?: "--",
                style = HeroNumberStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.fillMaxWidth(0f))
            Text(
                text = " ${WeightFormatter.unitLabel(unit)}",
                style = HeroUnitStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        if (weekChange != null) {
            val color = changeColor(weekChange, snapshot.goal?.direction, trendColors)
            Text(
                text = "${WeightFormatter.delta(weekChange, unit)} in the last week",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                fontWeight = FontWeight.Medium,
            )
        }

        snapshot.latestEntry?.let { entry ->
            val deviation = snapshot.series.latestDeviationGrams
            val scaleLine = buildString {
                append("Last weighed ")
                append(DateFormatters.sinceDay(entry.localDate, today))
                append(" at ")
                append(WeightFormatter.full(entry.grams, unit))
                if (deviation != null && abs(deviation) > 150) {
                    append(if (deviation > 0) ", above the line" else ", below the line")
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = scaleLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (snapshot.series.points.size >= 3) {
            Spacer(Modifier.height(12.dp))
            Sparkline(
                series = snapshot.series,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
    }
}

@Composable
private fun RateCard(snapshot: ProgressSnapshot) {
    val unit = snapshot.settings.weightUnit
    val rate = snapshot.rate
    SectionCard {
        SectionHeading("Rate of change")
        Spacer(Modifier.height(8.dp))
        if (!rate.hasEnoughData) {
            Text(
                text = "Keep logging. A week of readings is enough to work out which way the trend is going.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatTile(
                label = "Per week",
                value = WeightFormatter.delta(rate.gramsPerWeek, unit, decimals = 2),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Energy balance",
                value = "${abs(rate.impliedKcalPerDay).roundToInt()} kcal",
                caption = if (rate.impliedKcalPerDay < 0) "below maintenance" else "above maintenance",
                modifier = Modifier.weight(1f),
            )
        }
        if (snapshot.isPlateau) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The line has been flat for a couple of weeks. That is a plateau, not a failure, and it usually means intake has crept back up to maintenance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun GoalCard(snapshot: ProgressSnapshot, onOpenGoal: () -> Unit, today: LocalDate) {
    val goal = snapshot.goal ?: return
    val projection = snapshot.projection
    val unit = snapshot.settings.weightUnit

    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading("Goal")
            androidx.compose.material3.TextButton(onClick = onOpenGoal) { Text("Edit") }
        }

        val milestoneFractions = remembermilestoneFractions(snapshot)
        GoalProgressBar(
            progress = (projection?.progressFraction ?: 0.0).toFloat(),
            milestoneFractions = milestoneFractions,
            reachedCount = snapshot.milestones.count { it.reached },
        )
        Spacer(Modifier.height(10.dp))

        LabelledValue(
            label = "Target",
            value = WeightFormatter.full(goal.targetGrams, unit),
        )
        if (projection != null) {
            LabelledValue(
                label = if (goal.direction == GoalDirection.GAIN) "To gain" else "To go",
                value = WeightFormatter.full(abs(projection.remainingGrams).roundToInt(), unit),
            )
            LabelledValue(
                label = "Progress",
                value = "${(projection.progressFraction * 100).roundToInt()}%",
            )
            val etaDate = projection.etaDate(today)
            LabelledValue(
                label = "Projected",
                value = when {
                    projection.reached -> "Reached"
                    etaDate == null -> "Not on this trend"
                    else -> DateFormatters.projection(etaDate, today)
                },
                valueColor = if (etaDate == null && !projection.reached) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            // A single date implies a precision the data does not have, so the spread is shown
            // whenever the rate is uncertain enough for it to matter.
            val earliest = projection.etaDateOptimistic(today)
            val latest = projection.etaDatePessimistic(today)
            if (etaDate != null && earliest != null && latest != null && earliest != latest) {
                Text(
                    text = "Somewhere between ${DateFormatters.shortDate(earliest, today)} and ${DateFormatters.shortDate(latest, today)}, on how the last two weeks have gone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (etaDate == null && !projection.reached) {
                Text(
                    text = "The trend is not moving toward the target yet, so there is no honest date to give you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        snapshot.nextMilestone?.let { milestone ->
            Spacer(Modifier.height(8.dp))
            val remaining = abs((snapshot.series.latestTrendGrams ?: 0.0) - milestone.grams)
            Text(
                text = "Next milestone ${WeightFormatter.full(milestone.grams, unit)}, ${WeightFormatter.full(remaining.roundToInt(), unit)} away. That is ${milestone.index} of ${milestone.total}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun remembermilestoneFractions(snapshot: ProgressSnapshot): List<Float> {
    val goal = snapshot.goal ?: return emptyList()
    val span = (goal.targetGrams - goal.startGrams).toDouble()
    if (abs(span) < 1.0) return emptyList()
    return snapshot.milestones.map { milestone ->
        ((milestone.grams - goal.startGrams) / span).toFloat().coerceIn(0f, 1f)
    }
}

@Composable
private fun ChartCard(snapshot: ProgressSnapshot, today: LocalDate) {
    SectionCard {
        SectionHeading("Last 30 days")
        Spacer(Modifier.height(8.dp))
        TrendChart(
            series = snapshot.series,
            unit = snapshot.settings.weightUnit,
            colors = rememberTrendChartColors(),
            range = ChartRange.MONTH,
            goalGrams = snapshot.goal?.targetGrams,
            milestoneGrams = snapshot.milestones.filter { !it.reached }.take(2).map { it.grams },
            today = today,
            height = 200.dp,
        )
        Text(
            text = "Faded dots are what the scale said. The line is the trend, smoothed over ${snapshot.settings.trendWindowDays} days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BodyStatsCard(snapshot: ProgressSnapshot, onOpenMeasurements: () -> Unit) {
    val unit = snapshot.settings.weightUnit
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading("Body")
            androidx.compose.material3.TextButton(onClick = onOpenMeasurements) { Text("Measurements") }
        }

        val hasAnything = snapshot.bmi != null || snapshot.bodyComposition != null ||
            snapshot.basalMetabolicRate != null
        if (!hasAnything) {
            Text(
                text = "Add your height in settings and WeightTrack can work out BMI, your healthy range and how many calories you burn at rest.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            snapshot.bmi?.let { bmi ->
                StatTile(
                    label = "BMI",
                    value = String.format(Locale.getDefault(), "%.1f", bmi),
                    caption = snapshot.bmiCategory?.let { bmiLabel(it) },
                    modifier = Modifier.weight(1f),
                )
            }
            snapshot.bodyComposition?.let { composition ->
                StatTile(
                    label = "Body fat",
                    value = String.format(Locale.getDefault(), "%.1f%%", composition.percent),
                    caption = when (composition.source) {
                        BodyFatSource.LOGGED -> "from your reading"
                        BodyFatSource.NAVY_ESTIMATE -> "estimated from measurements"
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            snapshot.basalMetabolicRate?.let { bmr ->
                StatTile(
                    label = "At rest",
                    value = "${bmr.roundToInt()}",
                    caption = "kcal/day",
                    modifier = Modifier.weight(1f),
                )
            }
            snapshot.totalDailyEnergyExpenditure?.let { tdee ->
                StatTile(
                    label = "Maintenance",
                    value = "${tdee.roundToInt()}",
                    caption = "kcal/day at your activity level",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        snapshot.waistToHeightRatio?.let { ratio ->
            Spacer(Modifier.height(4.dp))
            LabelledValue(
                label = "Waist to height",
                value = String.format(Locale.getDefault(), "%.2f", ratio) +
                    snapshot.waistToHeightCategory?.let { " (${waistLabel(it)})" }.orEmpty(),
            )
        }
        snapshot.healthyRangeGrams?.let { range ->
            LabelledValue(
                label = "Healthy range",
                value = "${WeightFormatter.value(range.first, unit)} to ${WeightFormatter.full(range.last, unit)}",
            )
        }
        snapshot.bodyComposition?.let { composition ->
            if (composition.leanMassGrams != null && composition.fatMassGrams != null) {
                LabelledValue(
                    label = "Lean and fat mass",
                    value = "${WeightFormatter.value(composition.leanMassGrams, unit)} / ${WeightFormatter.full(composition.fatMassGrams, unit)}",
                )
            }
        }
    }
}

private fun bmiLabel(category: BmiCategory): String = when (category) {
    BmiCategory.UNDERWEIGHT -> "underweight"
    BmiCategory.HEALTHY -> "healthy"
    BmiCategory.OVERWEIGHT -> "overweight"
    BmiCategory.OBESE_I -> "obese, class 1"
    BmiCategory.OBESE_II -> "obese, class 2"
    BmiCategory.OBESE_III -> "obese, class 3"
}

private fun waistLabel(category: WaistToHeightCategory): String = when (category) {
    WaistToHeightCategory.LOW -> "low"
    WaistToHeightCategory.HEALTHY -> "healthy"
    WaistToHeightCategory.INCREASED -> "increased risk"
    WaistToHeightCategory.HIGH -> "high risk"
}

/**
 * Green when the trend is heading where the goal wants it to, amber when it is not. With no
 * goal set, any movement is neutral rather than judged.
 */
@Composable
private fun changeColor(
    changeGrams: Double,
    direction: GoalDirection?,
    trendColors: com.weighttrack.ui.theme.TrendColors,
) = when {
    abs(changeGrams) < TrendEngine.PLATEAU_GRAMS_PER_WEEK -> trendColors.steady
    direction == null -> MaterialTheme.colorScheme.onSurfaceVariant
    direction == GoalDirection.LOSE -> if (changeGrams < 0) trendColors.losing else trendColors.gaining
    direction == GoalDirection.GAIN -> if (changeGrams > 0) trendColors.losing else trendColors.gaining
    else -> trendColors.steady
}

/** Grams-per-display-unit helper kept beside the screen that formats with it. */
internal val gramsPerKg = UnitConverter.GRAMS_PER_KG
