package com.weighttrack.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Restaurant
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weighttrack.R
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
    onOpenFoods: () -> Unit,
    onOpenDiary: () -> Unit,
    /** Food logging is off until somebody asks for it, so the weight-only app stays clean. */
    nutritionEnabled: Boolean,
    waterSummary: WaterSummary?,
    onShareProgress: () -> Unit = {},
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    if (!snapshot.hasData) {
        EmptyState(
            icon = Icons.Filled.MonitorWeight,
            title = stringResource(R.string.home_no_readings_yet),
            message = stringResource(R.string.home_log_your_weight_once_and_weighttrack),
            modifier = modifier.fillMaxSize().padding(top = 64.dp),
            action = {
                Button(onClick = onLogWeight) {
                    androidx.compose.material3.Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text(stringResource(R.string.home_log_your_first_weight))
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
        snapshot.goal?.let { item { GoalCard(snapshot, onOpenGoal, today, onShareProgress) } }
        item { HomeDivider() }
        item {
            HomeActionRow(
                icon = Icons.Outlined.MonitorWeight,
                title = stringResource(R.string.app_log_weight),
                subtitle = stringResource(R.string.home_record_your_weight),
                onClick = onLogWeight,
            )
        }
        if (nutritionEnabled) {
            item {
                HomeActionRow(
                    icon = Icons.Outlined.Restaurant,
                    title = stringResource(R.string.diary_food_diary),
                    subtitle = stringResource(R.string.home_what_you_ate_today_by_meal),
                    onClick = onOpenDiary,
                )
            }
            item {
                HomeActionRow(
                    icon = Icons.Outlined.MenuBook,
                    title = stringResource(R.string.food_foods),
                    subtitle = stringResource(R.string.home_your_food_database_recipes_and_lookups),
                    onClick = onOpenFoods,
                )
            }
        }
        item {
            HomeActionRow(
                icon = Icons.Outlined.Bluetooth,
                title = stringResource(R.string.home_weigh_in_on_your_scale),
                subtitle = stringResource(R.string.home_read_a_bluetooth_scale_straight_into),
                onClick = onOpenScale,
            )
        }
        item {
            HomeActionRow(
                icon = Icons.Outlined.LocalDrink,
                title = stringResource(R.string.home_water),
                subtitle = waterSummary
                    ?.let { stringResource(R.string.homescreen_today, VolumeFormatter.full(it.totalMl, it.unit), VolumeFormatter.full(it.targetMl, it.unit)) }
                    ?: stringResource(R.string.homescreen_track_what_you_drink),
                onClick = onOpenWater,
            )
        }
        item {
            HomeActionRow(
                icon = Icons.Outlined.Timer,
                title = stringResource(R.string.fasting_fasting),
                subtitle = stringResource(R.string.home_time_an_eating_window),
                onClick = onOpenFasting,
            )
        }
        item {
            HomeActionRow(
                icon = Icons.Outlined.PhotoCamera,
                title = stringResource(R.string.home_progress_photos),
                subtitle = stringResource(R.string.home_compare_how_you_look_over_time),
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
        SectionHeading(stringResource(R.string.home_trend_weight))
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
                text = stringResource(R.string.home_this_week, WeightFormatter.delta(weekChange, unit)),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.Medium,
            )
        }

        snapshot.latestEntry?.let { entry ->
            val deviation = snapshot.series.latestDeviationGrams
            // One sentence per case rather than four pieces glued together. Word order is not
            // the same in every language, and a sentence built by appending cannot be reordered
            // by whoever translates it.
            val whenWeighed = DateFormatters.sinceDay(entry.localDate, today)
            val weighed = WeightFormatter.full(entry.grams, unit)
            val scaleLine = when {
                deviation == null || abs(deviation) <= 150 ->
                    stringResource(R.string.home_last_weighed, whenWeighed, weighed)
                deviation > 0 ->
                    stringResource(R.string.home_last_weighed_above, whenWeighed, weighed)
                else ->
                    stringResource(R.string.home_last_weighed_below, whenWeighed, weighed)
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
                    text = stringResource(R.string.home_days),
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
        SectionHeading(stringResource(R.string.home_rate_of_change))
        Spacer(Modifier.height(8.dp))
        if (!rate.hasEnoughData) {
            Text(
                text = stringResource(R.string.home_keep_logging_a_week_of_readings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatTile(
                label = stringResource(R.string.home_rate),
                value = WeightFormatter.delta(rate.gramsPerWeek, unit, decimals = 2),
                caption = stringResource(R.string.home_per_week),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.home_energy_balance),
                value = stringResource(
                    R.string.home_kcal_a_day_value,
                    abs(rate.impliedKcalPerDay).roundToInt(),
                ),
                caption = if (rate.impliedKcalPerDay < 0) {
                    stringResource(R.string.home_below_maintenance)
                } else {
                    stringResource(R.string.home_above_maintenance)
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (snapshot.isPlateau) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_the_line_has_been_flat_for),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun GoalCard(
    snapshot: ProgressSnapshot,
    onOpenGoal: () -> Unit,
    today: LocalDate,
    onShareProgress: () -> Unit,
) {
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
            Text(stringResource(R.string.home_goal), style = MaterialTheme.typography.titleMedium)
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
                        stringResource(R.string.homescreen_gain, WeightFormatter.full(abs(projection.remainingGrams).roundToInt(), unit))
                    } else {
                        stringResource(R.string.homescreen_go, WeightFormatter.full(abs(projection.remainingGrams).roundToInt(), unit))
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
            // Tapping the date opens the working. A projected date is the thing people most want
            // and least trust, and with good reason.
            var explaining by remember { mutableStateOf(false) }
            if (explaining) {
                com.weighttrack.ui.goal.ProjectionExplainer(
                    projection = projection,
                    unit = unit,
                    today = today,
                    onDismiss = { explaining = false },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                modifier = Modifier.clickable { explaining = true },
                text = when {
                    projection.reached -> stringResource(R.string.home_reached)
                    etaDate == null -> stringResource(R.string.home_not_on_this_trend)
                    else -> stringResource(R.string.home_projected, DateFormatters.projection(etaDate, today))
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
                    text = stringResource(R.string.home_to_based_on_the_last_two, DateFormatters.shortDate(earliest, today), DateFormatters.shortDate(latest, today)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (etaDate == null && !projection.reached) {
                Text(
                    text = stringResource(R.string.home_the_trend_is_not_moving_toward),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        snapshot.nextMilestone?.let { milestone ->
            Spacer(Modifier.height(4.dp))
            val remaining = abs((snapshot.series.latestTrendGrams ?: 0.0) - milestone.grams)
            Text(
                text = stringResource(R.string.home_next_milestone_away_that_is_of, WeightFormatter.full(milestone.grams, unit), WeightFormatter.full(remaining.roundToInt(), unit), milestone.index, milestone.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Offered rather than pushed. There is no prompt, no badge and no reminder to share:
        // somebody who wants to will look for this, and somebody who does not should never be
        // asked.
        if (canShareProgress(snapshot)) {
            androidx.compose.material3.TextButton(onClick = onShareProgress) {
                Text(stringResource(R.string.home_share_your_progress))
            }
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
            SectionHeading(stringResource(R.string.home_body))
            androidx.compose.material3.TextButton(onClick = onOpenMeasurements) { Text(stringResource(R.string.home_measurements)) }
        }

        val hasAnything = snapshot.bmi != null || snapshot.bodyComposition != null ||
            snapshot.basalMetabolicRate != null
        if (!hasAnything) {
            Text(
                text = stringResource(R.string.home_add_your_height_in_settings_and),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            snapshot.bmi?.let { bmi ->
                StatTile(
                    label = stringResource(R.string.home_bmi),
                    value = String.format(locale, "%.1f", bmi),
                    caption = snapshot.bmiCategory?.let { bmiLabel(it) },
                    modifier = Modifier.weight(1f),
                )
            }
            snapshot.bodyComposition?.let { composition ->
                StatTile(
                    label = stringResource(R.string.home_body_fat),
                    value = String.format(locale, "%.1f%%", composition.percent),
                    caption = when (composition.source) {
                        BodyFatSource.LOGGED -> stringResource(R.string.home_from_your_reading)
                        BodyFatSource.NAVY_ESTIMATE -> stringResource(R.string.home_estimated_from_measurements)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            snapshot.basalMetabolicRate?.let { bmr ->
                StatTile(
                    label = stringResource(R.string.home_at_rest),
                    value = "${bmr.roundToInt()}",
                    caption = stringResource(R.string.home_kcal_a_day),
                    modifier = Modifier.weight(1f),
                )
            }
            snapshot.totalDailyEnergyExpenditure?.let { tdee ->
                StatTile(
                    label = stringResource(R.string.home_maintenance),
                    value = "${tdee.roundToInt()}",
                    caption = stringResource(R.string.home_kcal_a_day_at_your_activity_level),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        snapshot.waistToHeightRatio?.let { ratio ->
            Spacer(Modifier.height(4.dp))
            LabelledValue(
                label = stringResource(R.string.home_waist_to_height),
                value = String.format(locale, "%.2f", ratio) +
                    snapshot.waistToHeightCategory?.let { " (${waistLabel(it)})" }.orEmpty(),
            )
        }
        snapshot.healthyRangeGrams?.let { range ->
            LabelledValue(
                label = stringResource(R.string.home_healthy_range),
                value = "${WeightFormatter.value(range.first, unit)} to ${WeightFormatter.full(range.last, unit)}",
            )
        }
        snapshot.bodyComposition?.let { composition ->
            if (composition.leanMassGrams != null && composition.fatMassGrams != null) {
                LabelledValue(
                    label = stringResource(R.string.home_lean_and_fat_mass),
                    value = "${WeightFormatter.value(composition.leanMassGrams, unit)} / ${WeightFormatter.full(composition.fatMassGrams, unit)}",
                )
            }
        }
    }
}

@Composable
private fun bmiLabel(category: BmiCategory): String = when (category) {
    BmiCategory.UNDERWEIGHT -> stringResource(R.string.home_underweight)
    BmiCategory.HEALTHY -> stringResource(R.string.home_healthy)
    BmiCategory.OVERWEIGHT -> stringResource(R.string.home_overweight)
    BmiCategory.OBESE_I -> stringResource(R.string.home_obese_class)
    BmiCategory.OBESE_II -> stringResource(R.string.home_obese_class_2)
    BmiCategory.OBESE_III -> stringResource(R.string.home_obese_class_3)
}

@Composable
private fun waistLabel(category: WaistToHeightCategory): String = when (category) {
    WaistToHeightCategory.LOW -> stringResource(R.string.home_low)
    WaistToHeightCategory.HEALTHY -> stringResource(R.string.home_healthy)
    WaistToHeightCategory.INCREASED -> stringResource(R.string.home_increased_risk)
    WaistToHeightCategory.HIGH -> stringResource(R.string.home_high_risk)
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

/** Whether there is a story worth putting on a card yet. */
internal fun canShareProgress(snapshot: ProgressSnapshot): Boolean =
    milestoneCardFor(snapshot, includeWeight = false) != null
