package com.weighttrack.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.model.VolumeUnit
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.WaterRepository
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.domain.ProgressCalculator
import com.weighttrack.domain.ProgressSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    progressCalculator: ProgressCalculator,
    waterRepository: WaterRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val snapshot: StateFlow<ProgressSnapshot> = progressCalculator.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProgressSnapshot.empty(AppSettings()),
        )

    val waterSummary: StateFlow<WaterSummary?> = combine(
        waterRepository.observeTotalForDate(LocalDate.now()),
        settingsRepository.settings,
    ) { total, settings ->
        WaterSummary(
            totalMl = total,
            targetMl = settings.waterTargetMl,
            unit = VolumeUnit.forWeightUnit(settings.weightUnit),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Just enough water detail for the home row; the water screen owns the rest. */
data class WaterSummary(
    val totalMl: Int,
    val targetMl: Int,
    val unit: VolumeUnit,
)
