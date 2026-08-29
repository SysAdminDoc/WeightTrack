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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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

    /**
     * Re-reads which day it is rather than capturing it once.
     *
     * The home view model lives as long as the process, so a captured date would leave the
     * water row summing yesterday and calling it today after midnight or a flight.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val waterSummary: StateFlow<WaterSummary?> = combine(
        currentDay().flatMapLatest { waterRepository.observeTotalForDate(it) },
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

/**
 * Emits today's date, and again each time the day rolls over.
 *
 * Polling once a minute rather than scheduling at midnight keeps it correct through a manual
 * clock change or a timezone change too, which a single scheduled tick would miss.
 */
private fun currentDay(): Flow<LocalDate> = flow {
    var last = LocalDate.now()
    emit(last)
    while (true) {
        delay(60_000)
        val today = LocalDate.now()
        if (today != last) {
            last = today
            emit(today)
        }
    }
}.distinctUntilChanged()
