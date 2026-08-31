package com.weighttrack.diagnostics

import android.os.Build
import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException

/**
 * How a background run ended, in terms this app owns.
 *
 * WorkManager's own result classes are restricted to its library group, so there is no supported
 * way to ask a `Result` which of the three it is. Saying it here instead means the recording and
 * the value handed back to WorkManager cannot disagree about what happened.
 */
enum class WorkOutcome {
    DONE,

    /** Worth another go later: no signal, a server having a bad day, a busy provider. */
    RETRY,

    /** Nothing will fix itself. Retrying would repeat the same failure on a schedule. */
    FAILED,
}

/**
 * Runs a worker's body and leaves exactly one line saying how it ended.
 *
 * Background work is where this app does the things nobody watches: the hourly sync, the weekly
 * copy. Both already recorded the failures they could see, and neither recorded the ones they
 * could not: a run Android stopped partway, a run that never started, a run that asked to be
 * repeated for a reason nothing wrote down. "It just stopped backing up" had no evidence behind it.
 *
 * One started line and one terminal line per run, whichever way it ends, including the ways that
 * do not return: a cancellation is the platform stopping the job and is the most interesting of
 * them, so it is recorded and then rethrown, because swallowing it would leave the job running
 * after Android has asked it to stop.
 */
suspend fun ListenableWorker.recorded(
    log: RuntimeLog,
    task: LogTask,
    body: suspend () -> WorkOutcome,
): ListenableWorker.Result {
    log.write(LogArea.WORK, LogEvent.WORK_STARTED, task = task, code = runAttemptCount)
    try {
        val outcome = body()
        val event = when (outcome) {
            WorkOutcome.DONE -> LogEvent.WORK_SUCCEEDED
            WorkOutcome.RETRY -> LogEvent.WORK_RETRY
            WorkOutcome.FAILED -> LogEvent.WORK_FAILED
        }
        log.write(LogArea.WORK, event, task = task)
        return when (outcome) {
            WorkOutcome.DONE -> ListenableWorker.Result.success()
            WorkOutcome.RETRY -> ListenableWorker.Result.retry()
            WorkOutcome.FAILED -> ListenableWorker.Result.failure()
        }
    } catch (stopped: CancellationException) {
        // The platform's own reason where the phone has one. Below Android 12 it does not, and
        // -1 says so rather than pretending to a number nothing reported.
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason else -1
        log.write(LogArea.WORK, LogEvent.WORK_STOPPED, code = reason, task = task)
        throw stopped
    } catch (failure: Throwable) {
        log.write(LogArea.WORK, LogEvent.WORK_FAILED, cause = failure, task = task)
        throw failure
    }
}

/**
 * Runs one step of startup and records it if it fails.
 *
 * Every one of these was already wrapped so a failure could not take the launch down with it,
 * which is right and left nothing behind. A reminder that quietly stopped being booked after an
 * update is exactly the complaint this exists to answer.
 */
inline fun RuntimeLog.step(task: LogTask, body: () -> Unit) {
    runCatching(body).onFailure {
        write(LogArea.STARTUP, LogEvent.STARTUP_FAILED, cause = it, task = task)
    }
}
