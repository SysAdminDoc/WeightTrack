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
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val settings = preferences.current()
        if (!settings.isOn || !settings.isReady || !settings.syncInBackground) return Result.success()
        return when (engine.syncNow()) {
            is SyncResult.Done -> Result.success()
            // Worth another go later: no signal, a server having a bad day.
            is SyncResult.Unreachable -> Result.retry()
            // A wrong password will still be wrong in an hour, and retrying would hammer somebody
            // else's server for nothing. The reason is on the settings screen for them to read.
            is SyncResult.Refused -> Result.success()
            SyncResult.NotSetUp -> Result.success()
        }
    }
}

/** Turns the background job on and off to match what sync is set to. */
@Singleton
class SyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: SyncPreferences,
) {
    suspend fun reschedule() {
        val settings = preferences.current()
        val manager = WorkManager.getInstance(context)
        if (settings.mode == SyncMode.OFF || !settings.syncInBackground) {
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
                        if (settings.mode == SyncMode.WEBDAV) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
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
