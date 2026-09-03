package com.weighttrack.ui.charts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.math.Insights
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.domain.ProgressCalculator
import com.weighttrack.domain.ProgressSnapshot
import com.weighttrack.health.DailyActivity
import com.weighttrack.health.HealthConnectAvailability
import com.weighttrack.health.HealthOutcome
import com.weighttrack.health.HealthConnectSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Why the activity card has nothing to draw, so the screen can say which it is. */
enum class ActivityStatus {
    LOADING,
    UNAVAILABLE,
    NOT_PERMITTED,
    NO_DATA,

    /**
     * Health Connect was asked and something went wrong.
     *
     * Kept apart from [NO_DATA] on purpose. Telling somebody who walks every day that they have
     * no step counts is worse than telling them nothing, because it is an answer and it is wrong.
     */
    FAILED,
    READY,
}

/**
 * What, if anything, moved alongside somebody's weight.
 *
 * Null means there is nothing worth saying, which is the usual answer and the right one. These
 * appear only when the numbers really did move together over enough weeks, because a card that
 * turns up every week saying something different teaches people to ignore the cards.
 */
data class AssociationState(
    val steps: Insights.Association? = null,
    val sleep: Insights.Association? = null,
) {
    val hasAny: Boolean get() = steps?.isNotable == true || sleep?.isNotable == true
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

    private val _associations = MutableStateFlow(AssociationState())
    val associations: StateFlow<AssociationState> = _associations.asStateFlow()

    /**
     * Days a period covered, where the app has been allowed to read them.
     *
     * Empty by default and empty for anybody who refuses, which is what makes refusing free: the
     * chart draws no band and the weekday card counts every morning exactly as it did before.
     */
    private val _cycleDays = MutableStateFlow<Set<LocalDate>>(emptySet())
    val cycleDays: StateFlow<Set<LocalDate>> = _cycleDays.asStateFlow()

    init {
        refreshActivity()
        refreshCycle()
    }

    /** Called when the screen resumes, so a permission granted in Settings takes effect. */
    fun onScreenResumed() {
        refreshActivity()
        refreshCycle()
    }

    /**
     * Reads the periods, on its own.
     *
     * Deliberately not folded into [refreshActivity]. That one gives up the moment step counts
     * are refused, and hanging this off it would mean somebody who shares their cycle and not
     * their step count silently gets neither.
     */
    private fun refreshCycle() {
        viewModelScope.launch {
            if (healthConnect.availability() != HealthConnectAvailability.INSTALLED) return@launch
            _cycleDays.value = healthConnect.readMenstruationDays(days = ASSOCIATION_DAYS)
                .valueOrNull()
                .orEmpty()
        }
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
            // Far enough back for a weekly comparison to have anything to compare. Thirty
            // days is four weeks, which is fewer than the six the maths insists on.
            when (val read = healthConnect.readDailyActivity(days = ASSOCIATION_DAYS)) {
                is HealthOutcome.Ok -> {
                    _activity.value = ActivityState(
                        status = if (read.value.isEmpty()) {
                            ActivityStatus.NO_DATA
                        } else {
                            ActivityStatus.READY
                        },
                        days = read.value.takeLast(ACTIVITY_CARD_DAYS),
                    )
                    refreshAssociations(read.value)
                }
                HealthOutcome.NotAvailable ->
                    _activity.value = ActivityState(ActivityStatus.UNAVAILABLE)
                HealthOutcome.NotAllowed ->
                    _activity.value = ActivityState(ActivityStatus.NOT_PERMITTED)
                is HealthOutcome.Failed ->
                    _activity.value = ActivityState(ActivityStatus.FAILED)
            }
        }
    }

    private suspend fun refreshAssociations(days: List<DailyActivity>) {
        // Whatever there is right now, rather than waiting for there to be something. Waiting
        // parks a coroutine for the life of the view model on any phone with nothing logged
        // yet, which is every phone on the day it is installed.
        val current = snapshot.first()
        if (!current.hasData) {
            _associations.value = AssociationState()
            return
        }
        val series = current.series
        val rule = current.settings.weekRule
        val stepsByDate: Map<LocalDate, Double> = days
            .mapNotNull { day -> day.steps?.let { day.date to it.toDouble() } }
            .toMap()
        // Sleep has its own grant, so refusing it costs this one card and nothing else. An
        // empty map here means no association rather than a wrong one.
        val sleepByDate = healthConnect.readSleepHours(days = ASSOCIATION_DAYS)
            .valueOrNull()
            .orEmpty()
        _associations.value = AssociationState(
            steps = Insights.weeklyAssociation(series, stepsByDate, rule = rule),
            sleep = Insights.weeklyAssociation(series, sleepByDate, rule = rule),
        )
    }

    private companion object {
        /** How far back to read, so a weekly comparison has enough weeks to work with. */
        const val ASSOCIATION_DAYS = 120L

        /** The activity card itself still shows a month. */
        const val ACTIVITY_CARD_DAYS = 30
    }
}
