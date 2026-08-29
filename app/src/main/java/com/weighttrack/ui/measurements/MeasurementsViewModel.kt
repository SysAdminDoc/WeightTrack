package com.weighttrack.ui.measurements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.BodyMeasurement
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.MeasurementRepository
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

data class MeasurementEditor(
    val type: MeasurementType,
    val text: String,
)

@HiltViewModel
class MeasurementsViewModel @Inject constructor(
    private val measurementRepository: MeasurementRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<MeasurementsUiState> = combine(
        measurementRepository.observeLatestPerType(),
        settingsRepository.settings.map { it.lengthUnit },
    ) { latest, unit ->
        MeasurementsUiState(latest = latest, lengthUnit = unit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeasurementsUiState())

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
        val value = editing.text.trim().replace(',', '.').toDoubleOrNull()
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
        viewModelScope.launch { measurementRepository.delete(measurement) }
    }
}
