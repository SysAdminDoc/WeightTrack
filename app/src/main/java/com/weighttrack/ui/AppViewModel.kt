package com.weighttrack.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.diagnostics.LogArea
import com.weighttrack.diagnostics.LogEvent
import com.weighttrack.diagnostics.step
import com.weighttrack.wear.WearBridge
import com.weighttrack.wear.WearSummaryBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-wide settings, held above the navigation graph because the theme depends on them. */
@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val goalRepository: com.weighttrack.data.repo.GoalRepository,
    private val runtimeLog: com.weighttrack.diagnostics.RuntimeLog,
    private val reminderScheduler: com.weighttrack.notifications.ReminderScheduler,
    private val healthConnect: com.weighttrack.health.HealthConnectSync,
    private val wearBridge: WearBridge,
    private val wearSummaryBuilder: WearSummaryBuilder,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            // Null means "not read yet". Rendering against defaults first would flash the
            // wrong theme and briefly show onboarding to someone who finished it months ago.
            initialValue = null,
        )

    /**
     * Starts locked. A fresh view model means a fresh process or a fresh activity, and both are
     * cases where the person has not authenticated yet. The lock only shows when the setting is
     * on, so this costs nothing when the feature is off.
     */
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _lockError = MutableStateFlow<String?>(null)
    val lockError: StateFlow<String?> = _lockError.asStateFlow()

    /**
     * Changes every time the app is re-locked, so the screen can prompt again.
     *
     * Without it, someone who dismisses the prompt once is left on a lock screen that never
     * asks again, because `locked` was already true and nothing observable changed.
     */
    private val _promptRequest = MutableStateFlow(0)
    val promptRequest: StateFlow<Int> = _promptRequest.asStateFlow()

    init {
        viewModelScope.launch {
            // Recorded when it fails. Without a profile there is nowhere to put a reading, so a
            // failure here is the largest thing that can go quietly wrong on the way up.
            runtimeLog.step(com.weighttrack.diagnostics.LogTask.STARTUP_PROFILES) {
                // A fresh install has no profile until something makes one, and anyone who had a
                // daily reminder set before profiles existed would otherwise lose it silently.
                profileRepository.ensureDefault()
                // The row a fresh install starts with was written without a travelling name, so
                // deleting that person told the other phone nothing and they came back. Named
                // once, here, because every install made since profiles arrived has one.
                val named = profileRepository.nameTheUnnamed()
                if (named > 0) {
                    runtimeLog.write(LogArea.DATA, LogEvent.PROFILE_NAMED, code = named)
                }
                profileRepository.adoptLegacyReminder()
                // The height, sex, year of birth and activity level that used to belong to the
                // phone go to whoever was using it, once. Handing them to every profile would
                // tell a household everybody is the same height.
                profileRepository.adoptLegacyDemographics()
            }
            // A goal date nobody can read used to mean today, so the same damaged row said
            // something different every morning. Written back readable, once.
            // A repair that threw used to be written under the same event as one that worked,
            // so the only way to tell them apart in a shared log was to notice a cause on the
            // end of the line.
            runtimeLog.step(com.weighttrack.diagnostics.LogTask.STARTUP_GOAL_REPAIR) {
                val repaired = goalRepository.repairUnreadableDates()
                if (repaired.isNotEmpty()) {
                    runtimeLog.write(
                        LogArea.DATA,
                        LogEvent.GOAL_DATE_REPAIRED,
                        code = repaired.size,
                    )
                }
            }
            // Writing the row is not enough. Alarms do not survive the app being replaced, and
            // the boot receiver has already run and found nothing enabled, so the reminder that
            // was just moved across has to be booked here or it never fires.
            runtimeLog.step(com.weighttrack.diagnostics.LogTask.STARTUP_REMINDERS) {
                reminderScheduler.reschedule(profileRepository.observeAll().first())
            }
            // Whose Health Connect this is, settled at the first opportunity rather than at the
            // first background sync up to an hour later. Every install that connected before the
            // claim existed arrives here with nobody holding it, and the person switching profile
            // inside that window would have had it claimed for the wrong one, permanently.
            runtimeLog.step(com.weighttrack.diagnostics.LogTask.STARTUP_HEALTH_CLAIM) {
                if (healthConnect.hasPermissions()) healthConnect.claimProfile()
            }
        }

        // Opening the app is the moment to bring a watch up to date. Everything else that
        // changes a reading goes through SurfaceUpdater, but a watch paired since the last
        // weigh-in would otherwise have nothing until the next one.
        if (wearBridge.isSupported) {
            viewModelScope.launch {
                runtimeLog.step(com.weighttrack.diagnostics.LogTask.STARTUP_WEAR_PUBLISH) {
                    wearBridge.publish(wearSummaryBuilder.current())
                }
            }
        }

        viewModelScope.launch {
            var previouslyEnabled: Boolean? = null
            settings.filterNotNull()
                .map { it.appLockEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    // Switching the lock on from inside the app must not immediately throw up
                    // the lock screen: the person is demonstrably right there. Only a later
                    // trip to the background should ask them to authenticate.
                    if (previouslyEnabled == false && enabled) unlock()
                    previouslyEnabled = enabled
                }
        }
    }

    fun unlock() {
        _lockError.value = null
        _locked.value = false
    }

    fun lock() {
        _locked.value = true
        _promptRequest.value += 1
    }

    fun onUnlockFailed(message: String?) {
        _lockError.value = message
    }
}
