package com.weighttrack.data.io

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Keeping a few copies of the export in a folder the person chose.
 *
 * The roadmap has said since the first version that automatic local backups are the answer to the
 * thing that gets a tracker one star, and until now the only backup was one somebody remembered to
 * take. Cloud backup is switched off deliberately, so if this app loses somebody's history there
 * is nowhere else it exists.
 *
 * The naming and the pruning are here, with no Android in them, because they are the parts that
 * can quietly go wrong: a name that does not sort, or a rule that keeps the wrong four.
 */
object AutoBackup {

    /** How many to keep. Enough to go back a month at a weekly run. */
    const val KEEP = 4

    private const val PREFIX = "weighttrack-"
    private const val SUFFIX = ".json"

    /**
     * What marks a half-written backup, before the extension rather than after it.
     *
     * A name ending ".json.part" looks like the obvious answer and is not: a document provider
     * creating a file with the JSON media type appends its own extension when the one given does
     * not match, so the file arrives as "weighttrack-2026-08-29.json.part.json" and nothing can
     * find it again. Kept inside the stem, the name is one the provider leaves alone.
     */
    const val PARTIAL_MARKER = "-part"

    /**
     * The name a backup taken on [date] goes under.
     *
     * The date is in the name and in ISO order, so the folder sorts chronologically for a person
     * reading it and for the pruning below, without either having to open a file.
     */
    fun nameFor(date: LocalDate): String = "$PREFIX$date$SUFFIX"

    /**
     * Where the week's backup is written before it is allowed to become the backup.
     *
     * A run on a day that already has one used to open the good copy and write straight over it.
     * A crash, a full card or a card pulled out halfway leaves a truncated file where the only
     * copy of somebody's history was, and the weekly job that exists to stop them losing it is
     * the thing that lost it.
     */
    fun partialNameFor(date: LocalDate): String = "$PREFIX$date$PARTIAL_MARKER$SUFFIX"

    /** The date in a name this object wrote, or null for anything else in the folder. */
    fun dateOf(name: String): LocalDate? {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null
        val middle = name.removePrefix(PREFIX).removeSuffix(SUFFIX)
        return try {
            LocalDate.parse(middle)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Leftovers from a run that died before it could put its file in place. */
    fun partialsIn(names: List<String>): List<String> = names.filter {
        it.startsWith(PREFIX) && it.endsWith("$PARTIAL_MARKER$SUFFIX")
    }

    /** Every backup in the folder, newest first. Anything else there is ignored. */
    fun backupsIn(names: List<String>): List<String> = names
        .mapNotNull { name -> dateOf(name)?.let { it to name } }
        .sortedByDescending { it.first }
        .map { it.second }

    /**
     * Which files to remove once [keep] have been kept.
     *
     * Only files this object wrote. A folder is somebody's own, and something else living in it
     * is none of the app's business: deleting an unrecognised file would be the worst possible
     * outcome of a feature whose entire purpose is not losing things.
     */
    fun toRemove(names: List<String>, keep: Int = KEEP): List<String> =
        backupsIn(names).drop(keep)
}
