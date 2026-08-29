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
        }
    }
}
