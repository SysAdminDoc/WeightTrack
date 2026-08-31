package com.weighttrack.diagnostics

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where in the app something happened.
 *
 * Few on purpose. A log nobody can scan is a log nobody reads.
 */
enum class LogArea { SYNC, HEALTH_CONNECT, SCALE, SECRETS }

/**
 * What happened. A closed list, and that is the point.
 *
 * Nothing here is written by a caller at the moment of failure, so nothing a person owns, a
 * weight, a food, a server address, a file name, can reach the log even by mistake. Adding an
 * event means adding it here, in daylight, where the name gets read by somebody.
 */
enum class LogEvent {
    SYNC_REFUSED,
    SYNC_UNREACHABLE,
    SYNC_FINISHED,
    LOCAL_NETWORK_NOT_ALLOWED,
    WEBDAV_REQUEST_FAILED,
    WEBDAV_TRANSPORT_FAILED,
    BACKGROUND_SYNC_THREW,
    HEALTH_SYNC_FAILED,
    HEALTH_READ_FAILED,
    HEALTH_WRITE_FAILED,
    HEALTH_PERMISSION_MISSING,
    SCALE_SCAN_FAILED,
    SCALE_CONNECT_FAILED,
    SCALE_DISCONNECTED,
    SCALE_BOND_LOST,
    BACKUP_FOLDER_GONE,
    BACKUP_FAILED,

    /**
     * The phone would not give a key, so a password was not stored at all.
     *
     * Worth a line of its own. The alternative used to be writing the password down in the clear,
     * which nobody was told about and nobody could have found out afterwards.
     */
    SECRET_NOT_PROTECTED,
}

/**
 * A short record of what the app tried to do and how it went.
 *
 * The app had nothing like this. A crash left a report, but everything that fails quietly, which
 * is most of what can go wrong here, left nothing at all: a sync that did nothing, a scale that
 * connected and never spoke, a Health Connect grant withdrawn months ago. "It just stopped
 * working" was impossible to answer.
 *
 * Meant to be shareable, which is why an entry is an [LogArea], a [LogEvent], an optional number
 * and the class of an exception, and nothing else. There is no free text anywhere in it. An
 * exception's message is deliberately dropped: those carry paths, host names and usernames.
 *
 * Plain `java.io.File` work with no Android types, like [CrashLogStore] beside it, so the awkward
 * parts are testable on the JVM.
 */
class RuntimeLog(
    private val file: File,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) {

    /**
     * Appends one entry, trimming the file if it has grown past its cap.
     *
     * [code] is a number about the machinery: an HTTP status, a GATT status, or a count of what
     * a sync moved. Never a measurement, and never anything that would identify a person or a
     * thing they logged.
     *
     * Never throws. Failing to record a failure must not become a second one, and every caller
     * here is already on an unhappy path.
     */
    @Synchronized
    fun write(area: LogArea, event: LogEvent, code: Int? = null, cause: Throwable? = null) {
        runCatching {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val line = buildString {
                append(TIME_FORMAT.format(now().atZone(zone)))
                append(' ')
                append(area.name.lowercase())
                append(' ')
                append(event.name.lowercase())
                code?.let { append(" code=").append(it) }
                cause?.let { append(" cause=").append(it.javaClass.name) }
            }
            file.appendText(line + "\n")
            if (file.length() > MAX_BYTES) trim()
        }
    }

    /** Everything currently kept, oldest first. Empty when nothing has been written. */
    @Synchronized
    fun read(): String = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")

    @Synchronized
    fun clear() {
        runCatching { if (file.isFile) file.delete() }
    }

    fun isEmpty(): Boolean = read().isBlank()

    /**
     * Drops the oldest half.
     *
     * Halving rather than trimming back to the cap keeps this from running on nearly every write
     * once the file is full, which would mean rewriting half a megabyte every time a scale fails
     * to answer.
     */
    private fun trim() {
        runCatching {
            val lines = file.readLines()
            file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }

    companion object {
        const val FILE_NAME = "runtime-log.txt"

        private const val MAX_BYTES = 512L * 1024

        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
