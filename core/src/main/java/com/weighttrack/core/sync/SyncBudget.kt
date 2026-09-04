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
     * How many devices one household's file may name.
     *
     * Nothing like the record ceiling, because a name in these lists is not a row: it is
     * something every phone then waits for before it will forget a deletion, for ever if that
     * device never publishes. Two hundred is already far past a house with phones, watches and a
     * tablet in it, and it is small enough that a file trying to jam the rule is refused.
     */
    const val MAX_PEERS = 200

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
            "doses" to document.medicationDoses.size,
            "side effects" to document.sideEffects.size,
        ).firstOrNull { (_, count) -> count > MAX_RECORDS }
        if (tooMany != null) return "${tooMany.first}: ${tooMany.second}"

        // The device lists get a ceiling of their own, far below the one above. Every name in
        // them is something the tombstone rule then waits for before it will forget anything,
        // and a device that never publishes a file is waited for for ever, so a file full of
        // invented names stops every phone in the house forgetting any deletion at all. There is
        // no way back from that short of erasing everything. A household is single digits.
        val crowded = listOf(
            "devices" to document.peers.size,
            "device positions" to document.observed.size,
        ).firstOrNull { (_, count) -> count > MAX_PEERS }
        if (crowded != null) return "${crowded.first}: ${crowded.second}"

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
        // The writer's own name, which is not on any row and reaches the peer table by its own
        // door: the merge notes a peer for every document it accepts, so a file whose deviceId
        // is five million characters is persisted and then republished by this phone in its own
        // list, and every other device refuses this phone's file for good.
        add(document.deviceId)
        document.profiles.forEach {
            add(it.name); add(it.syncId); add(it.reminderDays)
            add(it.sex); add(it.activityLevel); add(it.stampDeviceId)
        }
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
            it.originPackage?.let(::add)
            it.originDevice?.let(::add)
            add(it.stampDeviceId)
        }
        document.measurements.forEach {
            add(it.syncId); add(it.profileSyncId); add(it.type); add(it.localDate)
            it.note?.let(::add); add(it.stampDeviceId)
        }
        document.water.forEach {
            add(it.syncId); add(it.profileSyncId); add(it.localDate); add(it.stampDeviceId)
        }
        document.fasts.forEach {
            add(it.syncId); add(it.profileSyncId); it.note?.let(::add); add(it.stampDeviceId)
        }
        document.goals.forEach {
            add(it.syncId); add(it.profileSyncId); add(it.direction); add(it.startDate)
            it.targetDate?.let(::add); add(it.stampDeviceId)
        }
        document.macroTargets.forEach {
            add(it.syncId); add(it.profileSyncId); add(it.basis)
            it.dayOfWeek?.let(::add); add(it.stampDeviceId)
        }
        document.foods.forEach {
            add(it.syncId); add(it.name); add(it.origin)
            it.brand?.let(::add); it.barcode?.let(::add); add(it.stampDeviceId)
        }
        document.recipes.forEach { add(it.syncId); add(it.name); add(it.stampDeviceId) }
        document.recipeItems.forEach {
            add(it.syncId); add(it.recipeSyncId); add(it.foodSyncId); add(it.stampDeviceId)
        }
        document.foodLog.forEach {
            add(it.syncId); add(it.profileSyncId); add(it.name); add(it.meal); add(it.localDate)
            it.foodSyncId?.let(::add); add(it.stampDeviceId)
        }
        document.deletions.forEach {
            add(it.syncId); add(it.profileSyncId); add(it.stampDeviceId)
        }
        document.medicationDoses.forEach {
            add(it.syncId); add(it.profileSyncId); add(it.localDate)
            add(it.drug); add(it.site); it.note?.let(::add); add(it.stampDeviceId)
        }
        document.sideEffects.forEach {
            add(it.syncId); add(it.profileSyncId); add(it.localDate)
            add(it.kind); add(it.severity); it.note?.let(::add); add(it.stampDeviceId)
        }
        document.peers.forEach { add(it.deviceId) }
        document.observed.forEach { add(it.deviceId) }
        document.settings?.let {
            add(it.weightUnit); add(it.lengthUnit); add(it.themeMode)
            add(it.sex); add(it.activityLevel); add(it.smoothingMode); add(it.stampDeviceId)
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
