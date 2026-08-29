package com.weighttrack.ui.charts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.domain.ProgressCalculator
import com.weighttrack.domain.ProgressSnapshot
import com.weighttrack.health.DailyActivity
import com.weighttrack.health.HealthConnectAvailability
import com.weighttrack.health.HealthConnectSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why the activity card has nothing to draw, so the screen can say which it is. */
enum class ActivityStatus {
    LOADING,
    UNAVAILABLE,
    NOT_PERMITTED,
    NO_DATA,
    READY,
}

data class ActivityState(
    val status: ActivityStatus = ActivityStatus.LOADING,
    val days: List<DailyActivity> = emptyList(),
) {
    val averageSteps: Long?
        get() = days.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()?.toLong()

    val averageActiveKilocalories: Double?
        get() = days.mapNotNull { it.activeKilocalories }.takeIf { it.isNotEmpty() }?.average()
}

@HiltViewModel
class ChartsViewModel @Inject constructor(
    progressCalculator: ProgressCalculator,
    private val healthConnect: HealthConnectSync,
) : ViewModel() {

    val snapshot: StateFlow<ProgressSnapshot> = progressCalculator.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProgressSnapshot.empty(AppSettings()),
        )

    private val _activity = MutableStateFlow(ActivityState())
    val activity: StateFlow<ActivityState> = _activity.asStateFlow()

    init {
        refreshActivity()
    }

    fun refreshActivity() {
        viewModelScope.launch {
            if (healthConnect.availability() != HealthConnectAvailability.INSTALLED) {
                _activity.value = ActivityState(ActivityStatus.UNAVAILABLE)
                return@launch
            }
            if (!healthConnect.hasActivityPermissions()) {
                _activity.value = ActivityState(ActivityStatus.NOT_PERMITTED)
                return@launch
            }
            val days = healthConnect.readDailyActivity()
            _activity.value = ActivityState(
                status = if (days.isEmpty()) ActivityStatus.NO_DATA else ActivityStatus.READY,
                days = days,
            )
        }
    }
}
