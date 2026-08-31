package com.weighttrack.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.R
import com.weighttrack.core.io.RowProblem
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
import com.weighttrack.data.repo.UndoableDelete
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.ZonedDateTime
import javax.inject.Inject

/** A backup that has been read and described, waiting for somebody to say yes to it. */
data class PendingRestore(
    val uri: Uri,
    val preview: com.weighttrack.data.io.BackupPreview,
)

data class HealthConnectState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.NOT_SUPPORTED,
    val granted: Boolean = false,
    /**
     * Whether food, water and steps were allowed as well as weight.
     *
     * False on any install that connected before those existed, which is every one of them.
     */
    val grantedEverything: Boolean = false,
    /**
     * Somebody connected once and the access has gone since.
     *
     * Worth saying rather than quietly showing the Connect button again, because from where they
     * are sitting the sync simply stopped and nothing said why.
     */
    val accessWithdrawn: Boolean = false,
    val syncing: Boolean = false,
    /** Which way readings may move, which is also what the app asks permission for. */
    val direction: com.weighttrack.core.model.HealthDirection =
        com.weighttrack.core.model.HealthDirection.TWO_WAY,
    /**
     * The apps whose readings have arrived here, each with whether they are still wanted.
     *
     * Read from what is actually in the log rather than from a list of known scale apps: the one
     * writing into somebody's Health Connect is whichever app they happen to use.
     */
    val origins: List<HealthOrigin> = emptyList(),
)

