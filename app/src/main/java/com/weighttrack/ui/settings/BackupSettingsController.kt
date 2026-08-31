package com.weighttrack.ui.settings

import android.net.Uri
import com.weighttrack.R
import com.weighttrack.core.io.RowProblem
import com.weighttrack.data.io.AutoBackupScheduler
import com.weighttrack.data.io.BackupService
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.ui.AppStrings
import com.weighttrack.widget.SurfaceUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the weekly copy goes, and when the last one was written. */
data class AutoBackupState(
    val folder: String? = null,
    val lastAt: Long? = null,
    /** The folder has gone, so the weekly copy is not happening. */
    val problem: Boolean = false,
)

/**
 * Everything that moves records off the phone or brings them back, off the view model.
 *
 * Owns the busy flag, because every one of these reads or writes a whole file and the screen has
 * to say so, and the pending restore, because a restore is the one action that reaches every
 * screen at once and is shown before it happens.
 */
internal class BackupSettingsController(
    private val scope: CoroutineScope,
    private val backupService: BackupService,
    private val settingsRepository: SettingsRepository,
    private val autoBackupScheduler: AutoBackupScheduler,
    private val surfaces: SurfaceUpdater,
    private val strings: AppStrings,
    private val onMessage: (String?) -> Unit,
) {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    private val _autoBackup = MutableStateFlow(AutoBackupState())
    val autoBackup: StateFlow<AutoBackupState> = _autoBackup.asStateFlow()

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
        scope.launch {
            _busy.value = true
            backupService.previewJson(uri).fold(
                onSuccess = { _pendingRestore.value = PendingRestore(uri, it) },
                onFailure = {
                    onMessage(strings[R.string.settings_restore_failed, it.message.orEmpty()])
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

    fun refreshAutoBackup() {
        scope.launch {
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
    fun useAutoBackupFolder(uri: Uri, holdOnTo: (Uri) -> Unit) {
        scope.launch {
            holdOnTo(uri)
            settingsRepository.setAutoBackupFolder(uri.toString())
            autoBackupScheduler.reschedule()
            refreshAutoBackup()
        }
    }

    fun turnOffAutoBackup() {
        scope.launch {
            settingsRepository.setAutoBackupFolder(null)
            autoBackupScheduler.reschedule()
            refreshAutoBackup()
        }
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

    /** One file operation: busy while it runs, whatever it answered on screen, surfaces redrawn. */
    private fun runBackup(block: suspend () -> String) {
        scope.launch {
            _busy.value = true
            onMessage(
                runCatching { block() }
                    .getOrElse { strings[R.string.settings_something_went_wrong, it.message.orEmpty()] },
            )
            surfaces.refresh()
            _busy.value = false
        }
    }
}
