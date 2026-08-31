package com.weighttrack.sync

import com.weighttrack.data.sync.SyncEngine
import com.weighttrack.data.sync.SyncPreferences
import com.weighttrack.data.sync.SyncResult
import com.weighttrack.diagnostics.LogArea
import com.weighttrack.diagnostics.LogEvent
import com.weighttrack.diagnostics.RuntimeLog
import com.weighttrack.diagnostics.WorkOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two exchanges the hourly job makes, behind one call each.
 *
 * A seam, so the job itself can be driven. Everything the worker decides comes down to what these
 * two answered, and until now the only way to check that decision was to read the source of the
 * worker and assert on the text of it, which is not a test: it passes just as happily with the
 * call it looks for moved into a branch nothing reaches, and it breaks on a rename that changes
 * no behaviour.
 */
interface SyncWork {

    /** Exchanges with Health Connect, when there is anything to exchange with. */
    suspend fun health(): WorkOutcome

    /**
     * Syncs the folder or the server.
     *
     * Null when there is nothing set up, which is not a failure and is not the same as a sync
     * that ran and found nothing.
     */
    suspend fun folder(): SyncResult?
}

/** The real one. */
@Singleton
class RealSyncWork @Inject constructor(
    private val engine: SyncEngine,
    private val preferences: SyncPreferences,
    private val runtimeLog: RuntimeLog,
    private val healthConnect: com.weighttrack.health.HealthConnectSync,
    private val surfaces: com.weighttrack.widget.SurfaceUpdater,
    private val scheduler: SyncScheduler,
) : SyncWork {

    /**
     * Answers done when it is not connected: that is not a failure and retrying would achieve
     * nothing. A failure is worth another go, since the usual cause is the provider being busy.
     */
    override suspend fun health(): WorkOutcome {
        if (healthConnect.availability() != com.weighttrack.health.HealthConnectAvailability.INSTALLED) {
            return WorkOutcome.DONE
        }
        if (!healthConnect.hasPermissions()) return WorkOutcome.DONE
        // Given up or revoked since the job was booked. Either way there is nothing to do and
        // nothing retrying would fix, so the job stands itself down and Settings offers it back.
        if (!healthConnect.backgroundReadIsPossible() || !healthConnect.isTiedToAProfile()) {
            runCatching { scheduler.reschedule() }
            return WorkOutcome.DONE
        }
        val result = runCatching { healthConnect.sync() }.getOrElse {
            runtimeLog.write(LogArea.SYNC, LogEvent.BACKGROUND_SYNC_THREW, cause = it)
            return WorkOutcome.RETRY
        }
        return result.fold(
            onSuccess = { summary ->
                // Only when something changed, so a quiet hourly run does not keep rebuilding
                // the widget and waking the watch for nothing.
                if (summary.imported > 0 || summary.removed > 0) {
                    runCatching { surfaces.refresh() }
                }
                WorkOutcome.DONE
            },
            onFailure = { WorkOutcome.RETRY },
        )
    }

    override suspend fun folder(): SyncResult? {
        val settings = preferences.current()
        if (!settings.isOn || !settings.isReady || !settings.syncInBackground) return null
        return runCatching { engine.syncNow() }.getOrElse {
            // A worker that throws is a crash nobody asked for and nobody sees. Whatever went
            // wrong is worth another go later rather than taking the process with it.
            runtimeLog.write(LogArea.SYNC, LogEvent.BACKGROUND_SYNC_THREW, cause = it)
            SyncResult.Unreachable("")
        }
    }
}
