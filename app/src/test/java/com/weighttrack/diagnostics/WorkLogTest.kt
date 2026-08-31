package com.weighttrack.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What a background run leaves behind.
 *
 * Everything this app does that nobody watches happens in a worker, and until now a run that
 * Android stopped partway, or one that returned retry for a reason nothing wrote down, left
 * nothing at all. "It just stopped backing up" had no evidence behind it.
 */
@RunWith(RobolectricTestRunner::class)
class WorkLogTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var log: RuntimeLog

    /**
     * A real worker, built the way WorkManager builds one.
     *
     * Real because [recorded] reads the run attempt and the platform's stop reason off the
     * worker itself, and a hand-made stand-in would have neither.
     */
    class Probe(context: Context, parameters: WorkerParameters) :
        androidx.work.CoroutineWorker(context, parameters) {

        override suspend fun doWork(): Result = Result.success()
    }

    private fun worker(): Probe = TestListenableWorkerBuilder<Probe>(
        ApplicationProvider.getApplicationContext(),
    ).build()

    private fun lines(): List<String> = log.read().lines().filter { it.isNotBlank() }

    private fun newLog(): RuntimeLog {
        log = RuntimeLog(File(temporary.newFolder(), "runtime-log.txt"))
        return log
    }

    @Test
    fun `a run that succeeds leaves one started line and one finished line`() = runTest {
        newLog()
        val result = worker().recorded(log, LogTask.AUTO_BACKUP) { WorkOutcome.DONE }

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        val entries = lines()
        assertThat(entries).hasSize(2)
        assertThat(entries[0]).contains("work work_started auto_backup")
        assertThat(entries[1]).contains("work work_succeeded auto_backup")
    }

    @Test
    fun `a run that asks to be repeated says so, once`() = runTest {
        newLog()

        worker().recorded(log, LogTask.SYNC) { WorkOutcome.RETRY }

        val terminal = lines().drop(1)
        assertThat(terminal).hasSize(1)
        assertThat(terminal.single()).contains("work_retry sync")
    }

    @Test
    fun `a run that gives up says so rather than asking to be repeated`() = runTest {
        newLog()

        val result = worker().recorded(log, LogTask.AUTO_BACKUP) { WorkOutcome.FAILED }

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        val terminal = lines().drop(1)
        assertThat(terminal).hasSize(1)
        assertThat(terminal.single()).contains("work_failed auto_backup")
    }

    @Test
    fun `a run that throws records the failure and lets it out`() = runTest {
        newLog()

        val thrown = runCatching {
            worker().recorded(log, LogTask.SYNC) { error("nothing works") }
        }

        assertThat(thrown.isFailure).isTrue()
        val terminal = lines().drop(1)
        assertThat(terminal).hasSize(1)
        assertThat(terminal.single()).contains("work_failed sync")
        // The class, not the message: a message carries paths, host names and user names, and
        // this log is meant to be shareable.
        assertThat(terminal.single()).contains("cause=java.lang.IllegalStateException")
        assertThat(terminal.single()).doesNotContain("nothing works")
    }

    @Test
    fun `a run the platform stops is recorded and still stops`() = runTest {
        newLog()

        val thrown = runCatching {
            worker().recorded(log, LogTask.AUTO_BACKUP) { throw CancellationException("stopped") }
        }

        // Rethrown. Swallowing it would leave the job running after Android asked it to stop.
        assertThat(thrown.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        val terminal = lines().drop(1)
        assertThat(terminal).hasSize(1)
        assertThat(terminal.single()).contains("work_stopped auto_backup")
        assertThat(terminal.single()).contains("code=")
    }

    @Test
    fun `a startup step that fails is recorded and the launch carries on`() {
        newLog()
        var reached = false

        log.step(LogTask.STARTUP_REMINDERS) { error("no alarm manager") }
        log.step(LogTask.STARTUP_PROFILES) { reached = true }

        assertThat(reached).isTrue()
        val entries = lines()
        assertThat(entries).hasSize(1)
        assertThat(entries.single()).contains("startup startup_failed startup_reminders")
        assertThat(entries.single()).doesNotContain("no alarm manager")
    }

    @Test
    fun `nothing a caller holds can reach the log`() {
        newLog()

        // Everything the log will accept: an area, an event, a task, a number and an exception
        // class. There is no free text anywhere in the shape of it, so a password, a server
        // address or a weight cannot get in even by mistake.
        log.write(
            LogArea.WORK,
            LogEvent.WORK_FAILED,
            code = 503,
            cause = IllegalArgumentException("https://user:hunter2@nas.example/weights 82.4 kg"),
            task = LogTask.SYNC,
        )

        val line = lines().single()
        assertThat(line).doesNotContain("hunter2")
        assertThat(line).doesNotContain("nas.example")
        assertThat(line).doesNotContain("82.4")
        assertThat(line).contains("code=503")
    }
}
