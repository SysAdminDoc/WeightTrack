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
import com.weighttrack.diagnostics.WorkOutcome
import com.weighttrack.diagnostics.recorded
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
    private val work: SyncWork,
    private val runtimeLog: RuntimeLog,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result =
        recorded(runtimeLog, com.weighttrack.diagnostics.LogTask.SYNC) {
            // Health Connect first, and whether or not folder sync is set up. Everything the
            // changes walk is for, a reading added or deleted in the scale's own app, used to
            // reach the app only when somebody opened Settings and pressed the button, which
            // meant the trend, the widget and the watch all sat stale until they did.
            val health = work.health()
            outcomeFor(health, work.folder())
        }

    companion object {
        /**
         * What the run amounts to, given what the two exchanges said.
         *
         * Separate and pure so the decision can be read in one place. Nothing about it depends
         * on Android, and every branch of it matters to somebody whose sync has stopped.
         */
        fun outcomeFor(health: WorkOutcome, folder: SyncResult?): WorkOutcome = when (folder) {
            // Nothing set up, or nothing to do. Whatever Health Connect said stands.
            null, SyncResult.NotSetUp -> health
            is SyncResult.Done -> health
            // Worth another go later: no signal, a server having a bad day.
            is SyncResult.Unreachable -> WorkOutcome.RETRY
            // A wrong password will still be wrong in an hour, and retrying would hammer
            // somebody else's server for nothing. The reason is on the settings screen.
            is SyncResult.Refused -> WorkOutcome.DONE
        }
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
        // Background access as well as the read itself, where the provider has such a thing to
        // grant. An hourly job without it reads nothing and reports success, which leaves
        // somebody with a sync that says it works and a scale reading that never arrives; but
        // demanding a grant an older Health Connect cannot give would cancel the job for good on
        // phones where it worked perfectly well.
        val forHealth = healthConnect.availability() ==
            com.weighttrack.health.HealthConnectAvailability.INSTALLED &&
            healthConnect.hasPermissions() &&
            healthConnect.backgroundReadIsPossible() &&
            healthConnect.isTiedToAProfile()
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
