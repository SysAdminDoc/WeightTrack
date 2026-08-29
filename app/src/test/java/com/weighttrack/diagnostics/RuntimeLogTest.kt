package com.weighttrack.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * What the runtime log keeps, and more importantly what it cannot be made to keep.
 *
 * This file is meant to be shareable: somebody reporting that sync stopped working should be able
 * to send it without sending their weight history along with it. That only holds if there is no
 * way to get personal data into a line, so half of what is tested here is the absence of one.
 */
class RuntimeLogTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun log(at: String = "2026-08-29T14:03:11Z"): Pair<RuntimeLog, File> {
        val file = File(temporary.root, "runtime-log.txt")
        return RuntimeLog(file, ZoneId.of("UTC")) { Instant.parse(at) } to file
    }

    @Test
    fun `an entry carries the time, the area and what happened`() {
        val (log, file) = log()

        log.write(LogArea.SYNC, LogEvent.WEBDAV_REQUEST_FAILED, code = 507)

        val line = file.readLines().single()
        assertThat(line).startsWith("2026-08-29 14:03:11")
        assertThat(line).contains("sync")
        assertThat(line).contains("webdav_request_failed")
        assertThat(line).contains("code=507")
    }

    @Test
    fun `a failed sync says what the server answered`() {
        val (log, _) = log()

        log.write(LogArea.SYNC, LogEvent.WEBDAV_REQUEST_FAILED, code = 507)

        // The whole reason for the log: "sync did nothing" becomes a status code somebody can
        // look up, without anybody having to reproduce it first.
        assertThat(log.read()).contains("code=507")
    }

    @Test
    fun `an exception is recorded by class and never by message`() {
        val (log, _) = log()
        val secret = "weighed 84.3 kg, saved to /storage/emulated/0/me.json"

        log.write(LogArea.HEALTH_CONNECT, LogEvent.HEALTH_READ_FAILED, cause = IllegalStateException(secret))

        assertThat(log.read()).contains("java.lang.IllegalStateException")
        assertThat(log.read()).doesNotContain(secret)
        assertThat(log.read()).doesNotContain("84.3")
        assertThat(log.read()).doesNotContain("storage")
    }

    @Test
    fun `nothing a person owns can reach a line`() {
        // The guarantee is structural rather than a matter of care at the call sites: an entry is
        // built from two enums, so there is no argument anywhere that could carry a weight, a
        // food, a server address or a file name.
        val naming = Regex("^[A-Z][A-Z_]*$")

        LogArea.entries.forEach { assertThat(it.name).matches(naming.pattern) }
        LogEvent.entries.forEach { assertThat(it.name).matches(naming.pattern) }
    }

    @Test
    fun `every event can be written and stays on one line`() {
        val (log, file) = log()

        LogArea.entries.forEach { area ->
            LogEvent.entries.forEach { event -> log.write(area, event) }
        }

        val expected = LogArea.entries.size * LogEvent.entries.size
        assertThat(file.readLines()).hasSize(expected)
        assertThat(file.readLines().none { it.contains('.') && it.substringAfter(' ').contains('.') })
            .isTrue()
    }

    @Test
    fun `entries build up in the order they happened`() {
        val file = File(temporary.root, "runtime-log.txt")
        var instant = Instant.parse("2026-08-29T14:00:00Z")
        val log = RuntimeLog(file, ZoneId.of("UTC")) { instant }

        log.write(LogArea.SYNC, LogEvent.SYNC_FINISHED)
        instant = instant.plusSeconds(61)
        log.write(LogArea.SYNC, LogEvent.SYNC_REFUSED)

        val lines = file.readLines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("sync_finished")
        assertThat(lines[1]).contains("sync_refused")
        assertThat(lines[1]).contains("14:01:01")
    }

    @Test
    fun `the file stops growing`() {
        val (log, file) = log()

        repeat(20_000) { log.write(LogArea.SCALE, LogEvent.SCALE_CONNECT_FAILED, code = it) }

        assertThat(file.length()).isLessThan(512L * 1024)
        // Trimming drops the oldest, so the failure that just happened is always still there.
        assertThat(log.read()).contains("code=19999")
    }

    @Test
    fun `clearing empties it`() {
        val (log, _) = log()
        log.write(LogArea.SYNC, LogEvent.WEBDAV_REQUEST_FAILED, code = 500)

        log.clear()

        assertThat(log.isEmpty()).isTrue()
        assertThat(log.read()).isEmpty()
    }

    @Test
    fun `reading before anything is written gives nothing`() {
        val (log, _) = log()

        assertThat(log.read()).isEmpty()
        assertThat(log.isEmpty()).isTrue()
    }

    @Test
    fun `a directory that does not exist yet is made`() {
        val file = File(temporary.root, "nested/deeper/runtime-log.txt")
        val log = RuntimeLog(file, ZoneId.of("UTC")) { Instant.parse("2026-08-29T14:03:11Z") }

        log.write(LogArea.SYNC, LogEvent.SYNC_FINISHED)

        assertThat(file.isFile).isTrue()
    }

    @Test
    fun `a write that cannot happen is not a crash`() {
        // The log lives on an unhappy path by definition. Failing to record a failure must not
        // become a second, louder failure.
        val log = RuntimeLog(temporary.root, ZoneId.of("UTC")) { Instant.parse("2026-08-29T14:03:11Z") }

        log.write(LogArea.SYNC, LogEvent.WEBDAV_REQUEST_FAILED, code = 500)

        assertThat(log.read()).isEmpty()
    }

    @Test
    fun `the log file is covered by the rules that keep data off backups`() {
        // It lives in internal storage beside the crash reports, and every domain is excluded, so
        // nothing here can leave the phone through a cloud backup or a device transfer.
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()

        listOf("cloud-backup", "device-transfer").forEach { section ->
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            assertThat(body).contains("<exclude domain=\"file\" />")
            assertThat(body).contains("<exclude domain=\"root\" />")
        }
    }
}
