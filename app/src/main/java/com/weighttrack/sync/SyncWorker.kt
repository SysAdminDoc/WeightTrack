package com.weighttrack.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.weighttrack.data.sync.SyncEngine
import com.weighttrack.data.sync.SyncMode
import com.weighttrack.data.sync.SyncPreferences
import com.weighttrack.data.sync.SyncResult
import com.weighttrack.diagnostics.LogArea
import com.weighttrack.diagnostics.LogEvent
import com.weighttrack.diagnostics.RuntimeLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncing on its own, now and then.
 *
 * Nothing here is urgent. A weight written on one phone reaching the other within the hour is
 * fine, and asking Android for anything tighter would cost battery for no benefit anybody would
 * notice.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: SyncEngine,
    private val preferences: SyncPreferences,
    private val runtimeLog: RuntimeLog,
    private val healthConnect: com.weighttrack.health.HealthConnectSync,
    private val surfaces: com.weighttrack.widget.SurfaceUpdater,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        // Health Connect first, and whether or not folder sync is set up. Everything the changes
        // walk is for, a reading added or deleted in the scale's own app, used to reach the app
        // only when somebody opened Settings and pressed the button, which meant the trend, the
        // widget and the watch all sat stale until they did.
        val health = syncHealthConnect()

        val settings = preferences.current()
        if (!settings.isOn || !settings.isReady || !settings.syncInBackground) return health
        val outcome = runCatching { engine.syncNow() }.getOrElse {
            // A worker that throws is a crash nobody asked for and nobody sees. Whatever went
            // wrong is worth another go later rather than taking the process with it.
            runtimeLog.write(LogArea.SYNC, LogEvent.BACKGROUND_SYNC_THREW, cause = it)
            return Result.retry()
        }
        return when (outcome) {
            is SyncResult.Done -> health
            // Worth another go later: no signal, a server having a bad day.
            is SyncResult.Unreachable -> Result.retry()
            // A wrong password will still be wrong in an hour, and retrying would hammer somebody
            // else's server for nothing. The reason is on the settings screen for them to read.
            is SyncResult.Refused -> Result.success()
            SyncResult.NotSetUp -> health
        }
    }

    /**
     * Exchanges with Health Connect, when there is anything to exchange with.
     *
     * Answers success when it is not connected: that is not a failure and retrying would achieve
     * nothing. A failure is worth another go, since the usual cause is the provider being busy.
     */
    private suspend fun syncHealthConnect(): Result {
        if (healthConnect.availability() != com.weighttrack.health.HealthConnectAvailability.INSTALLED) {
            return Result.success()
        }
        if (!healthConnect.hasPermissions()) return Result.success()
        val result = runCatching { healthConnect.sync() }.getOrElse {
            runtimeLog.write(LogArea.SYNC, LogEvent.BACKGROUND_SYNC_THREW, cause = it)
            return Result.retry()
        }
        return result.fold(
            onSuccess = { summary ->
                // Only when something changed, so a quiet hourly run does not keep rebuilding
                // the widget and waking the watch for nothing.
                if (summary.imported > 0 || summary.removed > 0) {
                    runCatching { surfaces.refresh() }
                }
                Result.success()
            },
            onFailure = { Result.retry() },
        )
    }
}

/** Turns the background job on and off to match what sync is set to. */
@Singleton
class SyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: SyncPreferences,
    private val healthConnect: com.weighttrack.health.HealthConnectSync,
) {
    suspend fun reschedule() {
        val settings = preferences.current()
        val manager = WorkManager.getInstance(context)
        // Health Connect keeps the job alive on its own. Somebody who syncs a scale through it
        // and keeps no folder would otherwise have nothing running, and a reading added on the
        // scale would wait for them to open Settings.
        val forHealth = healthConnect.availability() ==
            com.weighttrack.health.HealthConnectAvailability.INSTALLED &&
            healthConnect.hasPermissions()
        val forSync = settings.mode != SyncMode.OFF && settings.syncInBackground
        if (!forSync && !forHealth) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    // A folder on the phone needs nothing; a WebDAV server needs the network.
                    // Asking for it either way is simpler and costs a folder sync nothing, since
                    // a phone with no connection at all is one nobody is syncing between.
                    .setRequiredNetworkType(
                        if (settings.mode == SyncMode.WEBDAV) {
                            NetworkType.CONNECTED
                        } else {
                            NetworkType.NOT_REQUIRED
                        },
                    )
                    .build(),
            )
            .build()
        // Kept rather than replaced, so turning a switch on and off does not push the next run
        // an hour into the future every time.
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val WORK_NAME = "weighttrack-sync"
    }
}
