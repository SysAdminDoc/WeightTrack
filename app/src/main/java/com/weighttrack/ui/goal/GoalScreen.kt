package com.weighttrack.ui.goal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.math.Milestones
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.GoalProgressBar
import com.weighttrack.ui.components.LedgerSection
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.SegmentButton
import com.weighttrack.ui.components.WeightKeypad
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.ui.theme.HeroNumberStyle
import com.weighttrack.ui.theme.HeroUnitStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalScreen(
    state: GoalUiState,
    milestoneOptions: List<Pair<String, Int>>,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onTargetDateChange: (LocalDate?) -> Unit,
    onMilestoneStepChange: (Int) -> Unit,
    onSave: () -> Unit,
    onClearGoal: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onClose()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (state.hasExistingGoal) stringResource(R.string.goalscreen_edit_goal) else stringResource(R.string.goalscreen_set_goal_2)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.currentGrams == null) {
                Text(
                    text = stringResource(R.string.goal_log_a_weight_first_a_goal),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 24.dp),
                )
                return@Column
            }

            Text(
                text = stringResource(R.string.goal_target_weight),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.formattedTarget,
                    style = HeroNumberStyle,
                    color = if (state.canSave) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = " ${if (state.unit == WeightUnit.ST_LB) "lb" else WeightFormatter.unitLabel(state.unit)}",
                    style = HeroUnitStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            if (state.canSave) {
                val change = state.targetGrams - state.currentGrams
                Text(
                    text = when (state.direction) {
                        GoalDirection.LOSE -> stringResource(R.string.goal_to_lose, WeightFormatter.full(abs(change), state.unit))
                        GoalDirection.GAIN -> stringResource(R.string.goal_to_gain, WeightFormatter.full(abs(change), state.unit))
                        else -> stringResource(R.string.goal_holding_steady_at_your_current_weight)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            WeightKeypad(onDigit = onDigit, onBackspace = onBackspace, onClear = onClear)

            LedgerSection(contentPadding = PaddingValues(vertical = 14.dp)) {
                SectionHeading(stringResource(R.string.goal_milestones))
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    milestoneOptions.forEach { (label, grams) ->
                        SegmentButton(
                            label = label,
                            selected = state.milestoneStepGrams == grams,
                            onClick = { onMilestoneStepChange(grams) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (state.canSave && state.direction != GoalDirection.MAINTAIN) {
                    val milestones = Milestones.generate(
                        state.currentGrams,
                        state.targetGrams,
                        state.milestoneStepGrams,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.goal_milestones_on_the_way, milestones.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    GoalProgressBar(
                        progress = 1f,
                        milestoneFractions = milestones.indices.map { (it + 1f) / milestones.size },
                        reachedCount = 0,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        milestones.forEach { milestone ->
                            Text(
                                text = WeightFormatter.full(milestone.grams, state.unit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            LedgerSection(contentPadding = PaddingValues(vertical = 14.dp)) {
                SectionHeading(stringResource(R.string.goal_target_date))
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { showDatePicker = true }) {
                        Text(state.targetDate?.let { DateFormatters.fullDate(it) } ?: stringResource(R.string.goalscreen_pick_date))
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.targetDate != null) {
                        TextButton(onClick = { onTargetDateChange(null) }) { Text(stringResource(R.string.goal_clear)) }
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.requiredGramsPerDay(today)?.let { perDay ->
                    val perWeek = perDay * 7
                    LabelledValue(
                        label = stringResource(R.string.goal_pace_needed),
                        value = WeightFormatter.ratePerWeek(perWeek, state.unit),
                    )
                    // Faster than about 1% of body mass a week is not a plan, it is a crash diet.
                    val safeLimit = state.currentGrams * 0.01
                    if (abs(perWeek) > safeLimit) {
                        Text(
                            text = stringResource(R.string.goal_that_is_a_fast_pace_losing, WeightFormatter.full(safeLimit.roundToInt(), state.unit)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            Button(
                onClick = onSave,
                enabled = state.canSave,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.hasExistingGoal) stringResource(R.string.goalscreen_save_goal) else stringResource(R.string.goalscreen_set_goal))
            }

            if (state.hasExistingGoal) {
                TextButton(
                    onClick = onClearGoal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.goal_remove_goal), color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val initial = (state.targetDate ?: today.plusMonths(3))
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onTargetDateChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.common_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
