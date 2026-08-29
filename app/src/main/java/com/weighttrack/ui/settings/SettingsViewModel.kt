package com.weighttrack.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.UserProfile
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.io.BackupService
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.Profile
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.diagnostics.CrashLogStore
import com.weighttrack.health.HealthConnectAvailability
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.notifications.ReminderScheduler
import com.weighttrack.notifications.WeeklySummaryScheduler
import com.weighttrack.widget.SurfaceUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.ZonedDateTime
import javax.inject.Inject

data class HealthConnectState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.NOT_SUPPORTED,
    val granted: Boolean = false,
    val syncing: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val weightRepository: WeightRepository,
    private val profileRepository: ProfileRepository,
    private val progressPhotoRepository: com.weighttrack.data.repo.ProgressPhotoRepository,
    private val backupService: BackupService,
    private val reminderScheduler: ReminderScheduler,
    private val weeklySummaryScheduler: WeeklySummaryScheduler,
    private val crashLogStore: CrashLogStore,
    private val SurfaceUpdater: SurfaceUpdater,
    val healthConnect: HealthConnectSync,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val entryCount: StateFlow<Int> = weightRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val profiles: StateFlow<List<Profile>> = profileRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long> = profileRepository.activeProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1L)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _healthConnectState = MutableStateFlow(HealthConnectState())
    val healthConnectState: StateFlow<HealthConnectState> = _healthConnectState.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _crashReportCount = MutableStateFlow(0)
    val crashReportCount: StateFlow<Int> = _crashReportCount.asStateFlow()

    init {
        refreshHealthConnect()
        refreshCrashReportCount()
    }

    fun refreshCrashReportCount() {
        viewModelScope.launch {
            _crashReportCount.value = withContext(Dispatchers.IO) { crashLogStore.count() }
        }
    }

    /**
     * Re-reads everything that can change while the screen sits in the background: the crash
     * count after someone clears it, and whether the device has gained or lost a screen lock.
     */
    fun onScreenResumed() {
        refreshCrashReportCount()
        refreshHealthConnect()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun refreshHealthConnect() {
        viewModelScope.launch {
            _healthConnectState.value = HealthConnectState(
                availability = healthConnect.availability(),
                granted = healthConnect.hasPermissions(),
            )
        }
    }

    fun setWeightUnit(unit: WeightUnit) = viewModelScope.launch {
        settingsRepository.setWeightUnit(unit)
    }

    fun setLengthUnit(unit: LengthUnit) = viewModelScope.launch {
        settingsRepository.setLengthUnit(unit)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        settingsRepository.setThemeMode(mode)
    }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDynamicColor(enabled)
    }

    fun setTrendWindow(days: Int) = viewModelScope.launch {
        settingsRepository.setTrendWindowDays(days)
    }

    fun setHeightMm(mm: Int) = viewModelScope.launch { settingsRepository.setHeightMm(mm) }

    fun setProfile(profile: UserProfile) = viewModelScope.launch {
        settingsRepository.setProfile(profile)
    }

    fun setSex(sex: Sex) = viewModelScope.launch {
        settingsRepository.setProfile(settings.value.profile.copy(sex = sex))
    }

    fun setBirthYear(year: Int) = viewModelScope.launch {
        settingsRepository.setProfile(settings.value.profile.copy(birthYear = year))
    }

    fun setActivityLevel(level: ActivityLevel) = viewModelScope.launch {
        settingsRepository.setProfile(settings.value.profile.copy(activityLevel = level))
    }

    /**
     * Any reminder change reschedules immediately, so the setting and the alarm cannot drift.
     *
     * The reminder belongs to the profile on screen, not to the phone: two people in a house
     * weigh themselves at different times.
     */
    fun setReminder(enabled: Boolean, hour: Int, minute: Int, days: Set<DayOfWeek>) {
        viewModelScope.launch {
            val id = activeProfileId.value
            profileRepository.setReminder(id, enabled, hour, minute, days)
            val updated = profileRepository.observeAll().first().firstOrNull { it.id == id }
                ?: return@launch
            reminderScheduler.reschedule(updated)
            _message.value = if (!enabled) {
                "Reminders turned off for " + updated.name + "."
            } else {
                reminderScheduler.nextTriggerAt(updated)
                    ?.let { next -> "Next reminder for " + updated.name + " " + describeNext(next) + "." }
                    ?: "Pick at least one day for the reminder."
            }
        }
    }

    /** Hands Health Connect to the profile on screen, or takes it away. */
    fun setHealthConnectProfile(enabled: Boolean) {
        viewModelScope.launch {
            profileRepository.setHealthConnect(activeProfileId.value, enabled)
            _message.value = if (enabled) {
                "Health Connect now exchanges weights for this profile only."
            } else {
                "Health Connect is no longer tied to a profile, so nothing is synced."
            }
        }
    }

    fun canScheduleExactAlarms(): Boolean = reminderScheduler.canScheduleExact()

    fun setNutritionEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setNutritionEnabled(enabled)
    }

    fun setWeeklySummary(enabled: Boolean, day: java.time.DayOfWeek, hour: Int) {
        viewModelScope.launch {
            settingsRepository.setWeeklySummary(enabled, day, hour)
            val updated = settingsRepository.settings.first()
            weeklySummaryScheduler.reschedule(updated)
            _message.value = if (!enabled) {
                "Weekly summary turned off."
            } else {
                weeklySummaryScheduler.nextTriggerAt(updated)
                    ?.let { next -> "Next summary " + describeNext(next) + "." }
                    ?: "Weekly summary turned on."
            }
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAppLockEnabled(enabled)
            // Otherwise the home screen widget keeps showing the trend weight until Android
            // next feels like updating it, which can be half an hour.
            SurfaceUpdater.refresh()
            _message.value = if (enabled) {
                "App lock is on. You will be asked to unlock each time you come back."
            } else {
                "App lock is off."
            }
        }
    }

    fun notifyTestSent(sent: Boolean) {
        _message.value = if (sent) {
            "Test notification sent."
        } else {
            "Android is blocking notifications for WeightTrack. Turn them on in system settings."
        }
    }

    fun exportCsv(uri: Uri) = runBackup {
        backupService.exportCsv(uri).fold(
            onSuccess = { "Exported $it readings." },
            onFailure = { "Export failed: ${it.message}" },
        )
    }

    fun exportJson(uri: Uri) = runBackup {
        backupService.exportJson(uri).fold(
            onSuccess = { "Backed up $it readings, plus measurements, goal and settings." },
            onFailure = { "Backup failed: ${it.message}" },
        )
    }

    fun exportMeasurements(uri: Uri) = runBackup {
        backupService.exportMeasurementsCsv(uri).fold(
            onSuccess = { "Exported $it measurements." },
            onFailure = { "Export failed: ${it.message}" },
        )
    }

    fun importCsv(uri: Uri) = runBackup {
        val unit = settingsRepository.settings.first().weightUnit
        backupService.importCsv(uri, unit).fold(
            onSuccess = { summary ->
                buildString {
                    append("Imported ${summary.imported} readings")
                    if (summary.skipped > 0) append(", skipped ${summary.skipped} unreadable rows")
                    append(".")
                    summary.problems.firstOrNull()?.let { append(" $it") }
                }
            },
            onFailure = { "Import failed: ${it.message}" },
        )
    }

    fun importJson(uri: Uri) = runBackup {
        backupService.importJson(uri).fold(
            onSuccess = { summary ->
                "Restored ${summary.imported} readings and ${summary.measurements} measurements."
            },
            onFailure = { "Restore failed: ${it.message}" },
        )
    }

    fun syncHealthConnect() {
        viewModelScope.launch {
            _healthConnectState.value = _healthConnectState.value.copy(syncing = true)
            val result = healthConnect.sync()
            healthConnect.exportBodyFat()
            _healthConnectState.value = _healthConnectState.value.copy(syncing = false)
            SurfaceUpdater.refresh()
            _message.value = result.fold(
                onSuccess = { summary ->
                    "Health Connect: brought in ${summary.imported}, sent out ${summary.exported}."
                },
                onFailure = { "Health Connect sync failed: ${it.message}" },
            )
            refreshHealthConnect()
        }
    }

    fun onHealthConnectPermissionResult(granted: Set<String>) {
        // Weight sync only needs the core set. Treating a declined optional read as a
        // refused connection would report a working sync as unauthorised.
        val allowed = granted.containsAll(healthConnect.corePermissions)
        _healthConnectState.value = _healthConnectState.value.copy(granted = allowed)
        if (allowed) {
            syncHealthConnect()
        } else {
            _message.value = "Health Connect access was not granted, so nothing was synced."
        }
    }

    private fun runBackup(block: suspend () -> String) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = runCatching { block() }
                .getOrElse { "Something went wrong: ${it.message}" }
            SurfaceUpdater.refresh()
            _busy.value = false
        }
    }

    private fun describeNext(next: ZonedDateTime): String {
        val time = "%02d:%02d".format(next.hour, next.minute)
        val today = ZonedDateTime.now(next.zone).toLocalDate()
        return when (next.toLocalDate()) {
            today -> "today at $time"
            today.plusDays(1) -> "tomorrow at $time"
            else -> "on ${next.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} at $time"
        }
    }

    fun switchProfile(id: Long) {
        viewModelScope.launch {
            profileRepository.setActive(id)
            // Everything glanceable is showing somebody else's numbers until this runs.
            SurfaceUpdater.refresh()
        }
    }

    fun addProfile(name: String) {
        viewModelScope.launch {
            profileRepository.add(name)
            SurfaceUpdater.refresh()
            _message.value = "Switched to " + name.trim()
        }
    }

    fun renameProfile(id: Long, name: String) {
        viewModelScope.launch {
            profileRepository.rename(id, name)
            SurfaceUpdater.refresh()
        }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            val name = profiles.value.firstOrNull { it.id == id }?.name
            val photos = profileRepository.deleteReturningPhotos(id)
            if (photos != null) {
                // The alarm outlives the row it belonged to, and would go off once more under
                // somebody else's name. The pictures outlive it on disk.
                reminderScheduler.cancel(id)
                progressPhotoRepository.deleteFiles(photos)
                SurfaceUpdater.refresh()
                _message.value = name?.let { "Deleted $it and everything recorded for them" }
            } else {
                // Refusing to delete the last one is deliberate: the app would have nowhere to
                // put the next reading and no way to make a profile to fix it.
                _message.value = "There has to be somebody. Add another profile first."
            }
        }
    }
}
