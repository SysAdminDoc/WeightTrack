package com.weighttrack.data.io

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.diagnostics.LogArea
import com.weighttrack.diagnostics.LogEvent
import com.weighttrack.diagnostics.RuntimeLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a copy of the export into a folder, once a week.
 *
 * Cloud backup is switched off on purpose, so an export is the only copy of somebody's history
 * that exists anywhere. Until now the only export was one they remembered to take, which for most
 * people means none. The roadmap has named this as the answer to the thing that gets a tracker one
 * star since the first version.
 *
 * The folder is one they picked through the storage picker, so the app can see nothing else on the
 * phone, and it only ever removes files it wrote itself.
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val backupService: BackupService,
    private val settingsRepository: SettingsRepository,
    private val runtimeLog: RuntimeLog,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val folderUri = settingsRepository.autoBackupFolder() ?: return@withContext Result.success()
        val folder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folderUri))
            ?.takeIf { it.isDirectory && it.canWrite() }
        if (folder == null) {
            // The card was taken out, or the permission went when the app was reinstalled. Only
            // the person can put it right by picking the folder again, so retrying is pointless.
            runtimeLog.write(LogArea.SYNC, LogEvent.BACKUP_FOLDER_GONE)
            // Nothing will fix itself here: the card is out, or the permission went. Recorded so
            // the settings screen can say so, because a backup that has silently stopped is the
            // failure this feature exists to prevent.
            settingsRepository.setAutoBackupProblem(true)
            return@withContext Result.success()
        }

        val text = backupService.exportedJson().getOrElse {
            runtimeLog.write(LogArea.SYNC, LogEvent.BACKUP_FAILED, cause = it)
            return@withContext Result.retry()
        }

        val today = LocalDate.now()
        val name = AutoBackup.nameFor(today)
        val partialName = AutoBackup.partialNameFor(today)
        // Written beside the target, read back, and only then allowed to become it. A run on a
        // day that already has a backup used to open the good copy and write over it, so a crash
        // or a card pulled out halfway through left a truncated file where the only copy of
        // somebody's history had been.
        val written = runCatching {
            val partial = folder.findFile(partialName)?.takeIf { it.isFile }
                ?: folder.createFile(MIME, partialName)
                ?: error("could not create")
            writeAndCheck(partial, text)
            // The target is written from a copy that has already been proved good, and the
            // proved copy is not given up until the target itself reads back. Deleting the old
            // file and renaming over it looked tidier and was not: a provider that does not
            // support renaming, which several cloud ones do not, would have left the day with no
            // backup at all and no way back.
            val target = folder.findFile(name)?.takeIf { it.isFile }
                ?: folder.createFile(MIME, name)
                ?: error("could not create")
            runCatching { writeAndCheck(target, text) }.onFailure { failure ->
                // Writing over the target does truncate it, and a truncated file that still
                // carries a backup's name is worse than no file: the pruning counts it as one of
                // the four kept and throws out a real backup to make room for it. So it goes,
                // and the proved copy beside it is what this day has.
                runCatching { target.delete() }
                throw failure
            }
            partial.delete()
        }
        if (written.isFailure) {
            runtimeLog.write(
                LogArea.SYNC,
                LogEvent.BACKUP_FAILED,
                cause = written.exceptionOrNull(),
            )
            // The half-written copy is deliberately left where it is. Either it is rubbish, in
            // which case the next run overwrites it, or it is the only complete copy of this
            // week and throwing it away would be the one thing this feature must never do. It is
            // named so that nothing mistakes it for a backup in the meantime, and a person can
            // still pick it by hand from the restore chooser.
            return@withContext Result.retry()
        }

        val names = runCatching { folder.listFiles().mapNotNull { it.name } }.getOrDefault(emptyList())
        // Anything a killed earlier run left behind, so the folder does not fill up with them.
        AutoBackup.partialsIn(names).forEach { stale ->
            runCatching { folder.findFile(stale)?.delete() }
        }
        AutoBackup.toRemove(names).forEach { old ->
            runCatching { folder.findFile(old)?.delete() }
        }
        settingsRepository.setAutoBackupProblem(false)
        settingsRepository.setLastAutoBackup(System.currentTimeMillis())
        Result.success()
    }

    /**
     * Writes the export into one file and proves it landed whole.
     *
     * A short write and a provider that reported success are indistinguishable until something
     * reads the file back, and a truncated backup is worse than none: it looks like one.
     */
    private fun writeAndCheck(file: DocumentFile, text: String) {
        applicationContext.contentResolver.openOutputStream(file.uri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: error("could not write")
        val readBack = applicationContext.contentResolver.openInputStream(file.uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: error("could not read back")
        if (readBack != text || BackupCodec.decode(readBack) == null) {
            error("the written backup did not read back")
        }
    }

    private companion object {
        const val MIME = "application/json"
    }
}

/** Turns the weekly backup on and off to match whether a folder has been chosen. */
@Singleton
class AutoBackupScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun reschedule() {
        val manager = WorkManager.getInstance(context)
        if (settingsRepository.autoBackupFolder() == null) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS).build()
        // UPDATE so that a later version changing the period or the constraints reaches installs
        // that already have the job, which KEEP would leave on the old schedule for ever. It also
        // means picking the folder again does not restart the week.
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val WORK_NAME = "weighttrack-auto-backup"
    }
}
