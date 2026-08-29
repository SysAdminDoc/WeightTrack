package com.weighttrack.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.WeightKeypad
import com.weighttrack.ui.format.LengthFormatter
import com.weighttrack.ui.format.WeightFormatter
import com.weighttrack.ui.theme.HeroNumberStyle
import com.weighttrack.ui.theme.HeroUnitStyle
import kotlin.math.abs

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        StepDots(
            total = OnboardingStep.entries.size,
            current = OnboardingStep.entries.indexOf(state.step),
        )
        Spacer(Modifier.height(24.dp))

        when (state.step) {
            OnboardingStep.UNITS -> UnitsStep(state, viewModel)
            OnboardingStep.ABOUT_YOU -> AboutYouStep(state, viewModel)
            OnboardingStep.FIRST_WEIGHT -> FirstWeightStep(state, viewModel)
            OnboardingStep.GOAL -> GoalStep(state, viewModel)
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = viewModel::next,
            enabled = state.canContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                when (state.step) {
                    OnboardingStep.GOAL -> "Finish"
                    else -> "Continue"
                },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (state.step != OnboardingStep.UNITS) {
                TextButton(onClick = viewModel::back) { Text("Back") }
            } else {
                Spacer(Modifier.size(1.dp))
            }
            when (state.step) {
                OnboardingStep.ABOUT_YOU -> TextButton(onClick = viewModel::next) { Text("Skip") }
                OnboardingStep.GOAL -> TextButton(onClick = viewModel::skipGoal) { Text("No goal for now") }
                else -> Spacer(Modifier.size(1.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepDots(total: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Surface(
                modifier = Modifier.size(if (index == current) 10.dp else 7.dp).clip(CircleShape),
                shape = CircleShape,
                color = if (index <= current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {}
        }
    }
}

@Composable
private fun UnitsStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text("WeightTrack", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "A weight tracker that shows you the trend rather than the noise. No account, no ads, no subscription, and your readings never leave this phone.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(28.dp))
    Text("Weight in", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WeightUnit.entries.forEach { unit ->
            FilterChip(
                selected = state.weightUnit == unit,
                onClick = { viewModel.setWeightUnit(unit) },
                label = {
                    Text(
                        when (unit) {
                            WeightUnit.KG -> "Kilograms"
                            WeightUnit.LB -> "Pounds"
                            WeightUnit.ST_LB -> "Stones"
                        },
                    )
                },
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Text("Measurements in", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LengthUnit.entries.forEach { unit ->
            FilterChip(
                selected = state.lengthUnit == unit,
                onClick = { viewModel.setLengthUnit(unit) },
                label = { Text(if (unit == LengthUnit.CM) "Centimetres" else "Inches") },
            )
        }
    }
}

@Composable
private fun AboutYouStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text("A little about you", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "This is only used to work out BMI, body fat and roughly how many calories you burn. Skip it and everything else still works.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = state.heightText,
        onValueChange = viewModel::setHeightText,
        label = { Text("Height (${LengthFormatter.unitLabel(state.lengthUnit)})") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.birthYearText,
        onValueChange = viewModel::setBirthYearText,
        label = { Text("Year of birth") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Text("Sex", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Sex.entries.forEach { sex ->
            FilterChip(
                selected = state.sex == sex,
                onClick = { viewModel.setSex(sex) },
                label = { Text(if (sex == Sex.MALE) "Male" else "Female") },
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("How active are you", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityLevel.entries.forEach { level ->
            FilterChip(
                selected = state.activityLevel == level,
                onClick = { viewModel.setActivityLevel(level) },
                label = {
                    Text(
                        when (level) {
                            ActivityLevel.SEDENTARY -> "Sedentary"
                            ActivityLevel.LIGHT -> "Light"
                            ActivityLevel.MODERATE -> "Moderate"
                            ActivityLevel.ACTIVE -> "Active"
                            ActivityLevel.VERY_ACTIVE -> "Very active"
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun FirstWeightStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text("What do you weigh today", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "One reading is enough to start. The trend line needs about a week before it means much, so weigh in when you can and ignore the daily jumps until then.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    HeroValue(
        text = com.weighttrack.ui.components.KeypadValue.formatted(state.weightDigits, state.weightUnit),
        unit = state.weightUnit,
        active = state.currentGrams != null,
    )
    Spacer(Modifier.height(12.dp))
    WeightKeypad(
        onDigit = viewModel::onWeightDigit,
        onBackspace = viewModel::onWeightBackspace,
        onClear = viewModel::onWeightClear,
    )
}

@Composable
private fun GoalStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text("Where are you heading", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Set a target and WeightTrack works out a finish date from the rate you actually manage, then breaks the journey into milestones. You can change it or drop it at any time.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    HeroValue(
        text = com.weighttrack.ui.components.KeypadValue.formatted(state.goalDigits, state.weightUnit),
        unit = state.weightUnit,
        active = state.goalGrams != null,
    )
    val current = state.currentGrams
    val goal = state.goalGrams
    if (current != null && goal != null && goal != current) {
        Text(
            text = if (goal < current) {
                "That is ${WeightFormatter.full(abs(current - goal), state.weightUnit)} to lose."
            } else {
                "That is ${WeightFormatter.full(abs(goal - current), state.weightUnit)} to gain."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(12.dp))
    WeightKeypad(
        onDigit = viewModel::onGoalDigit,
        onBackspace = viewModel::onGoalBackspace,
        onClear = viewModel::onGoalClear,
    )
}

@Composable
private fun HeroValue(text: String, unit: WeightUnit, active: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = text,
            style = HeroNumberStyle,
            color = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = " ${if (unit == WeightUnit.ST_LB) "lb" else WeightFormatter.unitLabel(unit)}",
            style = HeroUnitStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}
