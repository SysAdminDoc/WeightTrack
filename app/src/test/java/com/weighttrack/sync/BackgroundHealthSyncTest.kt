package com.weighttrack.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * That the background job actually touches Health Connect.
 *
 * Everything the changes walk is for, a reading added or deleted in the scale's own app, reached
 * the app only when somebody opened Settings and pressed Sync now: the worker drove the folder and
 * WebDAV sync and nothing else. The trend, the widget and the watch all sat stale until they did.
 *
 * Standing up WorkManager, Hilt and a Health Connect fake together to assert this would be a large
 * amount of scaffolding for one wiring question, so this reads the worker instead. The behaviour
 * either side of it is covered properly by `HealthChangesTest` and `HealthConnectImportTest`.
 */
class BackgroundHealthSyncTest {

    private val worker = File("src/main/java/com/weighttrack/sync/SyncWorker.kt").readText()

    @Test
    fun `the source is where this test thinks it is`() {
        assertThat(worker).contains("class SyncWorker")
        assertThat(worker).contains("override suspend fun doWork")
    }

    @Test
    fun `the worker exchanges with Health Connect`() {
        assertThat(worker).contains("healthConnect.sync()")
    }

    @Test
    fun `it does so before deciding whether folder sync is set up`() {
        // Somebody who syncs a scale through Health Connect and keeps no folder must still get
        // their readings: an early return on the folder settings would skip them entirely.
        val healthCall = worker.indexOf("val health = syncHealthConnect()")
        val folderCheck = worker.indexOf("if (!settings.isOn")

        assertThat(healthCall).isGreaterThan(0)
        assertThat(folderCheck).isGreaterThan(healthCall)
    }

    @Test
    fun `the job is kept alive for a Health Connect connection alone`() {
        // Otherwise the scheduler cancels the work as soon as folder sync is off, and the hourly
        // exchange somebody is relying on quietly stops existing.
        assertThat(worker).contains("forHealth")
        assertThat(worker).contains("if (!forSync && !forHealth)")
    }

    @Test
    fun `the widget and the watch are refreshed only when something changed`() {
        // A quiet hourly run must not keep rebuilding the widget and waking the watch.
        val refresh = worker.substringAfter("summary.imported > 0").substringBefore("WorkOutcome.DONE")

        assertThat(refresh).contains("surfaces.refresh()")
        assertThat(worker).contains("summary.removed > 0")
    }

    @Test
    fun `not being connected is not a failure to retry`() {
        val guard = worker.substringAfter("private suspend fun syncHealthConnect")
            .substringBefore("val result =")

        assertThat(guard).contains("WorkOutcome.DONE")
        assertThat(guard).contains("hasPermissions()")
    }
}
