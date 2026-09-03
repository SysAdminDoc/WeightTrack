package com.weighttrack.core.io

/** What a file handed to the app from outside turns out to be. */
enum class OpenedFileKind {
    /** A whole backup, which is shown before anything is written. */
    BACKUP,

    /** A spreadsheet of readings, which is imported the way the in-app picker imports one. */
    READINGS,
}

/**
 * Deciding what somebody just opened.
 *
 * Kept here rather than beside the content resolver, because the interesting part is the
 * judgement and not the lookup: a file manager will hand over a backup labelled
 * `application/octet-stream`, and a share sheet will hand over a text file labelled
 * `application/json`. Neither can be trusted on its own, and a wrong answer either loses
 * somebody's file to a "not supported" message or takes them into a restore they did not ask for.
 */
object OpenedFile {

    /**
     * What this is, or null when it is nothing the app can read.
     *
     * The declared type wins where it says something specific, because that is what the app that
     * wrote the file said it was. A generic type falls through to the name, which is the only
     * thing left. Null is a perfectly ordinary answer and the caller says so plainly rather than
     * guessing.
     */
    fun kindOf(mimeType: String?, displayName: String?): OpenedFileKind? =
        fromMimeType(mimeType) ?: fromName(displayName)

    private fun fromMimeType(mimeType: String?): OpenedFileKind? =
        when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "application/json", "text/json" -> OpenedFileKind.BACKUP
            "text/csv", "text/comma-separated-values", "application/csv" -> OpenedFileKind.READINGS
            else -> null
        }

    private fun fromName(displayName: String?): OpenedFileKind? {
        val name = displayName?.trim()?.lowercase() ?: return null
        // The extension only, and only when there is one. A file called "january.csv.txt" is a
        // text file whatever the middle of its name says.
        return when {
            name.endsWith(".json") -> OpenedFileKind.BACKUP
            name.endsWith(".csv") -> OpenedFileKind.READINGS
            else -> null
        }
    }
}
