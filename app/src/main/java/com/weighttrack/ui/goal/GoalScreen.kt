package com.weighttrack.ui.goal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.weighttrack.core.math.Milestones
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.WeightKeypad
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.ui.format.WeightFormatter
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
                title = { Text(if (state.hasExistingGoal) "Edit goal" else "Set a goal") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.currentGrams == null) {
                Text(
                    text = "Log a weight first. A goal needs a starting point to measure from.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 24.dp),
                )
                return@Column
            }

            Text(
                text = "Target weight",
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
                        GoalDirection.LOSE -> "Losing ${WeightFormatter.full(abs(change), state.unit)} from where you are now."
                        GoalDirection.GAIN -> "Gaining ${WeightFormatter.full(abs(change), state.unit)} from where you are now."
                        else -> "Holding steady at your current weight."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            WeightKeypad(onDigit = onDigit, onBackspace = onBackspace, onClear = onClear)

            SectionCard {
                SectionHeading("Milestones")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "A big goal is easier to hold on to when it is broken into steps you actually reach.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    milestoneOptions.forEach { (label, grams) ->
                        FilterChip(
                            selected = state.milestoneStepGrams == grams,
                            onClick = { onMilestoneStepChange(grams) },
                            label = { Text(label) },
                        )
                    }
                }
                if (state.canSave && state.direction != GoalDirection.MAINTAIN) {
                    val count = Milestones.generate(
                        state.currentGrams,
                        state.targetGrams,
                        state.milestoneStepGrams,
                    ).size
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "That gives you $count milestones along the way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard {
                SectionHeading("Target date")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Optional. WeightTrack projects a date from your actual rate either way; setting one here just tells you what pace it would take.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showDatePicker = true }) {
                        Text(state.targetDate?.let { DateFormatters.fullDate(it) } ?: "Pick a date")
                    }
                    if (state.targetDate != null) {
                        TextButton(onClick = { onTargetDateChange(null) }) { Text("Clear") }
                    }
                }
                state.requiredGramsPerDay(today)?.let { perDay ->
                    val perWeek = perDay * 7
                    LabelledValue(
                        label = "Pace needed",
                        value = WeightFormatter.ratePerWeek(perWeek, state.unit),
                    )
                    // Faster than about 1% of body mass a week is not a plan, it is a crash diet.
                    val safeLimit = state.currentGrams * 0.01
                    if (abs(perWeek) > safeLimit) {
                        Text(
                            text = "That is a fast pace. Losing more than about ${WeightFormatter.full(safeLimit.roundToInt(), state.unit)} a week is hard to hold and costs you muscle. Consider a later date.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.hasExistingGoal) "Save goal" else "Set goal")
            }

            if (state.hasExistingGoal) {
                TextButton(
                    onClick = onClearGoal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove goal", color = MaterialTheme.colorScheme.error)
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
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
