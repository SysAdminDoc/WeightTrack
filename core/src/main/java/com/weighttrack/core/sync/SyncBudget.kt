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

        val longest = longestString(document)
        if (longest > MAX_STRING) return "a stored value of $longest characters"
        return null
    }

    /**
     * The longest single string anywhere in a document.
     *
     * Only the fields a person can put text in. A million-character note is not a note, and one
     * of them is enough to make every list that renders it unusable on the other phone.
     */
    private fun longestString(document: SyncDocument): Int = maxOf(
        document.profiles.maxOfOrNull { it.name.length } ?: 0,
        document.weights.maxOfOrNull { maxOf(it.note?.length ?: 0, it.syncId.length) } ?: 0,
        document.measurements.maxOfOrNull { it.note?.length ?: 0 } ?: 0,
        document.fasts.maxOfOrNull { it.note?.length ?: 0 } ?: 0,
        document.foods.maxOfOrNull { maxOf(it.name.length, it.brand?.length ?: 0) } ?: 0,
        document.recipes.maxOfOrNull { it.name.length } ?: 0,
        document.foodLog.maxOfOrNull { it.name.length } ?: 0,
    )

    private const val BUFFER_BYTES = 64 * 1024
}
