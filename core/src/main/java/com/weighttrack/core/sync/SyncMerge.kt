package com.weighttrack.core.sync

/**
 * Puts several devices' files together into one answer.
 *
 * The rule is the same for every kind of record: the most recently touched version wins, and a
 * deletion counts as a touch. Nothing here knows about Android or about the database, so the
 * awkward cases can be written down as tests rather than found on a phone.
 *
 * Every device runs this over the same set of files and has to reach the same answer, or they
 * will hand edits back and forth forever. That is why ties are broken on the device identifier
 * instead of on which file happened to be read first.
 */
object SyncMerge {

    /**
     * How long a deletion is remembered.
     *
     * Long enough that a phone left in a drawer over a holiday does not bring back everything
     * deleted while it was away. Not so long that the file grows without end. A device quiet for
     * longer than this will resurrect what it still holds, which is worth saying plainly.
     */
    const val TOMBSTONE_LIFETIME_MILLIS: Long = 180L * 24 * 60 * 60 * 1000

    /**
     * The merged state of every document handed in.
     *
     * [documents] should include this device's own file. Order does not matter: the answer is
     * the same whichever way round they arrive, which is the property that makes two phones
     * settle instead of arguing.
     */
    fun merge(
        documents: List<SyncDocument>,
        deviceId: String,
        now: Long,
    ): SyncDocument {
        val deletions = mergeDeletions(documents, now)
        fun gone(kind: SyncKind, syncId: String, updatedAt: Long): Boolean {
            val deletedAt = deletions[kind to syncId] ?: return false
            // An edit made after the delete brings the record back, which is what somebody who
            // deleted a row on one phone and then corrected it on another actually meant.
            return deletedAt >= updatedAt
        }

        return SyncDocument(
            deviceId = deviceId,
            writtenAtUtcMillis = now,
            profiles = documents.newest({ it.profiles }, { it.syncId }, { it.updatedAtUtcMillis })
                .filterNot { gone(SyncKind.PROFILE, it.syncId, it.updatedAtUtcMillis) },
            weights = documents.newest({ it.weights }, { it.syncId }, { it.updatedAtUtcMillis })
                .filterNot { gone(SyncKind.WEIGHT, it.syncId, it.updatedAtUtcMillis) },
            measurements = documents
                .newest({ it.measurements }, { it.syncId }, { it.updatedAtUtcMillis })
                .filterNot { gone(SyncKind.MEASUREMENT, it.syncId, it.updatedAtUtcMillis) },
            water = documents.newest({ it.water }, { it.syncId }, { it.updatedAtUtcMillis })
                .filterNot { gone(SyncKind.WATER, it.syncId, it.updatedAtUtcMillis) },
            fasts = documents.newest({ it.fasts }, { it.syncId }, { it.updatedAtUtcMillis })
                .filterNot { gone(SyncKind.FAST, it.syncId, it.updatedAtUtcMillis) },
            goals = documents.newest({ it.goals }, { it.syncId }, { it.updatedAtUtcMillis })
                .filterNot { gone(SyncKind.GOAL, it.syncId, it.updatedAtUtcMillis) },
            macroTargets = documents
                .newest({ it.macroTargets }, { it.syncId }, { it.updatedAtUtcMillis })
                .filterNot { gone(SyncKind.MACRO_TARGET, it.syncId, it.updatedAtUtcMillis) },
            settings = newestSettings(documents),
            deletions = deletions.map { (key, at) ->
                SyncDeletion(kind = key.first, syncId = key.second, deletedAtUtcMillis = at)
            }.sortedWith(compareBy({ it.kind }, { it.syncId })),
        )
    }

    /**
     * A weigh-in only belongs to somebody. One whose profile has been deleted, or which arrived
     * from a device that never had that profile, has nowhere to go.
     *
     * Dropping such rows silently would lose data. They are handed back separately so the caller
     * can put them somewhere rather than pretend they never arrived.
     */
    fun orphans(merged: SyncDocument): Orphans {
        val profiles = merged.profiles.map { it.syncId }.toSet()
        return Orphans(
            weights = merged.weights.filterNot { it.profileSyncId in profiles },
            measurements = merged.measurements.filterNot { it.profileSyncId in profiles },
            water = merged.water.filterNot { it.profileSyncId in profiles },
            fasts = merged.fasts.filterNot { it.profileSyncId in profiles },
            goals = merged.goals.filterNot { it.profileSyncId in profiles },
            macroTargets = merged.macroTargets.filterNot { it.profileSyncId in profiles },
        )
    }

    data class Orphans(
        val weights: List<SyncWeight>,
        val measurements: List<SyncMeasurement>,
        val water: List<SyncWater>,
        val fasts: List<SyncFast>,
        val goals: List<SyncGoal>,
        val macroTargets: List<SyncMacroTarget>,
    ) {
        val isEmpty: Boolean
            get() = weights.isEmpty() && measurements.isEmpty() && water.isEmpty() &&
                fasts.isEmpty() && goals.isEmpty() && macroTargets.isEmpty()

        val count: Int
            get() = weights.size + measurements.size + water.size + fasts.size + goals.size +
                macroTargets.size
    }

    private fun mergeDeletions(
        documents: List<SyncDocument>,
        now: Long,
    ): Map<Pair<SyncKind, String>, Long> {
        val kept = mutableMapOf<Pair<SyncKind, String>, Long>()
        for (document in documents) {
            for (deletion in document.deletions) {
                // Forgotten once they are old enough. A tombstone from the future is somebody's
                // clock being wrong and is kept rather than discarded, which is the safe way
                // round: it holds a delete in place instead of undoing one.
                if (now - deletion.deletedAtUtcMillis > TOMBSTONE_LIFETIME_MILLIS) continue
                val key = deletion.kind to deletion.syncId
                val existing = kept[key]
                if (existing == null || deletion.deletedAtUtcMillis > existing) {
                    kept[key] = deletion.deletedAtUtcMillis
                }
            }
        }
        return kept
    }

    /**
     * The newest version of each record, across every file.
     *
     * Ties go to the file from the device whose identifier sorts highest. Arbitrary, but the
     * same everywhere, which is the only property that matters: two devices that break a tie
     * differently will each keep sending the other its own version, forever.
     */
    private fun <T> List<SyncDocument>.newest(
        select: (SyncDocument) -> List<T>,
        id: (T) -> String,
        updatedAt: (T) -> Long,
    ): List<T> {
        val best = LinkedHashMap<String, Pair<T, String>>()
        for (document in sortedBy { it.deviceId }) {
            for (record in select(document)) {
                val key = id(record)
                val existing = best[key]
                if (existing == null ||
                    updatedAt(record) > updatedAt(existing.first) ||
                    // Equal times: the higher device identifier wins. Sorting the documents
                    // first means this is reached in a fixed order however the files were read.
                    (updatedAt(record) == updatedAt(existing.first) &&
                        document.deviceId > existing.second)
                ) {
                    best[key] = record to document.deviceId
                }
            }
        }
        return best.values.map { it.first }.sortedBy { id(it) }
    }

    private fun newestSettings(documents: List<SyncDocument>): SyncSettings? {
        var best: SyncSettings? = null
        var bestDevice = ""
        for (document in documents.sortedBy { it.deviceId }) {
            val candidate = document.settings ?: continue
            val current = best
            if (current == null ||
                candidate.updatedAtUtcMillis > current.updatedAtUtcMillis ||
                (candidate.updatedAtUtcMillis == current.updatedAtUtcMillis &&
                    document.deviceId > bestDevice)
            ) {
                best = candidate
                bestDevice = document.deviceId
            }
        }
        return best
    }
}
