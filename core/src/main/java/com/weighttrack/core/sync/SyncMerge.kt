package com.weighttrack.core.sync

/**
 * Puts several devices' files together into one answer.
 *
 * The rule is the same for every kind of record: the most recently touched version wins, and a
 * deletion counts as a touch. Nothing here knows about Android or about the database, so the
 * awkward cases can be written down as tests rather than found on a phone.
 *
 * Every device runs this over the same set of files and has to reach the same answer, or they
 * will hand edits back and forth forever. Two things make that hold. Edits are ordered by a
 * [SyncStamp], which pairs the time with the device that made the edit rather than with the
 * device whose file it arrived in, so a record relayed by a third phone sorts the same wherever
 * it is read. And the stamp itself comes from a [HybridClock], so a phone with a wrong clock
 * cannot hold a stale edit in place forever.
 */
object SyncMerge {

    /**
     * The shortest a deletion is remembered, whatever anybody says.
     *
     * A floor, not a lifetime. Even when every device has confirmed it has seen a deletion, the
     * tombstone stays this long, because "every device" only means the ones that are known: a
     * phone set up from a backup and not yet synced is holding rows nobody has heard of.
     */
    const val TOMBSTONE_RETENTION_FLOOR_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * Whether a retired device can still be brought back without losing what was deleted.
     *
     * Retiring a device takes it out of the set the tombstone rule waits for, which is the whole
     * point: the others can stop holding a deletion open for a phone that was sold. The cost is
     * only visible later. Once the deletions that device never saw have passed the floor and been
     * forgotten, nothing is left to contradict the rows it still holds, so switching it back on
     * hands every one of them back and [merge] puts them on every device for good.
     *
     * Inside the floor there is no such cost, because the tombstones are all still here. So this
     * is the line between a decision that can be undone and one that cannot, and it is why the
     * settings screen offers to set the device up again rather than to bring it back.
     */
    fun canReturn(retiredAtUtcMillis: Long, nowUtcMillis: Long): Boolean =
        retiredAtUtcMillis <= 0 ||
            nowUtcMillis - retiredAtUtcMillis <= TOMBSTONE_RETENTION_FLOOR_MILLIS

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
        val attributed = documents.map { it.attributed() }
        val peers = mergePeers(attributed, deviceId, now)
        val observed = mergeObservations(attributed, deviceId)
        val deletions = mergeDeletions(attributed, peers, deviceId, observed, now)
        fun gone(kind: SyncKind, syncId: String, profile: String, updatedAt: Long): Boolean {
            // A tombstone naming a profile applies to that profile only. One naming none applies
            // wherever the name is found, which is what a profile's own tombstone means and what
            // a file written before deletions carried a profile says.
            val deletedAt = deletions[Key(kind, syncId, profile)]?.deletedAtUtcMillis
                ?: deletions[Key(kind, syncId, "")]?.deletedAtUtcMillis
                ?: return false
            // An edit made after the delete brings the record back, which is what somebody who
            // deleted a row on one phone and then corrected it on another actually meant.
            return deletedAt >= updatedAt
        }

