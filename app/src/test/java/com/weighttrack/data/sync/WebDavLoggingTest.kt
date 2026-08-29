package com.weighttrack.data.sync

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.sync.WebDavClient
import com.weighttrack.diagnostics.RuntimeLog
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * What a failed sync leaves behind for somebody to read afterwards.
 *
 * "Sync stopped working" was unanswerable: the screen showed a sentence, the sentence went away,
 * and nothing anywhere said what the server had actually said. A status code in a file with a
 * time against it is the difference between guessing and knowing.
 */
class WebDavLoggingTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var log: RuntimeLog

    private fun target(answer: (WebDavClient.Request) -> WebDavClient.Response): WebDavSyncTarget {
        log = RuntimeLog(
            File(temporary.root, "runtime-log.txt"),
            ZoneId.of("UTC"),
        ) { Instant.parse("2026-08-29T14:03:11Z") }
        return WebDavSyncTarget(
            baseUrl = "https://cloud.example.com/dav",
            username = "me",
            password = "secret",
            runtimeLog = log,
            exchange = { answer(it) },
        ) { _, _ -> "message" }
    }

    @Test
    fun `a server that refuses says so in the log, with the time and the status`() = runTest {
        val target = target { WebDavClient.Response(507, "insufficient storage") }

        val outcome = target.list()

        assertThat(outcome).isInstanceOf(SyncOutcome.Refused::class.java)
        val line = log.read().trim()
        assertThat(line).startsWith("2026-08-29 14:03:11")
        assertThat(line).contains("sync")
        assertThat(line).contains("webdav_request_failed")
        assertThat(line).contains("code=507")
    }

    @Test
    fun `a server having a bad day is recorded with its status too`() = runTest {
        val target = target { WebDavClient.Response(503, "later") }

        assertThat(target.list()).isInstanceOf(SyncOutcome.Unreachable::class.java)
        assertThat(log.read()).contains("code=503")
    }

    @Test
    fun `a request that never left the phone is recorded by what went wrong`() = runTest {
        val target = target { throw IOException("no route to host 192.168.1.50") }

        val outcome = target.list()

        assertThat(outcome).isInstanceOf(SyncOutcome.Unreachable::class.java)
        assertThat(log.read()).contains("webdav_transport_failed")
        assertThat(log.read()).contains("java.io.IOException")
        // The address is somebody's own network and belongs in nobody's bug report.
        assertThat(log.read()).doesNotContain("192.168.1.50")
    }

    @Test
    fun `a sync that works writes nothing to complain about`() = runTest {
        val target = target { WebDavClient.Response(207, EMPTY_LISTING) }

        assertThat(target.list()).isInstanceOf(SyncOutcome.Ok::class.java)
        // A log that fills up when nothing is wrong is one nobody reads when something is.
        assertThat(log.isEmpty()).isTrue()
    }

    @Test
    fun `a directory that is not there yet is not a failure worth logging`() = runTest {
        val target = target { WebDavClient.Response(404, "") }

        assertThat(target.list()).isInstanceOf(SyncOutcome.Ok::class.java)
        assertThat(log.isEmpty()).isTrue()
    }

    private companion object {
        val EMPTY_LISTING = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
    }
}
