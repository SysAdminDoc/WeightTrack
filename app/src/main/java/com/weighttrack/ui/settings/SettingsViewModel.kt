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
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.diagnostics.CrashLogStore
import com.weighttrack.health.HealthConnectAvailability
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.notifications.ReminderScheduler
import com.weighttrack.widget.WidgetUpdater
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
    private val backupService: BackupService,
    private val reminderScheduler: ReminderScheduler,
    private val crashLogStore: CrashLogStore,
    private val widgetUpdater: WidgetUpdater,
    val healthConnect: HealthConnectSync,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val entryCount: StateFlow<Int> = weightRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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

    /** Any reminder change reschedules immediately, so the setting and the alarm cannot drift. */
    fun setReminder(enabled: Boolean, hour: Int, minute: Int, days: Set<DayOfWeek>) {
        viewModelScope.launch {
            settingsRepository.setReminder(enabled, hour, minute, days)
            val updated = settingsRepository.settings.first()
            reminderScheduler.reschedule(updated)
            _message.value = if (!enabled) {
                "Reminders turned off."
            } else {
                reminderScheduler.nextTriggerAt(updated)
                    ?.let { next -> "Next reminder ${describeNext(next)}." }
                    ?: "Pick at least one day for the reminder."
            }
        }
    }

    fun canScheduleExactAlarms(): Boolean = reminderScheduler.canScheduleExact()

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAppLockEnabled(enabled)
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
            widgetUpdater.refresh()
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
        val allowed = granted.containsAll(healthConnect.permissions)
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
            widgetUpdater.refresh()
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
}