/** One app that has written a reading into this log, and whether it still may. */
data class HealthOrigin(
    val packageName: String,
    val device: String?,
    val excluded: Boolean,
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
    private val autoBackupScheduler: com.weighttrack.data.io.AutoBackupScheduler,
    private val reminderScheduler: ReminderScheduler,
    private val weeklySummaryScheduler: WeeklySummaryScheduler,
    private val crashLogStore: CrashLogStore,
    private val SurfaceUpdater: SurfaceUpdater,
    private val undoOffers: com.weighttrack.ui.UndoCoordinator,
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

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    private val _crashReportCount = MutableStateFlow(0)
    val crashReportCount: StateFlow<Int> = _crashReportCount.asStateFlow()

    private val _autoBackup = MutableStateFlow(AutoBackupState())
    val autoBackup: StateFlow<AutoBackupState> = _autoBackup.asStateFlow()

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

    /** Says which row would not read and why, in the reader's language. */
    private fun describe(problem: RowProblem): String = strings[
        when (problem.field) {
            RowProblem.Field.WEIGHT -> R.string.settings_import_bad_weight
            RowProblem.Field.DATE -> R.string.settings_import_bad_date
        },
        problem.row,
        problem.value,
    ]

    fun consumeMessage() {
        _message.value = null
    }

    fun refreshHealthConnect() {
        viewModelScope.launch {
            val granted = healthConnect.hasPermissions()
            val stored = settingsRepository.settings.first()
            _healthConnectState.value = HealthConnectState(
                availability = healthConnect.availability(),
                granted = granted,
                grantedEverything = healthConnect.hasEverything(),
                // A profile holds the claim from the moment somebody connects, so a claim with
                // no permission behind it is access that was taken away rather than never given.
                accessWithdrawn = !granted && profileRepository.healthConnectId() != null,
                direction = stored.healthDirection,
                origins = weightRepository.origins().map { origin ->
                    HealthOrigin(
                        packageName = origin.packageName,
                        device = origin.device,
                        excluded = origin.packageName in stored.excludedHealthOrigins,
                    )
                },
            )
        }
    }

    fun setHealthDirection(direction: com.weighttrack.core.model.HealthDirection) =
        viewModelScope.launch {
            settingsRepository.setHealthDirection(direction)
            refreshHealthConnect()
        }

    fun setHealthOriginExcluded(packageName: String, excluded: Boolean) = viewModelScope.launch {
        settingsRepository.setHealthOriginExcluded(packageName, excluded)
        refreshHealthConnect()
    }

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

    /**
     * The body the figures on screen are worked out from, for the person on screen.
     *
     * These belong to a profile rather than to the phone. A household of two sharing one height
     * had every BMI, healthy range, body-fat estimate, basal rate and expenditure computed from
     * whichever of them typed theirs in, and nothing on any screen said so.
     */
    val demographics: StateFlow<UserProfile> = profileRepository.activeProfile
        .map { it?.demographics ?: UserProfile() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile())

    fun setHeightMm(mm: Int) = editDemographics { it.copy(heightMm = mm) }

    fun setProfile(profile: UserProfile) = editDemographics { profile }

    fun setSex(sex: Sex) = editDemographics { it.copy(sex = sex) }

    fun setBirthYear(year: Int) = editDemographics { it.copy(birthYear = year) }

    fun setActivityLevel(level: ActivityLevel) = editDemographics { it.copy(activityLevel = level) }

    private fun editDemographics(change: (UserProfile) -> UserProfile) = viewModelScope.launch {
        // Read from the row rather than from the state flow, so a change made in the moment
        // between the screen collecting and the tap landing is not written back over.
        val id = profileRepository.activeId()
        val current = profileRepository.observeAll().first().firstOrNull { it.id == id }
            ?.demographics
            ?: UserProfile()
        profileRepository.setDemographics(id, change(current))
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
            // Never while a sync is in flight. Changing hands halfway through would file the
            // rest of that sync's import against the new owner and leave the export half done
            // under the old one.
            healthConnect.whileNotSyncing {
                profileRepository.setHealthConnect(activeProfileId.value, enabled)
            }
            _message.value = if (enabled) {
                strings[R.string.settings_health_connect_now_exchanges_weights_for]
            } else {
                strings[R.string.settings_health_connect_is_no_longer_tied]
            }
        }
    }


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

    /**
     * Writes the archive, then forgets the password.
     *
     * Held as a [CharArray] and wiped as soon as the file is written. There is nowhere to keep
     * it: a stored password would make the archive's encryption a formality on the one phone
     * that already has everything in it anyway.
     */
    fun exportArchive(uri: Uri, password: CharArray) = runBackup {
        try {
            backupService.exportArchive(uri, password).fold(
                onSuccess = { strings[R.string.settings_archived_with_photos, it.photos] },
                onFailure = { strings[R.string.settings_backup_failed, it.message.orEmpty()] },
            )
        } finally {
            password.fill('\u0000')
        }
    }

    fun importArchive(uri: Uri, password: CharArray) = runBackup {
        try {
            backupService.importArchive(uri, password).fold(
                onSuccess = { summary ->
                    strings[
                        R.string.settings_restored_readings_and_measurements,
                        summary.imported,
                        summary.measurements,
                    ]
                },
                onFailure = { strings[R.string.settings_restore_failed, it.message.orEmpty()] },
            )
        } finally {
            password.fill('\u0000')
        }
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
                    if (summary.skipped > 0) {
                        append(strings[R.string.settings_import_skipped, summary.skipped])
                    }
                    append(".")
                    summary.problems.firstOrNull()?.let { append(" ").append(describe(it)) }
                }
            },
            onFailure = { strings[R.string.settings_import_failed, it.message.orEmpty()] },
        )
    }

    /**
     * Reads a chosen file and says what is in it, without writing anything.
     *
     * Restoring used to happen the instant a file was picked, which is the one action in the app
     * that can change every screen at once and the easiest to do to the wrong file: a folder of
     * weekly backups is four files with almost the same name.
     */
    fun previewRestore(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            backupService.previewJson(uri).fold(
                onSuccess = { _pendingRestore.value = PendingRestore(uri, it) },
                onFailure = {
                    _message.value = strings[R.string.settings_restore_failed, it.message.orEmpty()]
                },
            )
            _busy.value = false
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    fun confirmRestore() {
        val pending = _pendingRestore.value ?: return
        _pendingRestore.value = null
        runBackup {
            backupService.importJson(pending.uri).fold(
                onSuccess = { summary ->
                    strings[R.string.settings_restored_readings_and_measurements, summary.imported, summary.measurements]
                },
                onFailure = { strings[R.string.settings_restore_failed, it.message.orEmpty()] },
            )
        }
    }

    fun syncHealthConnect() {
        viewModelScope.launch {
            _healthConnectState.value = _healthConnectState.value.copy(syncing = true)
            // Body fat travels with the weigh-in it belongs to now, so there is nothing to
            // send separately afterwards.
            val result = healthConnect.sync()
            _healthConnectState.value = _healthConnectState.value.copy(syncing = false)
            SurfaceUpdater.refresh()
            _message.value = result.fold(
                onSuccess = { summary ->
                    val message = strings[
                        R.string.settings_health_connect_brought_in_sent_out,
                        summary.imported,
                        summary.exported,
                    ]
                    // A sync that removed readings deleted elsewhere said nothing about them, so
                    // the count on screen looked like nothing had happened.
                    if (summary.removed > 0) {
                        message + strings[R.string.settings_health_connect_removed, summary.removed]
                    } else {
                        message
                    }
                },
                onFailure = { strings[R.string.settings_health_connect_sync_failed, it.message.orEmpty()] },
            )
            refreshHealthConnect()
        }
    }

    fun onHealthConnectPermissionResult(granted: Set<String>) {
        // Connecting is what starts the background job for somebody who syncs a scale through
        // Health Connect and keeps no folder.
        viewModelScope.launch { runCatching { syncScheduler.reschedule() } }
        // Weight sync only needs the core set. Treating a declined optional read as a
        // refused connection would report a working sync as unauthorised.
        val way = _healthConnectState.value.direction
        val allowed = granted.containsAll(HealthConnectSync.corePermissionsFor(way))
        _healthConnectState.value = _healthConnectState.value.copy(
            granted = allowed,
            grantedEverything = granted.containsAll(healthConnect.grantablePermissions(way)),
        )
        if (allowed) {
            // Whose Health Connect this is, written down before a single record moves. Deciding
            // it at the first sync instead would pin it on whoever happened to be active when a
            // background job ran, which need not be the person who granted the access.
            viewModelScope.launch {
                runCatching { healthConnect.claimProfile() }
                syncHealthConnect()
            }
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
            today -> strings[R.string.settings_today_at, time]
            today.plusDays(1) -> strings[R.string.settings_tomorrow_at, time]
            else -> strings[
                R.string.settings_on_day_at,
                next.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() },
                time,
            ]
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
            val deletion = profileRepository.deleteReturningPhotos(id)
            if (deletion != null) {
                // The alarm outlives the row it belonged to, and would go off once more under
                // somebody else's name. The pictures outlive it on disk, so they are moved aside
                // rather than unlinked while the undo is still on offer.
                reminderScheduler.cancel(id)
                val held = progressPhotoRepository.holdForUndo(deletion.photoFileNames)
                SurfaceUpdater.refresh()
                undoOffers.offer(
                    UndoableDelete(release = { progressPhotoRepository.releaseHeld(held) }) {
                        // The files first. A row whose image is not yet back reads as a photo
                        // that has gone, and the screen simply does not list it.
                        progressPhotoRepository.returnFromUndo(held)
                        deletion.restore()
                    },
                    strings[R.string.settings_deleted_and_everything_recorded_for_them, name.orEmpty()],
                ) {
                    // The reminder lives on the profile row, so it comes back with the person and
                    // has to be booked again.
                    profileRepository.byId(id)?.let { reminderScheduler.reschedule(it) }
                    SurfaceUpdater.refresh()
                }
            } else {
                // Refusing to delete the last one is deliberate: the app would have nowhere to
                // put the next reading and no way to make a profile to fix it.
                _message.value = strings[R.string.settings_there_has_to_be_somebody_add]
            }
        }
    }

    // ---- automatic backup ----

    /** Where the weekly copy goes, and when the last one was written. */
    data class AutoBackupState(
        val folder: String? = null,
        val lastAt: Long? = null,
        /** The folder has gone, so the weekly copy is not happening. */
        val problem: Boolean = false,
    )

    fun refreshAutoBackup() {
        viewModelScope.launch {
            _autoBackup.value = AutoBackupState(
                folder = settingsRepository.autoBackupFolder(),
                lastAt = settingsRepository.lastAutoBackup(),
                problem = settingsRepository.autoBackupProblem(),
            )
        }
    }

    /**
     * Takes the folder somebody picked and holds on to the right to write to it.
     *
     * Without taking the permission the folder stops working the next time the app starts, and
     * the weekly job would fail forever with nothing on screen to explain why.
     */
    fun useAutoBackupFolder(uri: android.net.Uri, holdOnTo: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            holdOnTo(uri)
            settingsRepository.setAutoBackupFolder(uri.toString())
            autoBackupScheduler.reschedule()
            refreshAutoBackup()
        }
    }

    fun turnOffAutoBackup() {
        viewModelScope.launch {
            settingsRepository.setAutoBackupFolder(null)
            autoBackupScheduler.reschedule()
            refreshAutoBackup()
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
            // Nothing is stored when the phone will not encrypt the password, so sync stays off
            // and is not scheduled. Saying so is the whole point: the alternative was writing the
            // password down in the clear and letting somebody believe otherwise.
            if (!syncPreferences.useWebDav(url, user, password)) {
                _message.value = strings[R.string.secret_not_stored_password]
                return@launch
            }
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
