package com.weighttrack.ui.water

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.model.VolumeUnit
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.DailyWater
import com.weighttrack.data.repo.WaterEntry
import com.weighttrack.data.repo.WaterRepository
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.R
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.UndoCoordinator
import com.weighttrack.widget.SurfaceUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

data class WaterUiState(
    val date: LocalDate = LocalDate.now(),
    val totalMl: Int = 0,
    val targetMl: Int = 2_000,
    val servingMl: Int = 250,
    val unit: VolumeUnit = VolumeUnit.ML,
    val entries: List<WaterEntry> = emptyList(),
    val recentDays: List<DailyWater> = emptyList(),
) {
    val progress: Float
        get() = if (targetMl <= 0) 0f else (totalMl.toFloat() / targetMl).coerceIn(0f, 1f)

    val remainingMl: Int get() = (targetMl - totalMl).coerceAtLeast(0)
    val targetReached: Boolean get() = totalMl >= targetMl
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WaterViewModel @Inject constructor(
    private val waterRepository: WaterRepository,
    private val settingsRepository: SettingsRepository,
    private val healthConnect: HealthConnectSync,
    private val SurfaceUpdater: SurfaceUpdater,
    private val strings: AppStrings,
    private val undoOffers: UndoCoordinator,
) : ViewModel() {

    /**
     * The day being shown. Held as state rather than read from the clock on every emission so
     * the screen does not silently jump to tomorrow while someone is looking at it.
     */
    private val selectedDate = MutableStateFlow(LocalDate.now())

    private val dayData = selectedDate.flatMapLatest { date ->
        combine(
            waterRepository.observeTotalForDate(date),
            waterRepository.observeForDate(date),
        ) { total, entries -> Triple(date, total, entries) }
    }

    val state: StateFlow<WaterUiState> = combine(
        dayData,
        settingsRepository.settings,
        waterRepository.observeRecentDays(),
    ) { (date, total, entries), settings, recent ->
        WaterUiState(
            date = date,
            totalMl = total,
            targetMl = settings.waterTargetMl,
            servingMl = settings.waterServingMl,
            unit = VolumeUnit.forWeightUnit(settings.weightUnit),
            entries = entries,
            recentDays = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WaterUiState())

    fun addServing() = add(state.value.servingMl)

    fun add(millilitres: Int) {
        if (millilitres <= 0) return
        viewModelScope.launch {
            WaterLogger.log(
                millilitres = millilitres,
                onDate = state.value.date,
                waterRepository = waterRepository,
                healthConnect = healthConnect,
            )
            SurfaceUpdater.refresh()
        }
    }

    fun remove(entry: WaterEntry) {
        viewModelScope.launch {
            val removed = waterRepository.delete(entry)
            SurfaceUpdater.refresh()
            undoOffers.offer(removed, strings[R.string.water_drink_removed]) {
                SurfaceUpdater.refresh()
            }
        }
    }

    fun clearDay() {
        viewModelScope.launch {
            val removed = waterRepository.clearDate(state.value.date)
            SurfaceUpdater.refresh()
            undoOffers.offer(removed, strings[R.string.water_day_cleared]) {
                SurfaceUpdater.refresh()
            }
        }
    }


    fun showPreviousDay() {
        selectedDate.value = selectedDate.value.minusDays(1)
    }

    /** Never walks past today; there is nothing to log in the future. */
    fun showNextDay() {
        val next = selectedDate.value.plusDays(1)
        if (!next.isAfter(LocalDate.now())) selectedDate.value = next
    }

    fun setTarget(millilitres: Int) {
        viewModelScope.launch { settingsRepository.setWaterTargetMl(millilitres) }
    }

    fun setServing(millilitres: Int) {
        viewModelScope.launch { settingsRepository.setWaterServingMl(millilitres) }
    }
}
