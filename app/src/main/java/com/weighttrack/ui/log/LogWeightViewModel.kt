package com.weighttrack.ui.log

import com.weighttrack.core.format.LocaleNumbers
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.ui.components.KeypadValue
import com.weighttrack.ui.navigation.Routes
import com.weighttrack.widget.SurfaceUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class LogWeightUiState(
    val digits: String = "",
    val unit: WeightUnit = WeightUnit.KG,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val note: String = "",
    val tags: Set<EntryTag> = emptySet(),
    val bodyFatText: String = "",
    val editingId: Long? = null,
    val prefilled: Boolean = false,
    val saved: Boolean = false,
) {
    val isEditing: Boolean get() = editingId != null
    val canSave: Boolean get() = KeypadValue.isValid(digits)
    val grams: Int get() = KeypadValue.toGrams(digits, unit)
    val formattedValue: String get() = KeypadValue.formatted(digits, unit)
}

@HiltViewModel
class LogWeightViewModel @Inject constructor(
    private val weightRepository: WeightRepository,
    private val settingsRepository: SettingsRepository,
    private val SurfaceUpdater: SurfaceUpdater,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editingId: Long? = savedStateHandle.get<String>(Routes.ENTRY_ID_ARG)?.toLongOrNull()

    private val _state = MutableStateFlow(LogWeightUiState(editingId = editingId))
    val state: StateFlow<LogWeightUiState> = _state.asStateFlow()

    private var existing: WeightEntry? = null

    init {
        viewModelScope.launch {
            val unit = settingsRepository.settings.first().weightUnit
            if (editingId != null) {
                val entry = weightRepository.byId(editingId)
                existing = entry
                if (entry != null) {
                    val zoned = entry.timestamp.atZone(ZoneId.systemDefault())
                    _state.update {
                        it.copy(
                            digits = KeypadValue.fromGrams(entry.grams, unit),
                            unit = unit,
                            date = entry.localDate,
                            time = zoned.toLocalTime().withSecond(0).withNano(0),
                            note = entry.note.orEmpty(),
                            tags = entry.tags,
                            bodyFatText = entry.bodyFatPercent?.toString().orEmpty(),
                            prefilled = true,
                        )
                    }
                    return@launch
                }
            }
            // A new reading starts at the last one. Most days the weight has barely moved, so
            // the fastest possible log is opening this screen and hitting save.
            val last = weightRepository.latest()
            _state.update {
                it.copy(
                    unit = unit,
                    digits = last?.let { entry -> KeypadValue.fromGrams(entry.grams, unit) }.orEmpty(),
                    prefilled = last != null,
                )
            }
        }
    }

    /** The first digit typed replaces the prefilled value rather than appending to it. */
    fun onDigit(digit: Char) {
        _state.update { current ->
            val base = if (current.prefilled) "" else current.digits
            current.copy(digits = KeypadValue.append(base, digit), prefilled = false)
        }
    }

    fun onBackspace() {
        _state.update { it.copy(digits = KeypadValue.backspace(it.digits), prefilled = false) }
    }

    fun onClear() {
        _state.update { it.copy(digits = "", prefilled = false) }
    }

    fun onDateChange(date: LocalDate) = _state.update { it.copy(date = date) }

    fun onTimeChange(time: LocalTime) = _state.update { it.copy(time = time) }

    fun onNoteChange(note: String) = _state.update { it.copy(note = note) }

    fun onBodyFatChange(text: String) = _state.update { it.copy(bodyFatText = text) }

    fun toggleTag(tag: EntryTag) = _state.update { current ->
        current.copy(
            tags = if (tag in current.tags) current.tags - tag else current.tags + tag,
        )
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val instant = current.date.atTime(current.time).atZone(zone).toInstant()
            val bodyFat = LocaleNumbers.decimal(current.bodyFatText)?.takeIf { it in 1.0..75.0 }
            val previous = existing
            if (previous != null) {
                weightRepository.update(
                    previous.copy(
                        timestamp = instant,
                        zoneOffset = zone.rules.getOffset(instant),
                        localDate = current.date,
                        grams = current.grams,
                        bodyFatPercent = bodyFat,
                        note = current.note.trim().takeIf { it.isNotEmpty() },
                        tags = current.tags,
                    ),
                )
            } else {
                weightRepository.add(
                    grams = current.grams,
                    timestamp = instant,
                    zone = zone,
                    bodyFatPercent = bodyFat,
                    note = current.note.trim().takeIf { it.isNotEmpty() },
                    tags = current.tags,
                )
            }
            SurfaceUpdater.refresh()
            _state.update { it.copy(saved = true) }
        }
    }
}
