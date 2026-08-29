package com.weighttrack.diagnostics

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One stored crash, identified by the file it lives in.
 *
 * [summary] is the first line of the report body, which is the exception class and message. It is
 * what the list shows, so a person can tell two crashes apart without opening either.
 */
data class CrashReport(
    val id: String,
    val timestamp: Instant,
    val summary: String,
) {
    fun formattedTime(zone: ZoneId = ZoneId.systemDefault()): String =
        TIME_FORMAT.format(timestamp.atZone(zone))

    private companion object {
        val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.getDefault())
    }
}

/**
 * Crash reports on disk.
 *
 * Deliberately plain `java.io.File` work with no Android types, so the awkward parts (writing
 * during a crash, pruning, a directory full of junk someone dropped in) can be tested on the
 * JVM. The app points it at internal storage, which no other app and no backup can read.
 */
class CrashLogStore(private val directory: File) {

    /**
     * Writes a report and prunes the oldest.
     *
     * Returns null rather than throwing when the write fails. This runs inside an uncaught
     * exception handler, where a second exception would replace the real crash with a
     * misleading one and could stop the system's own handler from ever running.
     */
    fun write(
        throwable: Throwable,
        threadName: String,
        buildInfo: String,
        timestamp: Instant = Instant.now(),
    ): CrashReport? = runCatching {
        if (!directory.exists() && !directory.mkdirs()) return null

        val body = buildString {
            appendLine(summarise(throwable))
            appendLine()
            appendLine("Time: $timestamp")
            appendLine("Thread: $threadName")
            appendLine(buildInfo)
            appendLine()
            append(stackTraceOf(throwable))
        }
        // Epoch millis keeps filename order and chronological order the same, which is what
        // lets listing avoid opening every file just to sort them.
        val file = File(directory, "$FILE_PREFIX${timestamp.toEpochMilli()}$FILE_SUFFIX")
        file.writeText(body)
        prune()
        CrashReport(file.name, timestamp, summarise(throwable))
    }.getOrNull()

    /** Newest first. Files that are not reports, or whose names carry no timestamp, are ignored. */
    fun list(): List<CrashReport> {
        val files = directory.listFiles() ?: return emptyList()
        return files
            .mapNotNull { file -> toReport(file) }
            .sortedByDescending { it.timestamp }
    }

    fun read(id: String): String? {
        val file = resolve(id) ?: return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun delete(id: String): Boolean {
        val file = resolve(id) ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    fun deleteAll() {
        directory.listFiles()?.forEach { file ->
            if (isReportFile(file)) runCatching { file.delete() }
        }
    }

    fun count(): Int = list().size

    /** Keeps the newest [MAX_REPORTS] so a crash loop cannot fill the device. */
    private fun prune() {
        val reports = list()
        if (reports.size <= MAX_REPORTS) return
        reports.drop(MAX_REPORTS).forEach { delete(it.id) }
    }

    /**
     * Resolves an id to a file inside the directory.
     *
     * The id comes back from the UI, so it is treated as untrusted: anything that is not a
     * plain report filename in this exact directory is refused rather than followed.
     */
    private fun resolve(id: String): File? {
        if (!isReportName(id)) return null
        val file = File(directory, id)
        if (file.parentFile?.absolutePath != directory.absolutePath) return null
        return file.takeIf { it.isFile }
    }

    private fun toReport(file: File): CrashReport? {
        if (!isReportFile(file)) return null
        val millis = file.name
            .removePrefix(FILE_PREFIX)
            .removeSuffix(FILE_SUFFIX)
            .toLongOrNull() ?: return null
        val summary = runCatching { file.useLines { it.firstOrNull() } }.getOrNull()
        return CrashReport(
            id = file.name,
            timestamp = Instant.ofEpochMilli(millis),
            summary = summary?.takeIf { it.isNotBlank() } ?: "Unknown crash",
        )
    }

    private fun isReportFile(file: File): Boolean = file.isFile && isReportName(file.name)

    private fun isReportName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) &&
            name.endsWith(FILE_SUFFIX) &&
            !name.contains('/') &&
            !name.contains('\\') &&
            name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).all { it.isDigit() }

    companion object {
        const val DIRECTORY_NAME = "crash-logs"
        const val MAX_REPORTS = 20
        private const val FILE_PREFIX = "crash-"
        private const val FILE_SUFFIX = ".txt"

        fun summarise(throwable: Throwable): String {
            val message = throwable.message?.trim()?.takeIf { it.isNotEmpty() }
            return if (message == null) {
                throwable.javaClass.name
            } else {
                "${throwable.javaClass.name}: $message"
            }
        }

        fun stackTraceOf(throwable: Throwable): String {
            val writer = StringWriter()
            PrintWriter(writer).use { throwable.printStackTrace(it) }
            return writer.toString()
        }
    }
}
