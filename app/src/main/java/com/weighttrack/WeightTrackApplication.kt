package com.weighttrack

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.weighttrack.diagnostics.CrashReporter
import com.weighttrack.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WeightTrackApplication : Application(), Configuration.Provider {

    @Inject lateinit var crashReporter: CrashReporter

    /** Lets the background sync job be built with its dependencies rather than by hand. */
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var autoBackupScheduler: com.weighttrack.data.io.AutoBackupScheduler

    @Inject lateinit var syncPreferences: com.weighttrack.data.sync.SyncPreferences

    @Inject lateinit var settingsRepository: com.weighttrack.data.prefs.SettingsRepository

    @Inject lateinit var progressPhotos: com.weighttrack.data.repo.ProgressPhotoRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Installed first so a crash anywhere later in startup still leaves a report.
        crashReporter.install()
        // Put back after an update or a reboot, which clear scheduled work. Off the main thread
        // and unwaited: nothing on screen depends on it, and blocking startup on a database read
        // would be a visible cost for something nobody is watching.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { syncScheduler.reschedule() }
            // The weekly copy, put back for the same reason.
            runCatching { autoBackupScheduler.reschedule() }
            // A password stored before it was being encrypted would otherwise stay legible in the
            // file until somebody happened to edit it, which for most people is never.
            runCatching { syncPreferences.protectStoredSecrets() }
            runCatching { settingsRepository.protectStoredSecrets() }
            // A picture deleted with its undo still on screen is moved aside rather than
            // unlinked, and a process killed in that moment leaves it there with nothing that
            // knows about it. Collected on the next launch.
            runCatching { progressPhotos.purgeAbandonedRecovery() }
        }
    }
}
