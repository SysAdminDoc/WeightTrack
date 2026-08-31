package com.weighttrack.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.sync.SyncChanges
import com.weighttrack.data.sync.SyncResult
import com.weighttrack.diagnostics.RuntimeLog
import com.weighttrack.diagnostics.WorkOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The hourly job, driven.
 *
 * This replaces a test that read `SyncWorker.kt` and asserted on the text of it. That is not a
 * test: it broke on a rename that changed no behaviour, and it would have passed just as happily
 * with the call it looked for moved into a branch nothing reaches.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /** Records what was asked and in what order, and answers whatever the test wants. */
    private class Recording(
        val healthAnswer: suspend () -> WorkOutcome = { WorkOutcome.DONE },
        val folderAnswer: suspend () -> SyncResult? = { null },
    ) : SyncWork {
        val asked = mutableListOf<String>()

        override suspend fun health(): WorkOutcome {
            asked += "health"
            return healthAnswer()
        }

        override suspend fun folder(): SyncResult? {
            asked += "folder"
            return folderAnswer()
        }
    }

    private fun worker(work: SyncWork): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SyncWorker(
                        appContext,
                        workerParameters,
                        work,
                        RuntimeLog(File(temporary.newFolder(), "log.txt")),
                    )
                },
            )
            .build()

    @Test
    fun `a refused sync is not retried`() = runTest {
        // A wrong password will still be wrong in an hour, and retrying would hammer somebody
        // else's server for nothing. The reason is on the settings screen for them to read.
        val work = Recording(folderAnswer = { SyncResult.Refused("wrong password") })

        val result = worker(work).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `a server that could not be reached is tried again`() = runTest {
        val work = Recording(folderAnswer = { SyncResult.Unreachable("no route to host") })

        val result = worker(work).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `Health Connect is exchanged with before the folder settings are read`() = runTest {
        // Somebody who syncs a scale through Health Connect and keeps no folder must still get
        // their readings: reading the folder settings first and returning early skips them.
        val work = Recording()

        worker(work).doWork()

        assertThat(work.asked).containsExactly("health", "folder").inOrder()
    }

    @Test
    fun `a Health Connect failure is tried again even when nothing else is set up`() = runTest {
        val work = Recording(healthAnswer = { WorkOutcome.RETRY }, folderAnswer = { null })

        val result = worker(work).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `a folder that synced cleanly does not undo a Health Connect failure`() = runTest {
        // The two are separate exchanges. A folder sync going through says nothing about whether
        // the scale's readings arrived, and reporting success would leave the person with a sync
        // that says it works and a reading that never comes.
        val work = Recording(
            healthAnswer = { WorkOutcome.RETRY },
            folderAnswer = { SyncResult.Done(SyncChanges(), devices = 1) },
        )

        val result = worker(work).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `nothing set up at all is a quiet success`() = runTest {
        val work = Recording(folderAnswer = { SyncResult.NotSetUp })

        assertThat(worker(work).doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `the decision is the same whichever way round it is asked`() {
        // Every branch, in one place, without the harness.
        assertThat(SyncWorker.outcomeFor(WorkOutcome.DONE, null)).isEqualTo(WorkOutcome.DONE)
        assertThat(SyncWorker.outcomeFor(WorkOutcome.RETRY, null)).isEqualTo(WorkOutcome.RETRY)
        assertThat(SyncWorker.outcomeFor(WorkOutcome.DONE, SyncResult.NotSetUp))
            .isEqualTo(WorkOutcome.DONE)
        assertThat(SyncWorker.outcomeFor(WorkOutcome.DONE, SyncResult.Refused("x")))
            .isEqualTo(WorkOutcome.DONE)
        assertThat(SyncWorker.outcomeFor(WorkOutcome.DONE, SyncResult.Unreachable("x")))
            .isEqualTo(WorkOutcome.RETRY)
        assertThat(SyncWorker.outcomeFor(WorkOutcome.RETRY, SyncResult.Done(SyncChanges(), 1)))
            .isEqualTo(WorkOutcome.RETRY)
    }
}
