package com.weighttrack.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.WeightKeypad
import com.weighttrack.ui.components.SegmentButton
import com.weighttrack.core.format.LengthFormatter
import com.weighttrack.core.format.WeightFormatter
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
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.onboarding_weighttrack), style = MaterialTheme.typography.titleLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.app_privacy_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(30.dp))
        StepProgress(
            total = OnboardingStep.entries.size,
            current = OnboardingStep.entries.indexOf(state.step),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_of, OnboardingStep.entries.indexOf(state.step) + 1, OnboardingStep.entries.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        when (state.step) {
            OnboardingStep.UNITS -> UnitsStep(state, viewModel)
            OnboardingStep.ABOUT_YOU -> AboutYouStep(state, viewModel)
            OnboardingStep.FIRST_WEIGHT -> FirstWeightStep(state, viewModel)
            OnboardingStep.GOAL -> GoalStep(state, viewModel)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = viewModel::next,
            enabled = state.canContinue,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                when (state.step) {
                    OnboardingStep.GOAL -> stringResource(R.string.onboarding_finish)
                    else -> stringResource(R.string.onboarding_continue)
                },
            )
        }
        if (state.step == OnboardingStep.UNITS) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.onboarding_no_account_no_ads_stored_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (state.step != OnboardingStep.UNITS) {
                TextButton(onClick = viewModel::back) { Text(stringResource(R.string.common_back)) }
            } else {
                Spacer(Modifier.size(1.dp))
            }
            when (state.step) {
                OnboardingStep.ABOUT_YOU -> TextButton(onClick = viewModel::next) { Text(stringResource(R.string.onboarding_skip)) }
                OnboardingStep.GOAL -> TextButton(onClick = viewModel::skipGoal) { Text(stringResource(R.string.onboarding_no_goal_for_now)) }
                else -> Spacer(Modifier.size(1.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StepProgress(total: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(total) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(
                        color = if (index <= current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
            )
        }
    }
}

@Composable
private fun UnitsStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text(
        stringResource(R.string.onboarding_your_weight_your_data),
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.fillMaxWidth(0.70f),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.onboarding_track_the_trend_without_an_account),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    Text(stringResource(R.string.onboarding_weight), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WeightUnit.entries.forEach { unit ->
            SegmentButton(
                label = when (unit) {
                    WeightUnit.KG -> stringResource(R.string.onboarding_kilograms)
                    WeightUnit.LB -> stringResource(R.string.onboarding_pounds)
                    WeightUnit.ST_LB -> stringResource(R.string.onboarding_stones)
                },
                selected = state.weightUnit == unit,
                onClick = { viewModel.setWeightUnit(unit) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.home_measurements), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(0.68f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LengthUnit.entries.forEach { unit ->
            SegmentButton(
                label = if (unit == LengthUnit.CM) {
                    stringResource(R.string.settings_centimetres)
                } else {
                    stringResource(R.string.settings_inches)
                },
                selected = state.lengthUnit == unit,
                onClick = { viewModel.setLengthUnit(unit) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AboutYouStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text(stringResource(R.string.onboarding_a_little_about_you), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.onboarding_this_is_only_used_to_work),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = state.heightText,
        onValueChange = viewModel::setHeightText,
        label = { Text(stringResource(R.string.onboarding_height, LengthFormatter.unitLabel(state.lengthUnit))) },
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
        label = { Text(stringResource(R.string.onboarding_year_of_birth)) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.onboarding_sex), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Sex.entries.forEach { sex ->
            SegmentButton(
                label = stringResource(
                    if (sex == Sex.MALE) {
                        R.string.onboardingscreen_male
                    } else {
                        R.string.onboardingscreen_female
                    },
                ),
                selected = state.sex == sex,
                onClick = { viewModel.setSex(sex) },
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.onboarding_how_active_are_you), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityLevel.entries.forEach { level ->
            SegmentButton(
                label = when (level) {
                    ActivityLevel.SEDENTARY -> stringResource(R.string.onboarding_sedentary)
                    ActivityLevel.LIGHT -> stringResource(R.string.onboarding_light)
                    ActivityLevel.MODERATE -> stringResource(R.string.onboarding_moderate)
                    ActivityLevel.ACTIVE -> stringResource(R.string.onboarding_active)
                    ActivityLevel.VERY_ACTIVE -> stringResource(R.string.onboarding_very_active)
                },
                selected = state.activityLevel == level,
                onClick = { viewModel.setActivityLevel(level) },
            )
        }
    }
}

@Composable
private fun FirstWeightStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text(stringResource(R.string.onboarding_what_do_you_weigh_today), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.onboarding_one_reading_is_enough_to_start),
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
    Text(stringResource(R.string.onboarding_where_are_you_heading), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.onboarding_set_a_target_and_weighttrack_works),
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
                stringResource(R.string.onboardingscreen_that_lose, WeightFormatter.full(abs(current - goal), state.weightUnit))
            } else {
                stringResource(R.string.onboardingscreen_that_gain, WeightFormatter.full(abs(goal - current), state.weightUnit))
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
