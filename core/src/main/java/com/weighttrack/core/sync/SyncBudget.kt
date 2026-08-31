package com.weighttrack.core.sync

import java.io.InputStream

/**
 * How much a peer is allowed to ask this phone to hold.
 *
 * Both sync targets read a whole remote answer into memory before deciding whether it is one of
 * ours. Nothing bounded it: not the bytes, not the number of records, not the length of a single
 * string. A file in a shared folder is written by something outside this app, and a WebDAV server
 * is somebody else's machine, so "it will be a sensible size" is a hope rather than a fact. A
 * hundred megabytes of anything at all was enough to take the app down before a single line of it
 * had been parsed.
 *
 * The numbers are deliberately far above a real document and far below what hurts. A decade of
 * daily weigh-ins with a full food diary is a few megabytes; a name or a note is a sentence.
 */
object SyncBudget {

    /** The most a single device's file may be. */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** The most rows of any one kind a document may carry. */
    const val MAX_RECORDS = 250_000

    /** The longest a single stored string may be: a note, a name, a food. */
    const val MAX_STRING = 4_000

    /**
     * The most tags one reading may carry.
     *
     * There are a handful to choose from. A number this far above that is not somebody being
     * thorough.
     */
    const val MAX_TAGS = 100

    /**
     * Reads at most [limit] bytes, or answers null when there are more.
     *
     * Checked while reading rather than from a length the other side reported. A content provider
     * and an HTTP server can both say one thing and send another, and by the time the difference
     * shows up the memory is already gone.
     */
    fun readBounded(stream: InputStream, limit: Long = MAX_BYTES): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * What is wrong with a document, or null when nothing is.
     *
     * Counted after decoding because the parser is the only thing that can tell one collection
     * from another, and a document that decodes is already inside the byte ceiling above.
     */
    fun problemWith(document: SyncDocument): String? {
        val tooMany = listOf(
            "profiles" to document.profiles.size,
            "weights" to document.weights.size,
            "measurements" to document.measurements.size,
            "water" to document.water.size,
            "fasts" to document.fasts.size,
            "goals" to document.goals.size,
            "macro targets" to document.macroTargets.size,
            "foods" to document.foods.size,
            "recipes" to document.recipes.size,
            "recipe items" to document.recipeItems.size,
            "diary entries" to document.foodLog.size,
            "deletions" to document.deletions.size,
        ).firstOrNull { (_, count) -> count > MAX_RECORDS }
        if (tooMany != null) return "${tooMany.first}: ${tooMany.second}"

        // A list inside a row is a collection too. One weigh-in carrying a hundred thousand tags
        // is joined into a single database cell on arrival and counts against nothing above.
        val mostTags = document.weights.maxOfOrNull { it.tags.size } ?: 0
        if (mostTags > MAX_TAGS) return "tags on one reading: $mostTags"

        val longest = longestString(document)
        if (longest > MAX_STRING) return "a stored value of $longest characters"
        return null
    }

    /**
     * The longest single string anywhere in a document.
     *
     * Every string, not the ones that came to mind. A list of the interesting fields goes stale
     * the moment somebody adds one, and the field that was missed the first time round was the
     * tags on a weigh-in: not a note, not a name, joined into one database cell on arrival, and
     * thirty megabytes of it passed every check there was.
     */
    private fun longestString(document: SyncDocument): Int =
        allStrings(document).maxOfOrNull { it.length } ?: 0

    /**
     * Every piece of text a document carries, including the ones inside its own lists.
     *
     * A list of strings is a collection too: one weigh-in with a hundred thousand tags on it is
     * as much of a problem as a hundred thousand weigh-ins, and it counts against neither of the
     * per-collection limits.
     */
    private fun allStrings(document: SyncDocument): List<String> = buildList {
        document.profiles.forEach { add(it.name); add(it.syncId); add(it.reminderDays) }
        document.weights.forEach {
            add(it.syncId)
            add(it.profileSyncId)
            add(it.source)
            add(it.localDate)
            it.note?.let(::add)
            addAll(it.tags)
            it.compositionDevice?.let(::add)
            it.compositionProtocol?.let(::add)
            it.compositionQuality?.let(::add)
        }
        document.measurements.forEach {
            add(it.syncId); add(it.type); add(it.localDate); it.note?.let(::add)
        }
        document.water.forEach { add(it.syncId); add(it.localDate) }
        document.fasts.forEach { add(it.syncId); it.note?.let(::add) }
        document.goals.forEach {
            add(it.syncId); add(it.direction); add(it.startDate); it.targetDate?.let(::add)
        }
        document.macroTargets.forEach { add(it.syncId); add(it.basis); it.dayOfWeek?.let(::add) }
        document.foods.forEach {
            add(it.syncId); add(it.name); add(it.origin)
            it.brand?.let(::add); it.barcode?.let(::add)
        }
        document.recipes.forEach { add(it.syncId); add(it.name) }
        document.recipeItems.forEach { add(it.syncId); add(it.recipeSyncId); add(it.foodSyncId) }
        document.foodLog.forEach {
            add(it.syncId); add(it.name); add(it.meal); add(it.localDate)
            it.foodSyncId?.let(::add)
        }
        document.deletions.forEach { add(it.syncId); add(it.profileSyncId) }
        document.settings?.let { add(it.weightUnit); add(it.lengthUnit); add(it.themeMode) }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
