package com.weighttrack.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.R
import com.weighttrack.core.math.SmoothingMode
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.HealthDirection
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.UserProfile
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.io.AutoBackupScheduler
import com.weighttrack.data.io.BackupService
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.Profile
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.ProgressPhotoRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.sync.SyncEngine
import com.weighttrack.data.sync.SyncPreferences
import com.weighttrack.diagnostics.CrashLogStore
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.notifications.ReminderScheduler
import com.weighttrack.notifications.WeeklySummaryScheduler
import com.weighttrack.sync.SyncScheduler
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.UndoCoordinator
import com.weighttrack.widget.SurfaceUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject

/**
 * The settings screen, which is four screens' worth of settings in one list.
 *
 * The work itself lives in the controllers below rather than here. Each is built with this view
 * model's own scope, so everything they start is cancelled with the screen, and each is handed the
 * same message sink, because there is one snackbar and whichever of them spoke last owns it.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val strings: AppStrings,
    syncPreferences: SyncPreferences,
    syncEngine: SyncEngine,
    syncScheduler: SyncScheduler,
    private val settingsRepository: SettingsRepository,
    weightRepository: WeightRepository,
    profileRepository: ProfileRepository,
    progressPhotoRepository: ProgressPhotoRepository,
    backupService: BackupService,
    autoBackupScheduler: AutoBackupScheduler,
    reminderScheduler: ReminderScheduler,
    private val weeklySummaryScheduler: WeeklySummaryScheduler,
    private val crashLogStore: CrashLogStore,
    private val surfaces: SurfaceUpdater,
    undoOffers: UndoCoordinator,
    @ApplicationContext context: Context,
    val healthConnect: HealthConnectSync,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val onMessage: (String?) -> Unit = { _message.value = it }

    private val people = PeopleSettingsController(
        scope = viewModelScope,
        profileRepository = profileRepository,
        progressPhotoRepository = progressPhotoRepository,
        reminderScheduler = reminderScheduler,
        healthConnect = healthConnect,
        undoOffers = undoOffers,
        surfaces = surfaces,
        strings = strings,
        onMessage = onMessage,
    )

    private val health = HealthConnectSettingsController(
        scope = viewModelScope,
        healthConnect = healthConnect,
        settingsRepository = settingsRepository,
        profileRepository = profileRepository,
        weightRepository = weightRepository,
        syncScheduler = syncScheduler,
        surfaces = surfaces,
        strings = strings,
        onMessage = onMessage,
    )

    private val backups = BackupSettingsController(
        scope = viewModelScope,
        backupService = backupService,
        settingsRepository = settingsRepository,
        autoBackupScheduler = autoBackupScheduler,
        surfaces = surfaces,
        strings = strings,
        onMessage = onMessage,
    )

    private val sync = SyncSettingsController(
        scope = viewModelScope,
        preferences = syncPreferences,
        engine = syncEngine,
        scheduler = syncScheduler,
        strings = strings,
        context = context,
        onMessage = onMessage,
    )

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val entryCount: StateFlow<Int> = weightRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val profiles: StateFlow<List<Profile>> get() = people.profiles
    val activeProfileId: StateFlow<Long> get() = people.activeProfileId
    val demographics: StateFlow<UserProfile> get() = people.demographics
    val healthConnectState: StateFlow<HealthConnectState> get() = health.state
    val busy: StateFlow<Boolean> get() = backups.busy
    val pendingRestore: StateFlow<PendingRestore?> get() = backups.pendingRestore
    val autoBackup: StateFlow<AutoBackupState> get() = backups.autoBackup
    val syncSettings get() = sync.settings
    val syncing: StateFlow<Boolean> get() = sync.syncing

    private val _crashReportCount = MutableStateFlow(0)
    val crashReportCount: StateFlow<Int> = _crashReportCount.asStateFlow()

    init {
        refreshHealthConnect()
        refreshAutoBackup()
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

    // ---- the phone's own settings ----

    fun setImportLowestOfDay(only: Boolean) = viewModelScope.launch {
        settingsRepository.setImportLowestOfDay(only)
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

    fun setSmoothingMode(mode: SmoothingMode) = viewModelScope.launch {
        settingsRepository.setSmoothingMode(mode)
    }

    fun setNutritionEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setNutritionEnabled(enabled)
    }

    /** Null puts it back to whatever the phone's region says. */
    fun setFirstDayOfWeek(day: DayOfWeek?) {
        viewModelScope.launch { settingsRepository.setFirstDayOfWeek(day) }
    }

    fun setWeeklySummary(enabled: Boolean, day: DayOfWeek, hour: Int) {
        viewModelScope.launch {
            settingsRepository.setWeeklySummary(enabled, day, hour)
            val updated = settingsRepository.settings.first()
            weeklySummaryScheduler.reschedule(updated)
            _message.value = if (!enabled) {
                strings[R.string.settings_weekly_summary_turned_off]
            } else {
                weeklySummaryScheduler.nextTriggerAt(updated)
                    ?.let { next ->
                        strings[R.string.settings_next_summary_when, strings.describeNext(next)]
                    }
                    ?: strings[R.string.settings_weekly_summary_turned_on]
            }
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAppLockEnabled(enabled)
            // Otherwise the home screen widget keeps showing the trend weight until Android
            // next feels like updating it, which can be half an hour.
            surfaces.refresh()
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

    // ---- the people, and what belongs to each of them ----

    fun setHeightMm(mm: Int) = people.setHeightMm(mm)
    fun setProfile(profile: UserProfile) = people.setProfile(profile)
    fun setSex(sex: Sex) = people.setSex(sex)
    fun setBirthYear(year: Int) = people.setBirthYear(year)
    fun setActivityLevel(level: ActivityLevel) = people.setActivityLevel(level)
    fun setReminder(enabled: Boolean, hour: Int, minute: Int, days: Set<DayOfWeek>) =
        people.setReminder(enabled, hour, minute, days)
    fun setHealthConnectProfile(enabled: Boolean) = people.setHealthConnectProfile(enabled)
    fun switchProfile(id: Long) = people.switchProfile(id)
    fun addProfile(name: String) = people.addProfile(name)
    fun renameProfile(id: Long, name: String) = people.renameProfile(id, name)
    fun deleteProfile(id: Long) = people.deleteProfile(id)

    // ---- Health Connect ----

    fun refreshHealthConnect() = health.refresh()
    fun setHealthDirection(direction: HealthDirection) = health.setDirection(direction)
    fun setHealthOriginExcluded(packageName: String, excluded: Boolean) =
        health.setOriginExcluded(packageName, excluded)
    fun syncHealthConnect() = health.syncNow()
    fun onHealthConnectPermissionResult(granted: Set<String>) = health.onPermissionResult(granted)

    // ---- files in and out ----

    fun exportCsv(uri: Uri) = backups.exportCsv(uri)
    fun exportJson(uri: Uri) = backups.exportJson(uri)
    fun exportArchive(uri: Uri, password: CharArray) = backups.exportArchive(uri, password)
    fun importArchive(uri: Uri, password: CharArray) = backups.importArchive(uri, password)
    fun exportMeasurements(uri: Uri) = backups.exportMeasurements(uri)
    fun importCsv(uri: Uri) = backups.importCsv(uri)
    fun previewRestore(uri: Uri) = backups.previewRestore(uri)
    fun cancelRestore() = backups.cancelRestore()
    fun confirmRestore() = backups.confirmRestore()
    fun refreshAutoBackup() = backups.refreshAutoBackup()
    fun useAutoBackupFolder(uri: Uri, holdOnTo: (Uri) -> Unit) =
        backups.useAutoBackupFolder(uri, holdOnTo)
    fun turnOffAutoBackup() = backups.turnOffAutoBackup()

    // ---- sync ----

    fun useSyncFolder(uri: Uri, holdOnTo: (Uri) -> Unit) = sync.useFolder(uri, holdOnTo)
    fun useWebDav(url: String, user: String, password: String) = sync.useWebDav(url, user, password)
    fun useSyncCertificate(uri: Uri) = sync.useCertificate(uri)
    fun forgetSyncCertificate() = sync.forgetCertificate()
    fun turnSyncOff() = sync.turnOff()
    fun setSyncInBackground(enabled: Boolean) = sync.setInBackground(enabled)
    fun syncNow() = sync.syncNow()
}