        return SyncDocument(
            deviceId = deviceId,
            writtenAtUtcMillis = now,
            // Profiles are named on their own; everything else is named within a profile, so
            // two people who happen to hold rows with the same name keep their own.
            profiles = attributed.newest({ it.profiles }, { it.syncId }, { it.stamp() })
                .filterNot { gone(SyncKind.PROFILE, it.syncId, "", it.updatedAtUtcMillis) },
            weights = attributed
                .newest({ it.weights }, { owned(it.profileSyncId, it.syncId) }, { it.stamp() })
                .filterNot { gone(SyncKind.WEIGHT, it.syncId, it.profileSyncId, it.updatedAtUtcMillis) },
            measurements = attributed
                .newest({ it.measurements }, { owned(it.profileSyncId, it.syncId) }, { it.stamp() })
                .filterNot { gone(SyncKind.MEASUREMENT, it.syncId, it.profileSyncId, it.updatedAtUtcMillis) },
            water = attributed
                .newest({ it.water }, { owned(it.profileSyncId, it.syncId) }, { it.stamp() })
                .filterNot { gone(SyncKind.WATER, it.syncId, it.profileSyncId, it.updatedAtUtcMillis) },
            fasts = attributed
                .newest({ it.fasts }, { owned(it.profileSyncId, it.syncId) }, { it.stamp() })
                .filterNot { gone(SyncKind.FAST, it.syncId, it.profileSyncId, it.updatedAtUtcMillis) },
            goals = attributed
                .newest({ it.goals }, { owned(it.profileSyncId, it.syncId) }, { it.stamp() })
                .filterNot { gone(SyncKind.GOAL, it.syncId, it.profileSyncId, it.updatedAtUtcMillis) },
            macroTargets = attributed
                .newest({ it.macroTargets }, { owned(it.profileSyncId, it.syncId) }, { it.stamp() })
                .filterNot { gone(SyncKind.MACRO_TARGET, it.syncId, it.profileSyncId, it.updatedAtUtcMillis) },
            // Foods and recipes belong to nobody in particular, so they are named on their own.
            foods = attributed.newest({ it.foods }, { it.syncId }, { it.stamp() })
                .filterNot { gone(SyncKind.FOOD, it.syncId, "", it.updatedAtUtcMillis) },
            recipes = attributed.newest({ it.recipes }, { it.syncId }, { it.stamp() })
                .filterNot { gone(SyncKind.RECIPE, it.syncId, "", it.updatedAtUtcMillis) },
            recipeItems = attributed
                .newest({ it.recipeItems }, { it.syncId }, { it.stamp() })
                .filterNot { gone(SyncKind.RECIPE_ITEM, it.syncId, "", it.updatedAtUtcMillis) },
            foodLog = attributed
                .newest({ it.foodLog }, { owned(it.profileSyncId, it.syncId) }, { it.stamp() })
                .filterNot { gone(SyncKind.FOOD_LOG, it.syncId, it.profileSyncId, it.updatedAtUtcMillis) },
            medicationDoses = attributed
                .newest({ it.medicationDoses }, { owned(it.profileSyncId, it.syncId) }, { it.stamp() })
                .filterNot { gone(SyncKind.MEDICATION_DOSE, it.syncId, it.profileSyncId, it.updatedAtUtcMillis) },
            sideEffects = attributed
                .newest({ it.sideEffects }, { owned(it.profileSyncId, it.syncId) }, { it.stamp() })
                .filterNot { gone(SyncKind.SIDE_EFFECT, it.syncId, it.profileSyncId, it.updatedAtUtcMillis) },
            settings = newestSettings(attributed),
            deletions = deletions.map { (key, kept) ->
                SyncDeletion(
                    kind = key.kind,
                    syncId = key.syncId,
                    deletedAtUtcMillis = kept.deletedAtUtcMillis,
                    profileSyncId = key.profileSyncId,
                    stampDeviceId = kept.stampDeviceId,
                )
            }.sortedWith(compareBy({ it.kind }, { it.profileSyncId }, { it.syncId })),
            peers = peers,
            observed = observed.map { SyncObservation(it.key, it.value) }.sortedBy { it.deviceId },
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
            foodLog = merged.foodLog.filterNot { it.profileSyncId in profiles },
            measurements = merged.measurements.filterNot { it.profileSyncId in profiles },
            water = merged.water.filterNot { it.profileSyncId in profiles },
            fasts = merged.fasts.filterNot { it.profileSyncId in profiles },
            goals = merged.goals.filterNot { it.profileSyncId in profiles },
            macroTargets = merged.macroTargets.filterNot { it.profileSyncId in profiles },
            medicationDoses = merged.medicationDoses.filterNot { it.profileSyncId in profiles },
            sideEffects = merged.sideEffects.filterNot { it.profileSyncId in profiles },
        )
    }

    data class Orphans(
        val weights: List<SyncWeight>,
        val foodLog: List<SyncFoodLogEntry>,
        val measurements: List<SyncMeasurement>,
        val water: List<SyncWater>,
        val fasts: List<SyncFast>,
        val goals: List<SyncGoal>,
        val macroTargets: List<SyncMacroTarget>,
        val medicationDoses: List<SyncMedicationDose>,
        val sideEffects: List<SyncSideEffect>,
    ) {
        val isEmpty: Boolean
            get() = weights.isEmpty() && measurements.isEmpty() && water.isEmpty() &&
                fasts.isEmpty() && goals.isEmpty() && macroTargets.isEmpty() && foodLog.isEmpty() &&
                medicationDoses.isEmpty() && sideEffects.isEmpty()

        val count: Int
            get() = weights.size + measurements.size + water.size + fasts.size + goals.size +
                macroTargets.size + foodLog.size + medicationDoses.size + sideEffects.size
    }

    /** What names a deleted record: its kind, its own name, and whose it was. */
    private data class Key(val kind: SyncKind, val syncId: String, val profileSyncId: String)

    /** A tombstone as it stands after every file has had its say. */
    private data class Tombstone(val deletedAtUtcMillis: Long, val stampDeviceId: String)

    /** A record's identity, which is the profile it belongs to and its own name. */
    private fun owned(profileSyncId: String, syncId: String): String = "$profileSyncId/$syncId"

    /**
     * Every device anybody has heard of.
     *
     * A device is known once any file mentions it, so a phone that has never met another one
     * directly still waits for it before forgetting a deletion. Retirement is carried across the
     * same way and is deliberately one way: it is somebody saying a phone is gone, and a merge
     * has no business overruling that because a stale file is still sitting in the folder.
     */
    private fun mergePeers(
        documents: List<SyncDocument>,
        deviceId: String,
        now: Long,
    ): List<SyncPeer> {
        val known = LinkedHashMap<String, SyncPeer>()
        fun note(peer: SyncPeer) {
            if (peer.deviceId.isBlank()) return
            val current = known[peer.deviceId]
            known[peer.deviceId] = if (current == null) {
                peer
            } else {
                // The most recent decision about retirement wins, so bringing a device back is
                // possible; when two decisions carry the same moment, retired wins, because
                // waiting for a device that is not coming back only costs a file some room and
                // not waiting for one that is costs somebody a reading they deleted.
                val settled = when {
                    peer.decidedAtUtcMillis > current.decidedAtUtcMillis -> peer
                    current.decidedAtUtcMillis > peer.decidedAtUtcMillis -> current
                    current.isRetired -> current
                    else -> peer
                }
                SyncPeer(
                    deviceId = peer.deviceId,
                    lastSeenAtUtcMillis = maxOf(current.lastSeenAtUtcMillis, peer.lastSeenAtUtcMillis),
                    retiredAtUtcMillis = settled.retiredAtUtcMillis,
                    retirementDecidedAtUtcMillis = settled.decidedAtUtcMillis,
                )
            }
        }
        for (document in documents) {
            document.peers.forEach(::note)
            // A file that is here was written by a device that exists, whatever anybody's list
            // says, and reading it now is the most recent thing known about it.
            note(SyncPeer(deviceId = document.deviceId, lastSeenAtUtcMillis = now))
        }
        note(SyncPeer(deviceId = deviceId, lastSeenAtUtcMillis = now))
        return known.values.sortedBy { it.deviceId }
    }

    /**
     * How far this device has caught up with each of the others.
     *
     * Worked out from the stamps actually present rather than from what anybody claims, plus
     * whatever this device's own file already said. Believing a peer's claim would be quicker
     * and is exactly the wrong risk to take: a device that overstates what it holds makes the
     * others forget a deletion it never saw, and the row it still has comes back.
     */
    private fun mergeObservations(
        documents: List<SyncDocument>,
        deviceId: String,
    ): Map<String, Long> {
        val through = HashMap<String, Long>()
        fun note(origin: String, millis: Long) {
            val current = through[origin]
            if (current == null || millis > current) through[origin] = millis
        }
        for (document in documents) {
            document.highestStampPerDevice().forEach { (origin, millis) -> note(origin, millis) }
        }
        // Only this device's own previous list. It is a record of what this device held before,
        // and a deletion pruned since then would otherwise quietly lower its own answer.
        documents.filter { it.deviceId == deviceId }
            .forEach { own -> own.observed.forEach { note(it.deviceId, it.throughMillis) } }
        return through
    }

    /**
     * Which deletions are still worth carrying.
     *
     * The old rule was a calendar: six months and the tombstone went, whether or not anybody had
     * seen it. That is safe only if every device syncs inside six months, and it fails in the
     * ordinary way that matters, because the phone in the drawer is exactly the one still
     * holding the row. It comes back, nobody has a tombstone left to tell it otherwise, and the
     * reading somebody deleted last winter is back on their chart.
     *
     * The rule here is evidence. A tombstone goes when it is older than the floor and every
     * device that is not retired has published a list saying it holds everything from the
     * deleting device up to at least that point. A device that has not been heard from has
     * published nothing, so nothing is forgotten while it is away.
     */
    private fun mergeDeletions(
        documents: List<SyncDocument>,
        peers: List<SyncPeer>,
        deviceId: String,
        observed: Map<String, Long>,
        now: Long,
    ): Map<Key, Tombstone> {
        val kept = mutableMapOf<Key, Tombstone>()
        for (document in documents) {
            for (deletion in document.deletions) {
                val key = Key(deletion.kind, deletion.syncId, deletion.profileSyncId)
                val existing = kept[key]
                // A tombstone from the future is somebody's clock being wrong and is kept rather
                // than discarded, which is the safe way round: it holds a delete in place
                // instead of undoing one.
                if (existing == null || deletion.deletedAtUtcMillis > existing.deletedAtUtcMillis) {
                    kept[key] = Tombstone(deletion.deletedAtUtcMillis, deletion.stampDeviceId)
                }
            }
        }
        val byDevice = documents.associateBy { it.deviceId }
        val waiting = peers.filterNot { it.isRetired }.map { it.deviceId }
        return kept.filterValues { tombstone ->
            if (now - tombstone.deletedAtUtcMillis <= TOMBSTONE_RETENTION_FLOOR_MILLIS) {
                return@filterValues true
            }
            val stillWaitingOn = waiting.any { peer ->
                // The device that did the deleting plainly knows about it.
                if (peer == tombstone.stampDeviceId) return@any false
                val through = if (peer == deviceId) {
                    // This device's own answer is what this merge just worked out. It holds
                    // everything in front of it by the time the result is written.
                    observed[tombstone.stampDeviceId] ?: 0L
                } else {
                    // No file from that device in this round means no evidence either way, and
                    // no evidence means the tombstone stays.
                    val theirs = byDevice[peer] ?: return@any true
                    theirs.observed.firstOrNull { it.deviceId == tombstone.stampDeviceId }
                        ?.throughMillis ?: 0L
                }
                through < tombstone.deletedAtUtcMillis
            }
            stillWaitingOn
        }
    }

    /**
     * The newest version of each record, across every file.
     *
     * Ordered by [SyncStamp]: the time first, then the device that made the edit. Two edits
     * carrying the same millisecond are decided by a name that travels with the record, so every
     * device reaches the same answer however the files were read and whoever relayed them.
     */
    private fun <T> List<SyncDocument>.newest(
        select: (SyncDocument) -> List<T>,
        id: (T) -> String,
        stamp: (T) -> SyncStamp,
    ): List<T> {
        val best = LinkedHashMap<String, T>()
        for (document in sortedBy { it.deviceId }) {
            for (record in select(document)) {
                val key = id(record)
                val existing = best[key]
                if (existing == null || stamp(record) > stamp(existing)) {
                    best[key] = record
                }
            }
        }
        return best.values.sortedBy { id(it) }
    }

    private fun newestSettings(documents: List<SyncDocument>): SyncSettings? {
        var best: SyncSettings? = null
        for (document in documents.sortedBy { it.deviceId }) {
            val candidate = document.settings ?: continue
            val current = best
            if (current == null || candidate.stamp() > current.stamp()) best = candidate
        }
        return best
    }
}
