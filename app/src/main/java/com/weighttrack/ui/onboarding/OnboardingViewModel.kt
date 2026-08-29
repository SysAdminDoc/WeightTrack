package com.weighttrack.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.math.Milestones
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.UserProfile
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.ui.components.KeypadValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep {
    UNITS,
    ABOUT_YOU,
    FIRST_WEIGHT,
    GOAL,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.UNITS,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val lengthUnit: LengthUnit = LengthUnit.CM,
    val heightText: String = "",
    val sex: Sex = Sex.MALE,
    val birthYearText: String = "",
    val activityLevel: ActivityLevel = ActivityLevel.LIGHT,
    val weightDigits: String = "",
    val goalDigits: String = "",
    val finished: Boolean = false,
) {
    val currentGrams: Int? get() =
        if (KeypadValue.isValid(weightDigits)) KeypadValue.toGrams(weightDigits, weightUnit) else null

    val goalGrams: Int? get() =
        if (KeypadValue.isValid(goalDigits)) KeypadValue.toGrams(goalDigits, weightUnit) else null

    val canContinue: Boolean get() = when (step) {
        OnboardingStep.UNITS -> true
        // Height and age are optional. Refusing to move on would be asking for personal
        // details as the price of entry, which is exactly what the paid apps do.
        OnboardingStep.ABOUT_YOU -> true
        OnboardingStep.FIRST_WEIGHT -> currentGrams != null
        OnboardingStep.GOAL -> true
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val weightRepository: WeightRepository,
    private val goalRepository: GoalRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun setWeightUnit(unit: WeightUnit) = _state.update { it.copy(weightUnit = unit) }

    fun setLengthUnit(unit: LengthUnit) = _state.update { it.copy(lengthUnit = unit) }

    fun setHeightText(text: String) = _state.update { it.copy(heightText = text) }

    fun setSex(sex: Sex) = _state.update { it.copy(sex = sex) }

    fun setBirthYearText(text: String) = _state.update {
        it.copy(birthYearText = text.filter { char -> char.isDigit() }.take(4))
    }

    fun setActivityLevel(level: ActivityLevel) = _state.update { it.copy(activityLevel = level) }

    fun onWeightDigit(digit: Char) = _state.update {
        it.copy(weightDigits = KeypadValue.append(it.weightDigits, digit))
    }

    fun onWeightBackspace() = _state.update {
        it.copy(weightDigits = KeypadValue.backspace(it.weightDigits))
    }

    fun onWeightClear() = _state.update { it.copy(weightDigits = "") }

    fun onGoalDigit(digit: Char) = _state.update {
        it.copy(goalDigits = KeypadValue.append(it.goalDigits, digit))
    }

    fun onGoalBackspace() = _state.update {
        it.copy(goalDigits = KeypadValue.backspace(it.goalDigits))
    }

    fun onGoalClear() = _state.update { it.copy(goalDigits = "") }

    fun next() {
        val current = _state.value
        val order = OnboardingStep.entries
        val index = order.indexOf(current.step)
        if (index == order.lastIndex) {
            finish()
        } else {
            _state.update { it.copy(step = order[index + 1]) }
        }
    }

    fun back() {
        val order = OnboardingStep.entries
        val index = order.indexOf(_state.value.step)
        if (index > 0) _state.update { it.copy(step = order[index - 1]) }
    }

    /** Skips the goal step, since a goal is genuinely optional for someone just watching a trend. */
    fun skipGoal() {
        _state.update { it.copy(goalDigits = "") }
        finish()
    }

    private fun finish() {
        val current = _state.value
        viewModelScope.launch {
            settingsRepository.setWeightUnit(current.weightUnit)
            settingsRepository.setLengthUnit(current.lengthUnit)
            settingsRepository.setProfile(
                UserProfile(
                    heightMm = current.heightText.trim().replace(',', '.').toDoubleOrNull()
                        ?.takeIf { it > 0 }
                        ?.let { UnitConverter.displayToMm(it, current.lengthUnit) }
                        ?: 0,
                    sex = current.sex,
                    birthYear = current.birthYearText.toIntOrNull()?.takeIf { it in 1900..2100 } ?: 0,
                    activityLevel = current.activityLevel,
                ),
            )
            current.currentGrams?.let { grams ->
                weightRepository.add(grams = grams)
                current.goalGrams?.let { target ->
                    goalRepository.setGoal(
                        startGrams = grams,
                        targetGrams = target,
                        milestoneStepGrams = Milestones.defaultStepGrams(current.weightUnit),
                    )
                }
            }
            settingsRepository.setOnboardingComplete(true)
            _state.update { it.copy(finished = true) }
        }
    }
}
