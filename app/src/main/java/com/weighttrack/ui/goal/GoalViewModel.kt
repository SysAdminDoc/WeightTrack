package com.weighttrack.ui.goal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.math.Milestones
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.ui.components.KeypadValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class GoalUiState(
    val unit: WeightUnit = WeightUnit.KG,
    val currentGrams: Int? = null,
    val targetDigits: String = "",
    val targetDate: LocalDate? = null,
    val milestoneStepGrams: Int = 0,
    val hasExistingGoal: Boolean = false,
    val saved: Boolean = false,
) {
    val targetGrams: Int get() = KeypadValue.toGrams(targetDigits, unit)
    val canSave: Boolean get() = KeypadValue.isValid(targetDigits) && currentGrams != null
    val formattedTarget: String get() = KeypadValue.formatted(targetDigits, unit)

    val direction: GoalDirection?
        get() = currentGrams?.let { GoalRepository.directionFor(it, targetGrams) }

    /** Change needed per day to land on the chosen date, when one has been chosen. */
    fun requiredGramsPerDay(today: LocalDate = LocalDate.now()): Double? {
        val current = currentGrams ?: return null
        val date = targetDate ?: return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        if (days <= 0) return null
        return (targetGrams - current).toDouble() / days
    }
}

@HiltViewModel
class GoalViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    private val weightRepository: WeightRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GoalUiState())
    val state: StateFlow<GoalUiState> = _state.asStateFlow()

    private var existing: Goal? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val latest = weightRepository.latest()
            val goal = goalRepository.active()
            existing = goal
            _state.update {
                it.copy(
                    unit = settings.weightUnit,
                    currentGrams = latest?.grams,
                    targetDigits = goal?.let { active ->
                        KeypadValue.fromGrams(active.targetGrams, settings.weightUnit)
                    }.orEmpty(),
                    targetDate = goal?.targetDate,
                    milestoneStepGrams = goal?.milestoneStepGrams
                        ?: settings.milestoneStepGrams.takeIf { step -> step > 0 }
                        ?: Milestones.defaultStepGrams(settings.weightUnit),
                    hasExistingGoal = goal != null,
                )
            }
        }
    }

    fun onDigit(digit: Char) = _state.update {
        it.copy(targetDigits = KeypadValue.append(it.targetDigits, digit))
    }

    fun onBackspace() = _state.update {
        it.copy(targetDigits = KeypadValue.backspace(it.targetDigits))
    }

    fun onClear() = _state.update { it.copy(targetDigits = "") }

    fun onTargetDateChange(date: LocalDate?) = _state.update { it.copy(targetDate = date) }

    fun onMilestoneStepChange(grams: Int) = _state.update { it.copy(milestoneStepGrams = grams) }

    /** The two spacings people actually pick, offered in whichever unit they read. */
    fun milestoneOptions(): List<Pair<String, Int>> = when (state.value.unit) {
        WeightUnit.KG -> listOf("1 kg" to 1_000, "2 kg" to 2_000, "5 kg" to 5_000)
        WeightUnit.LB, WeightUnit.ST_LB -> listOf(
            "2 lb" to UnitConverter.lbToGrams(2.0),
            "5 lb" to UnitConverter.lbToGrams(5.0),
            "10 lb" to UnitConverter.lbToGrams(10.0),
        )
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        val startGrams = existing?.startGrams ?: current.currentGrams ?: return
        viewModelScope.launch {
            goalRepository.setGoal(
                startGrams = startGrams,
                targetGrams = current.targetGrams,
                milestoneStepGrams = current.milestoneStepGrams,
                startDate = existing?.startDate ?: LocalDate.now(),
                targetDate = current.targetDate,
            )
            settingsRepository.setMilestoneStepGrams(current.milestoneStepGrams)
            _state.update { it.copy(saved = true) }
        }
    }

    fun clearGoal() {
        viewModelScope.launch {
            goalRepository.clearActive()
            _state.update { it.copy(saved = true) }
        }
    }
}
