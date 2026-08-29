package com.weighttrack.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.R
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
import com.weighttrack.ui.AppStrings
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
    /**
     * Whether food, water and steps were allowed as well as weight.
     *
     * False on any install that connected before those existed, which is every one of them.
     */
    val grantedEverything: Boolean = false,
    val syncing: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val strings: AppStrings,
    private val syncPreferences: com.weighttrack.data.sync.SyncPreferences,
    private val syncEngine: com.weighttrack.data.sync.SyncEngine,
    private val syncScheduler: com.weighttrack.sync.SyncScheduler,
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
                grantedEverything = healthConnect.hasEverything(),
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
                strings[R.string.settings_reminders_turned_off_for_somebody, updated.name]
            } else {
                reminderScheduler.nextTriggerAt(updated)
                    ?.let { next ->
                        strings[
                            R.string.settings_next_reminder_for_somebody,
                            updated.name,
                            describeNext(next),
                        ]
                    }
                    ?: strings[R.string.settings_pick_at_least_one_day_for]
            }
        }
    }

    /** Hands Health Connect to the profile on screen, or takes it away. */
    fun setHealthConnectProfile(enabled: Boolean) {
        viewModelScope.launch {
            profileRepository.setHealthConnect(activeProfileId.value, enabled)
            _message.value = if (enabled) {
                strings[R.string.settings_health_connect_now_exchanges_weights_for]
            } else {
                strings[R.string.settings_health_connect_is_no_longer_tied]
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
                strings[R.string.settings_weekly_summary_turned_off]
            } else {
                weeklySummaryScheduler.nextTriggerAt(updated)
                    ?.let { next -> strings[R.string.settings_next_summary_when, describeNext(next)] }
                    ?: strings[R.string.settings_weekly_summary_turned_on]
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
                strings[R.string.settings_app_lock_is_on_you_will]
            } else {
                strings[R.string.settings_app_lock_is_off]
            }
        }
    }

    fun notifyTestSent(sent: Boolean) {
        _message.value = if (sent) {
            strings[R.string.settings_test_notification_sent]
        } else {
            strings[R.string.settings_android_is_blocking_notifications_for_weighttrack]
        }
    }

    fun exportCsv(uri: Uri) = runBackup {
        backupService.exportCsv(uri).fold(
            onSuccess = { strings[R.string.settings_exported_readings, it] },
            onFailure = { strings[R.string.settings_export_failed, it.message.orEmpty()] },
        )
    }

    fun exportJson(uri: Uri) = runBackup {
        backupService.exportJson(uri).fold(
            onSuccess = { strings[R.string.settings_backed_up_readings_plus_measurements_goal, it] },
            onFailure = { strings[R.string.settings_backup_failed, it.message.orEmpty()] },
        )
    }

    fun exportMeasurements(uri: Uri) = runBackup {
        backupService.exportMeasurementsCsv(uri).fold(
            onSuccess = { strings[R.string.settings_exported_measurements, it] },
            onFailure = { strings[R.string.settings_export_failed, it.message.orEmpty()] },
        )
    }

    fun importCsv(uri: Uri) = runBackup {
        val unit = settingsRepository.settings.first().weightUnit
        backupService.importCsv(uri, unit).fold(
            onSuccess = { summary ->
                buildString {
                    append(strings[R.string.settings_imported_readings, summary.imported])
                    if (summary.skipped > 0) append(", skipped ${summary.skipped} unreadable rows")
                    append(".")
                    summary.problems.firstOrNull()?.let { append(" $it") }
                }
            },
            onFailure = { strings[R.string.settings_import_failed, it.message.orEmpty()] },
        )
    }

    fun importJson(uri: Uri) = runBackup {
        backupService.importJson(uri).fold(
            onSuccess = { summary ->
                strings[R.string.settings_restored_readings_and_measurements, summary.imported, summary.measurements]
            },
            onFailure = { strings[R.string.settings_restore_failed, it.message.orEmpty()] },
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
                    strings[R.string.settings_health_connect_brought_in_sent_out, summary.imported, summary.exported]
                },
                onFailure = { strings[R.string.settings_health_connect_sync_failed, it.message.orEmpty()] },
            )
            refreshHealthConnect()
        }
    }

    fun onHealthConnectPermissionResult(granted: Set<String>) {
        // Weight sync only needs the core set. Treating a declined optional read as a
        // refused connection would report a working sync as unauthorised.
        val allowed = granted.containsAll(healthConnect.corePermissions)
        _healthConnectState.value = _healthConnectState.value.copy(
            granted = allowed,
            grantedEverything = granted.containsAll(healthConnect.permissions),
        )
        if (allowed) {
            syncHealthConnect()
        } else {
            _message.value = strings[R.string.settings_health_connect_access_was_not_granted]
        }
    }

    private fun runBackup(block: suspend () -> String) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = runCatching { block() }
                .getOrElse { strings[R.string.settings_something_went_wrong, it.message.orEmpty()] }
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
            _message.value = strings[R.string.settings_switched_to_somebody, name.trim()]
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
                _message.value = name?.let { strings[R.string.settings_deleted_and_everything_recorded_for_them, it] }
            } else {
                // Refusing to delete the last one is deliberate: the app would have nowhere to
                // put the next reading and no way to make a profile to fix it.
                _message.value = strings[R.string.settings_there_has_to_be_somebody_add]
            }
        }
    }

    // ---- sync ----

    val syncSettings = syncPreferences.settings.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        com.weighttrack.data.sync.SyncSettings(),
    )

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /**
     * Takes a folder the person picked and holds on to the right to read it.
     *
     * Without taking the permission the address stops working the next time the app starts, and
     * background syncing would fail forever with nothing on screen to explain why.
     */
    fun useSyncFolder(uri: android.net.Uri, holdOnTo: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            holdOnTo(uri)
            syncPreferences.useFolder(uri.toString())
            syncScheduler.reschedule()
            syncNow()
        }
    }

    fun useWebDav(url: String, user: String, password: String) {
        viewModelScope.launch {
            syncPreferences.useWebDav(url, user, password)
            syncScheduler.reschedule()
            syncNow()
        }
    }

    fun turnSyncOff() {
        viewModelScope.launch {
            syncPreferences.turnOff()
            syncScheduler.reschedule()
            _message.value = strings[R.string.settings_sync_turned_off_nothing_was_deleted]
        }
    }

    fun setSyncInBackground(enabled: Boolean) {
        viewModelScope.launch {
            syncPreferences.setBackground(enabled)
            syncScheduler.reschedule()
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                when (val result = syncEngine.syncNow()) {
                    is com.weighttrack.data.sync.SyncResult.Done ->
                        _message.value = syncPreferences.current().lastSyncMessage
                    is com.weighttrack.data.sync.SyncResult.Refused -> _message.value = result.reason
                    is com.weighttrack.data.sync.SyncResult.Unreachable ->
                        _message.value = result.reason
                    com.weighttrack.data.sync.SyncResult.NotSetUp ->
                        _message.value = strings[R.string.settings_pick_somewhere_to_sync_to_first]
                }
            } catch (error: Exception) {
                // Something in a file from another device, or a row that will not go where it
                // is told. Whatever it is, it must not take the app down: the button is a thing
                // somebody pressed, not a promise that every other device is well behaved.
                _message.value = strings[R.string.settings_sync_could_not_finish_nothing_was]
            } finally {
                _syncing.value = false
            }
        }
    }
}
