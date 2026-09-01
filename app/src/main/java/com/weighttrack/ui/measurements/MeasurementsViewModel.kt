package com.weighttrack.ui.measurements

import com.weighttrack.core.format.LocaleNumbers
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.BodyMeasurement
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.MeasurementRepository
import com.weighttrack.R
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.UndoCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeasurementsUiState(
    val latest: Map<MeasurementType, BodyMeasurement> = emptyMap(),
    val lengthUnit: LengthUnit = LengthUnit.CM,
)

/**
 * A whole set being entered at once.
 *
 * [values] holds every site as text, seeded from the last time each was measured. [changed] is
 * which of them somebody has touched, and is the difference between a measurement and a value
 * carried forward.
 */
data class MeasurementSet(
    val values: Map<MeasurementType, String>,
    val changed: Set<MeasurementType>,
) {
    val hasAnyChange: Boolean get() = changed.isNotEmpty()

    fun isCarried(type: MeasurementType): Boolean =
        type !in changed && !values[type].isNullOrBlank()
}

data class MeasurementEditor(
    val type: MeasurementType,
    val text: String,
)

@HiltViewModel
class MeasurementsViewModel @Inject constructor(
    private val measurementRepository: MeasurementRepository,
    private val settingsRepository: SettingsRepository,
    private val strings: AppStrings,
    private val undoOffers: UndoCoordinator,
) : ViewModel() {

    val state: StateFlow<MeasurementsUiState> = combine(
        measurementRepository.observeLatestPerType(),
        settingsRepository.settings.map { it.lengthUnit },
    ) { latest, unit ->
        MeasurementsUiState(latest = latest, lengthUnit = unit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeasurementsUiState())

    private val _set = MutableStateFlow<MeasurementSet?>(null)

    /** The whole-set editor, when one is open. */
    val measurementSet: StateFlow<MeasurementSet?> = _set.asStateFlow()

    /**
     * Opens a set with every site filled in from the last time it was measured.
     *
     * Thirteen sites retyped every time is why people stop measuring, so the carried values are
     * there to be kept and only the ones somebody actually changes count as measured.
     */
    fun startSet() {
        val unit = state.value.lengthUnit
        _set.value = MeasurementSet(
            values = MeasurementType.entries.associateWith { type ->
                state.value.latest[type]
                    ?.let { formatted(it.valueMm, unit) }
                    .orEmpty()
            },
            changed = emptySet(),
        )
    }

    fun onSetValueChange(type: MeasurementType, text: String) {
        _set.update { open ->
            open?.copy(
                values = open.values + (type to text),
                // Typing the same number back is still a measurement: somebody checked.
                changed = open.changed + type,
            )
        }
    }

    fun cancelSet() {
        _set.value = null
    }

    /**
     * Writes the set, or nothing at all.
     *
     * A set nobody changed is a screen somebody opened and closed again. Recording thirteen
     * carried values for it would put a measurement on a day when none was taken.
     */
    fun saveSet() {
        val open = _set.value ?: return
        val unit = state.value.lengthUnit
        val millimetres = open.values.mapNotNull { (type, text) ->
            LocaleNumbers.decimal(text)
                ?.takeIf { it > 0 }
                ?.let { type to UnitConverter.displayToMm(it, unit) }
        }.toMap()
        val measured = millimetres.filterKeys { it in open.changed }
        if (measured.isEmpty()) {
            _set.value = null
            return
        }
        viewModelScope.launch {
            measurementRepository.addSet(
                measured = measured,
                carried = millimetres.filterKeys { it !in open.changed },
            )
            _set.value = null
        }
    }

    private fun formatted(valueMm: Int, unit: LengthUnit): String =
        String.format(
            java.util.Locale.getDefault(),
            "%.1f",
            UnitConverter.mmToDisplay(valueMm, unit),
        )
    private val _editor = MutableStateFlow<MeasurementEditor?>(null)
    val editor: StateFlow<MeasurementEditor?> = _editor.asStateFlow()

    /** Opens the editor pre-filled with the current value, so a small correction is one edit. */
    fun startEditing(type: MeasurementType) {
        val existing = state.value.latest[type]
        val unit = state.value.lengthUnit
        _editor.value = MeasurementEditor(
            type = type,
            text = existing?.let {
                String.format(java.util.Locale.getDefault(), "%.1f", UnitConverter.mmToDisplay(it.valueMm, unit))
            }.orEmpty(),
        )
    }

    fun onEditorTextChange(text: String) {
        _editor.update { it?.copy(text = text) }
    }

    fun cancelEditing() {
        _editor.value = null
    }

    fun saveEditor() {
        val editing = _editor.value ?: return
        val value = LocaleNumbers.decimal(editing.text)
        if (value == null || value <= 0) {
            _editor.value = null
            return
        }
        val mm = UnitConverter.displayToMm(value, state.value.lengthUnit)
        viewModelScope.launch {
            measurementRepository.add(type = editing.type, valueMm = mm)
            _editor.value = null
        }
    }

    fun delete(measurement: BodyMeasurement) {
        viewModelScope.launch {
            undoOffers.offer(
                measurementRepository.delete(measurement),
                strings[R.string.measurements_measurement_deleted],
            )
        }
    }
}
