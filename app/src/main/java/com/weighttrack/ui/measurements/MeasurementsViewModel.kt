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
import kotlinx.coroutines.flow.first
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
    /** What each site was stored as, in millimetres, for the ones nobody touches. */
    val original: Map<MeasurementType, Int>,
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
    fun startSet() = viewModelScope.launch {
        // Read from the repository rather than from the state flow. That flow is only warm while
        // something is collecting it, so a set opened a moment too early would come up blank and
        // silently write one measurement instead of a set.
        val unit = settingsRepository.settings.first().lengthUnit
        val latest = measurementRepository.observeLatestPerType().first()
        _set.value = MeasurementSet(
            values = MeasurementType.entries.associateWith { type ->
                latest[type]?.let { formatted(it.valueMm, unit) }.orEmpty()
            },
            // The millimetres as they were stored, kept beside the text. A carried value is
            // written from these rather than from what is in the box, because the box holds one
            // decimal place of inches: 865 mm reads as 34.1 in and parses back as 866, so a
            // value nobody touched came out a millimetre different from the one it copied.
            original = latest.mapValues { (_, measurement) -> measurement.valueMm },
            changed = emptySet(),
        )
    }

    fun onSetValueChange(type: MeasurementType, text: String) {
        _set.update { open ->
            open ?: return@update null
            open.copy(
                values = open.values + (type to text),
                // Typing the same number back is still a measurement: somebody checked. Clearing
                // the box is not: it is somebody part way through retyping, and treating it as a
                // change dropped that site out of the set without saying so.
                changed = if (text.isBlank()) open.changed - type else open.changed + type,
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
    fun saveSet() = viewModelScope.launch {
        val unit = settingsRepository.settings.first().lengthUnit
        val open = _set.value ?: return@launch
        val measured = open.values
            .filterKeys { it in open.changed }
            .mapNotNull { (type, text) ->
                LocaleNumbers.decimal(text)
                    ?.takeIf { it > 0 }
                    ?.let { type to UnitConverter.displayToMm(it, unit) }
            }
            .toMap()
        // Untouched sites keep the millimetres they were stored with. Nothing round-trips
        // through the text, so nothing changes by being carried.
        val carried = open.original.filterKeys { it !in open.changed }
        if (measured.isEmpty()) {
            _set.value = null
            return@launch
        }
        run {
            measurementRepository.addSet(
                measured = measured,
                carried = carried,
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
