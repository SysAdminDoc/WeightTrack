package com.weighttrack.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weighttrack.core.math.BmiCategory
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.math.WaistToHeightCategory
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.domain.BodyFatSource
import com.weighttrack.domain.ProgressSnapshot
import com.weighttrack.ui.components.EmptyState
import com.weighttrack.ui.components.GoalProgressBar
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.Sparkline
import com.weighttrack.ui.components.StatTile
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.core.format.VolumeFormatter
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.ui.theme.HeroNumberStyle
import com.weighttrack.ui.theme.HeroUnitStyle
import com.weighttrack.ui.theme.LocalTrendColors
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    snapshot: ProgressSnapshot,
    onLogWeight: () -> Unit,
    onOpenGoal: () -> Unit,
    onOpenMeasurements: () -> Unit,
    onOpenWater: () -> Unit,
    onOpenFasting: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenScale: () -> Unit,
    waterSummary: WaterSummary?,
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
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item { TrendHeroCard(snapshot, today) }
        item { HomeDivider() }
        item { RateCard(snapshot) }
        item { HomeDivider() }
        snapshot.goal?.let { item { GoalCard(snapshot, onOpenGoal, today) } }
        item { HomeDivider() }
        item {
            HomeActionRow(
                icon = Icons.Outlined.MonitorWeight,
                title = "Log weight",
                subtitle = "Record your weight",
                onClick = onLogWeight,
            )
        }
        item {
            HomeActionRow(
                icon = Icons.Outlined.Bluetooth,
                title = "Weigh in on your scale",
                subtitle = "Read a Bluetooth scale straight into the log",
                onClick = onOpenScale,
            )
        }
        item {
            HomeActionRow(
                icon = Icons.Outlined.LocalDrink,
                title = "Water",
                subtitle = waterSummary
                    ?.let { "${VolumeFormatter.full(it.totalMl, it.unit)} of ${VolumeFormatter.full(it.targetMl, it.unit)} today" }
                    ?: "Track what you drink",
                onClick = onOpenWater,
            )
        }
        item {
            HomeActionRow(
                icon = Icons.Outlined.Timer,
                title = "Fasting",
                subtitle = "Time an eating window",
                onClick = onOpenFasting,
            )
        }
        item {
            HomeActionRow(
                icon = Icons.Outlined.PhotoCamera,
                title = "Progress photos",
                subtitle = "Compare how you look over time",
                onClick = onOpenPhotos,
            )
        }
        item { BodyStatsCard(snapshot, onOpenMeasurements) }
    }
}

@Composable
private fun TrendHeroCard(snapshot: ProgressSnapshot, today: LocalDate) {
    val unit = snapshot.settings.weightUnit
    val trendColors = LocalTrendColors.current
    val trendGrams = snapshot.series.latestTrendGrams?.roundToInt()
    val weekChange = snapshot.series.changeOverDays(7)

    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        SectionHeading("Trend weight")
        Spacer(Modifier.height(2.dp))
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
                text = "${WeightFormatter.delta(weekChange, unit)} this week",
                style = MaterialTheme.typography.titleMedium,
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
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "30 days",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Sparkline(
                series = snapshot.series,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(104.dp),
            )
        }
    }
}

@Composable
private fun RateCard(snapshot: ProgressSnapshot) {
    val unit = snapshot.settings.weightUnit
    val rate = snapshot.rate
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
        SectionHeading("Rate of change")
        Spacer(Modifier.height(8.dp))
        if (!rate.hasEnoughData) {
            Text(
                text = "Keep logging. A week of readings is enough to work out which way the trend is going.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatTile(
                label = "Rate",
                value = WeightFormatter.delta(rate.gramsPerWeek, unit, decimals = 2),
                caption = "per week",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Energy balance",
                value = "${abs(rate.impliedKcalPerDay).roundToInt()} kcal/day",
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

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenGoal)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Goal", style = MaterialTheme.typography.titleMedium)
            Text(
                text = WeightFormatter.full(goal.targetGrams, unit),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        val milestoneFractions = remembermilestoneFractions(snapshot)
        GoalProgressBar(
            progress = (projection?.progressFraction ?: 0.0).toFloat(),
            milestoneFractions = milestoneFractions,
            reachedCount = snapshot.milestones.count { it.reached },
        )
        Spacer(Modifier.height(10.dp))

        if (projection != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (goal.direction == GoalDirection.GAIN) {
                        "${WeightFormatter.full(abs(projection.remainingGrams).roundToInt(), unit)} to gain"
                    } else {
                        "${WeightFormatter.full(abs(projection.remainingGrams).roundToInt(), unit)} to go"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${(projection.progressFraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val etaDate = projection.etaDate(today)
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    projection.reached -> "Reached"
                    etaDate == null -> "Not on this trend"
                    else -> "Projected ${DateFormatters.projection(etaDate, today)}"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (etaDate == null && !projection.reached) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            // A single date implies a precision the data does not have, so the spread is shown
            // whenever the rate is uncertain enough for it to matter.
            val earliest = projection.etaDateOptimistic(today)
            val latest = projection.etaDatePessimistic(today)
            if (etaDate != null && earliest != null && latest != null && earliest != latest) {
                Text(
                    text = "${DateFormatters.shortDate(earliest, today)} to ${DateFormatters.shortDate(latest, today)}, based on the last two weeks.",
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
            Spacer(Modifier.height(4.dp))
            val remaining = abs((snapshot.series.latestTrendGrams ?: 0.0) - milestone.grams)
            Text(
                text = "Next milestone ${WeightFormatter.full(milestone.grams, unit)}, ${WeightFormatter.full(remaining.roundToInt(), unit)} away. That is ${milestone.index} of ${milestone.total}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HomeDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
}

@Composable
private fun HomeActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun BodyStatsCard(snapshot: ProgressSnapshot, onOpenMeasurements: () -> Unit) {
    val unit = snapshot.settings.weightUnit
    val locale = LocalConfiguration.current.locales[0]
    SectionCard(modifier = Modifier.padding(top = 12.dp)) {
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
                    value = String.format(locale, "%.1f", bmi),
                    caption = snapshot.bmiCategory?.let { bmiLabel(it) },
                    modifier = Modifier.weight(1f),
                )
            }
            snapshot.bodyComposition?.let { composition ->
                StatTile(
                    label = "Body fat",
                    value = String.format(locale, "%.1f%%", composition.percent),
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
                value = String.format(locale, "%.2f", ratio) +
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
