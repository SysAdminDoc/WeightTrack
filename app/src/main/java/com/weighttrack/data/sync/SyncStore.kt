package com.weighttrack.data.sync

import com.weighttrack.core.sync.SyncDeletion
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.core.sync.SyncFast
import com.weighttrack.core.sync.SyncGoal
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.core.sync.SyncMacroTarget
import com.weighttrack.core.sync.SyncMeasurement
import com.weighttrack.core.sync.SyncMerge
import com.weighttrack.core.sync.SyncProfile
import com.weighttrack.core.sync.SyncWater
import com.weighttrack.core.sync.SyncWeight
import com.weighttrack.data.db.DeletionDao
import com.weighttrack.data.db.DeletionEntity
import com.weighttrack.data.db.FastEntity
import com.weighttrack.data.db.GoalEntity
import com.weighttrack.data.db.MacroTargetEntity
import com.weighttrack.data.db.MeasurementEntity
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.SyncDao
import com.weighttrack.data.db.WaterEntryEntity
import com.weighttrack.data.db.WeightEntryEntity
import com.weighttrack.data.db.newSyncId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What one sync actually did, in numbers a person can be shown. */
data class SyncChanges(
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    /**
     * Records belonging to a profile that no longer exists anywhere.
     *
     * Counted rather than quietly dropped. It should be zero, and if it is not, somebody should
     * be told rather than left to notice missing readings months later.
     */
    val orphaned: Int = 0,
) {
    val touched: Int get() = added + updated + removed

    operator fun plus(other: SyncChanges) = SyncChanges(
        added = added + other.added,
        updated = updated + other.updated,
        removed = removed + other.removed,
        orphaned = orphaned + other.orphaned,
    )
}

/**
 * Turns the database into a sync document and back again.
 *
 * All of it, across every profile. Everything else in the app is scoped to whoever is active,
 * which is right for a screen and wrong here: a household of two would otherwise find that only
 * whichever profile happened to be open ever reached the other phone.
 */
@Singleton
class SyncStore @Inject constructor(
    private val dao: SyncDao,
    private val deletions: DeletionDao,
) {

    /** This device's own view of everything, ready to be written to a file. */
    suspend fun snapshot(deviceId: String, now: Long): SyncDocument = withContext(Dispatchers.IO) {
        val profiles = dao.profiles()
        val nameOf = profiles.associate { it.id to it.syncId }
        SyncDocument(
            deviceId = deviceId,
            writtenAtUtcMillis = now,
            profiles = profiles.map { it.toSync() },
            // Rows whose profile has gone are left out rather than written with a dangling name.
            weights = dao.weights().mapNotNull { row ->
                nameOf[row.profileId]?.let { row.toSync(it) }
            },
            measurements = dao.measurements().mapNotNull { row ->
                nameOf[row.profileId]?.let { row.toSync(it) }
            },
            water = dao.water().mapNotNull { row -> nameOf[row.profileId]?.let { row.toSync(it) } },
            fasts = dao.fasts().mapNotNull { row -> nameOf[row.profileId]?.let { row.toSync(it) } },
            goals = dao.goals().mapNotNull { row -> nameOf[row.profileId]?.let { row.toSync(it) } },
            macroTargets = dao.macroTargets().mapNotNull { row ->
                nameOf[row.profileId]?.let { row.toSync(it) }
            },
            deletions = deletions.all().mapNotNull { it.toSync() },
        )
    }

    /**
     * Writes a merged document back.
     *
     * Newest wins, decided per record on the time it was last touched. A record already here and
     * older than the one arriving is updated in place, keeping its own row identifier, so
     * anything on this phone pointing at that row still points at it.
     */
    suspend fun apply(merged: SyncDocument, now: Long): SyncChanges = withContext(Dispatchers.IO) {
        var changes = applyProfiles(merged)
        val profileIdOf = dao.profiles().associate { it.syncId to it.id }

        val orphans = SyncMerge.orphans(merged)
        changes = changes.copy(orphaned = orphans.count)

        changes += applyWeights(merged, profileIdOf)
        changes += applyMeasurements(merged, profileIdOf)
        changes += applyWater(merged, profileIdOf)
        changes += applyFasts(merged, profileIdOf)
        changes += applyGoals(merged, profileIdOf)
        changes += applyMacroTargets(merged, profileIdOf)
        changes += applyDeletions(merged)

        // Everybody else's tombstones are kept as if they were this device's own, so a deletion
        // that arrived here goes on travelling to a third device that has not seen it yet.
        deletions.recordAll(
            merged.deletions.map {
                DeletionEntity(it.kind.name, it.syncId, it.deletedAtUtcMillis)
            },
        )
        deletions.forgetBefore(now - SyncMerge.TOMBSTONE_LIFETIME_MILLIS)
        changes
    }

    // ---- profiles ----

    private suspend fun applyProfiles(merged: SyncDocument): SyncChanges {
        if (merged.profiles.isEmpty()) return SyncChanges()
        val local = dao.profiles().associateBy { it.syncId }
        var added = 0
        var updated = 0
        var position = local.values.maxOfOrNull { it.position } ?: -1
        for (remote in merged.profiles) {
            val existing = local[remote.syncId]
            if (existing == null) {
                position += 1
                dao.insertProfile(
                    ProfileEntity(
                        name = remote.name,
                        // Placed at the end rather than at the position it had on the other
                        // device. Two devices each inventing a position produce two profiles
                        // sitting on top of each other in the list.
                        position = position,
                        createdAtUtcMillis = remote.createdAtUtcMillis,
                        reminderEnabled = remote.reminderEnabled,
                        reminderHour = remote.reminderHour,
                        reminderMinute = remote.reminderMinute,
                        reminderDays = remote.reminderDays,
                        syncId = remote.syncId,
                        updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    ),
                )
                added++
            } else if (remote.updatedAtUtcMillis > existing.updatedAtUtcMillis) {
                dao.updateProfile(
                    existing.copy(
                        name = remote.name,
                        reminderEnabled = remote.reminderEnabled,
                        reminderHour = remote.reminderHour,
                        reminderMinute = remote.reminderMinute,
                        reminderDays = remote.reminderDays,
                        updatedAtUtcMillis = remote.updatedAtUtcMillis,
                        // Whether this phone talks to Health Connect is a fact about this phone,
                        // so it is never taken from another one.
                    ),
                )
                updated++
            }
        }
        return SyncChanges(added = added, updated = updated)
    }

    // ---- the rows that belong to a profile ----

    private suspend fun applyWeights(
        merged: SyncDocument,
        profileIdOf: Map<String, Long>,
    ): SyncChanges {
        val local = dao.weights().associateBy { it.clientRecordId }
        val fresh = mutableListOf<WeightEntryEntity>()
        val revised = mutableListOf<WeightEntryEntity>()
        for (remote in merged.weights) {
            val profileId = profileIdOf[remote.profileSyncId] ?: continue
            val existing = local[remote.syncId]
            if (existing == null) {
                fresh += WeightEntryEntity(
                    profileId = profileId,
                    timestampUtcMillis = remote.timestampUtcMillis,
                    zoneOffsetSeconds = remote.zoneOffsetSeconds,
                    localDate = remote.localDate,
                    grams = remote.grams,
                    bodyFatPercent = remote.bodyFatPercent,
                    note = remote.note,
                    tags = remote.tags.joinToString(","),
                    source = remote.source,
                    clientRecordId = remote.syncId,
                    // Never carried across. It names a record inside one phone's Health Connect
                    // and means nothing on another, and copying it would make the second phone
                    // think it had already written a weight it never wrote.
                    healthConnectId = null,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            } else if (remote.updatedAtUtcMillis > existing.updatedAtUtcMillis) {
                revised += existing.copy(
                    timestampUtcMillis = remote.timestampUtcMillis,
                    zoneOffsetSeconds = remote.zoneOffsetSeconds,
                    localDate = remote.localDate,
                    grams = remote.grams,
                    bodyFatPercent = remote.bodyFatPercent,
                    note = remote.note,
                    tags = remote.tags.joinToString(","),
                    source = remote.source,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            }
        }
        if (fresh.isNotEmpty()) dao.insertWeights(fresh)
        if (revised.isNotEmpty()) dao.updateWeights(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    private suspend fun applyMeasurements(
        merged: SyncDocument,
        profileIdOf: Map<String, Long>,
    ): SyncChanges {
        val local = dao.measurements().associateBy { it.syncId }
        val fresh = mutableListOf<MeasurementEntity>()
        val revised = mutableListOf<MeasurementEntity>()
        for (remote in merged.measurements) {
            val profileId = profileIdOf[remote.profileSyncId] ?: continue
            val existing = local[remote.syncId]
            if (existing == null) {
                fresh += MeasurementEntity(
                    profileId = profileId,
                    timestampUtcMillis = remote.timestampUtcMillis,
                    localDate = remote.localDate,
                    type = remote.type,
                    valueMm = remote.valueMm,
                    note = remote.note,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                )
            } else if (remote.updatedAtUtcMillis > existing.updatedAtUtcMillis) {
                revised += existing.copy(
                    timestampUtcMillis = remote.timestampUtcMillis,
                    localDate = remote.localDate,
                    type = remote.type,
                    valueMm = remote.valueMm,
                    note = remote.note,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            }
        }
        if (fresh.isNotEmpty()) dao.insertMeasurements(fresh)
        if (revised.isNotEmpty()) dao.updateMeasurements(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    private suspend fun applyWater(
        merged: SyncDocument,
        profileIdOf: Map<String, Long>,
    ): SyncChanges {
        val local = dao.water().associateBy { it.syncId }
        val fresh = mutableListOf<WaterEntryEntity>()
        val revised = mutableListOf<WaterEntryEntity>()
        for (remote in merged.water) {
            val profileId = profileIdOf[remote.profileSyncId] ?: continue
            val existing = local[remote.syncId]
            if (existing == null) {
                fresh += WaterEntryEntity(
                    profileId = profileId,
                    timestampUtcMillis = remote.timestampUtcMillis,
                    localDate = remote.localDate,
                    millilitres = remote.millilitres,
                    healthConnectId = null,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                )
            } else if (remote.updatedAtUtcMillis > existing.updatedAtUtcMillis) {
                revised += existing.copy(
                    timestampUtcMillis = remote.timestampUtcMillis,
                    localDate = remote.localDate,
                    millilitres = remote.millilitres,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            }
        }
        if (fresh.isNotEmpty()) dao.insertWater(fresh)
        if (revised.isNotEmpty()) dao.updateWater(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    private suspend fun applyFasts(
        merged: SyncDocument,
        profileIdOf: Map<String, Long>,
    ): SyncChanges {
        val local = dao.fasts().associateBy { it.syncId }
        val fresh = mutableListOf<FastEntity>()
        val revised = mutableListOf<FastEntity>()
        for (remote in merged.fasts) {
            val profileId = profileIdOf[remote.profileSyncId] ?: continue
            val existing = local[remote.syncId]
            if (existing == null) {
                fresh += FastEntity(
                    profileId = profileId,
                    startUtcMillis = remote.startUtcMillis,
                    endUtcMillis = remote.endUtcMillis,
                    targetMinutes = remote.targetMinutes,
                    note = remote.note,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                )
            } else if (remote.updatedAtUtcMillis > existing.updatedAtUtcMillis) {
                revised += existing.copy(
                    startUtcMillis = remote.startUtcMillis,
                    endUtcMillis = remote.endUtcMillis,
                    targetMinutes = remote.targetMinutes,
                    note = remote.note,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            }
        }
        if (fresh.isNotEmpty()) dao.insertFasts(fresh)
        if (revised.isNotEmpty()) dao.updateFasts(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    private suspend fun applyGoals(
        merged: SyncDocument,
        profileIdOf: Map<String, Long>,
    ): SyncChanges {
        val local = dao.goals().associateBy { it.syncId }
        val fresh = mutableListOf<GoalEntity>()
        val revised = mutableListOf<GoalEntity>()
        for (remote in merged.goals) {
            val profileId = profileIdOf[remote.profileSyncId] ?: continue
            val existing = local[remote.syncId]
            if (existing == null) {
                fresh += GoalEntity(
                    profileId = profileId,
                    direction = remote.direction,
                    startGrams = remote.startGrams,
                    targetGrams = remote.targetGrams,
                    startDate = remote.startDate,
                    targetDate = remote.targetDate,
                    milestoneStepGrams = remote.milestoneStepGrams,
                    active = remote.active,
                    createdAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            } else if (remote.updatedAtUtcMillis > existing.updatedAtUtcMillis) {
                revised += existing.copy(
                    direction = remote.direction,
                    startGrams = remote.startGrams,
                    targetGrams = remote.targetGrams,
                    startDate = remote.startDate,
                    targetDate = remote.targetDate,
                    milestoneStepGrams = remote.milestoneStepGrams,
                    active = remote.active,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            }
        }
        if (fresh.isNotEmpty()) dao.insertGoals(fresh)
        if (revised.isNotEmpty()) dao.updateGoals(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    private suspend fun applyMacroTargets(
        merged: SyncDocument,
        profileIdOf: Map<String, Long>,
    ): SyncChanges {
        val local = dao.macroTargets().associateBy { it.syncId }
        // The table allows one row per profile and day, so a target arriving under a different
        // name for a day that already has one has to replace it rather than be inserted beside
        // it. Inserting would break the unique index and take the whole sync down with it.
        val byDay = dao.macroTargets().associateBy { it.profileId to it.dayOfWeek }
        val fresh = mutableListOf<MacroTargetEntity>()
        val revised = mutableListOf<MacroTargetEntity>()
        for (remote in merged.macroTargets) {
            val profileId = profileIdOf[remote.profileSyncId] ?: continue
            val existing = local[remote.syncId] ?: byDay[profileId to remote.dayOfWeek]
            if (existing == null) {
                fresh += MacroTargetEntity(
                    profileId = profileId,
                    dayOfWeek = remote.dayOfWeek,
                    kcal = remote.kcal,
                    proteinG = remote.proteinG,
                    carbsG = remote.carbsG,
                    fatG = remote.fatG,
                    basis = remote.basis,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                )
            } else if (remote.updatedAtUtcMillis > existing.updatedAtUtcMillis) {
                revised += existing.copy(
                    dayOfWeek = remote.dayOfWeek,
                    kcal = remote.kcal,
                    proteinG = remote.proteinG,
                    carbsG = remote.carbsG,
                    fatG = remote.fatG,
                    basis = remote.basis,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                )
            }
        }
        if (fresh.isNotEmpty()) dao.insertMacroTargets(fresh)
        if (revised.isNotEmpty()) dao.updateMacroTargets(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    // ---- deletions ----

    private suspend fun applyDeletions(merged: SyncDocument): SyncChanges {
        // Only the rows the merge decided are gone. A record edited after it was deleted
        // survives the merge, and deleting it here would undo that on every pass.
        val surviving = mapOf(
            SyncKind.PROFILE to merged.profiles.map { it.syncId }.toSet(),
            SyncKind.WEIGHT to merged.weights.map { it.syncId }.toSet(),
            SyncKind.MEASUREMENT to merged.measurements.map { it.syncId }.toSet(),
            SyncKind.WATER to merged.water.map { it.syncId }.toSet(),
            SyncKind.FAST to merged.fasts.map { it.syncId }.toSet(),
            SyncKind.GOAL to merged.goals.map { it.syncId }.toSet(),
            SyncKind.MACRO_TARGET to merged.macroTargets.map { it.syncId }.toSet(),
        )
        fun gone(kind: SyncKind): List<String> = merged.deletions
            .filter { it.kind == kind && it.syncId !in surviving.getValue(kind) }
            .map { it.syncId }

        var removed = 0
        gone(SyncKind.WEIGHT).ifNotEmpty { removed += it.size; dao.deleteWeights(it) }
        gone(SyncKind.MEASUREMENT).ifNotEmpty { removed += it.size; dao.deleteMeasurements(it) }
        gone(SyncKind.WATER).ifNotEmpty { removed += it.size; dao.deleteWater(it) }
        gone(SyncKind.FAST).ifNotEmpty { removed += it.size; dao.deleteFasts(it) }
        gone(SyncKind.GOAL).ifNotEmpty { removed += it.size; dao.deleteGoals(it) }
        gone(SyncKind.MACRO_TARGET).ifNotEmpty { removed += it.size; dao.deleteMacroTargets(it) }

        val profilesGone = gone(SyncKind.PROFILE)
        if (profilesGone.isNotEmpty()) {
            val present = dao.profiles()
            val doomed = present.filter { it.syncId in profilesGone }
            // There has to be one left. An app with no profile has nowhere to put a reading, so
            // a delete that would empty the table is refused however many devices agree on it.
            val allowed = if (doomed.size >= present.size) doomed.drop(1) else doomed
            if (allowed.isNotEmpty()) {
                // Everything belonging to them goes too. Nothing cascades in this schema, and
                // rows left behind would sit there invisible and unreachable forever.
                val names = allowed.map { it.syncId }
                val ids = allowed.map { it.id }.toSet()
                dao.deleteWeights(dao.weights().filter { it.profileId in ids }.map { it.clientRecordId })
                dao.deleteMeasurements(dao.measurements().filter { it.profileId in ids }.map { it.syncId })
                dao.deleteWater(dao.water().filter { it.profileId in ids }.map { it.syncId })
                dao.deleteFasts(dao.fasts().filter { it.profileId in ids }.map { it.syncId })
                dao.deleteGoals(dao.goals().filter { it.profileId in ids }.map { it.syncId })
                dao.deleteMacroTargets(
                    dao.macroTargets().filter { it.profileId in ids }.map { it.syncId },
                )
                dao.deleteProfiles(names)
                removed += allowed.size
            }
        }
        return SyncChanges(removed = removed)
    }

    private inline fun List<String>.ifNotEmpty(block: (List<String>) -> Unit) {
        if (isNotEmpty()) block(this)
    }

    // ---- mapping ----

    private fun ProfileEntity.toSync() = SyncProfile(
        syncId = syncId.ifBlank { newSyncId() },
        name = name,
        position = position,
        createdAtUtcMillis = createdAtUtcMillis,
        reminderEnabled = reminderEnabled,
        reminderHour = reminderHour,
        reminderMinute = reminderMinute,
        reminderDays = reminderDays,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

    private fun WeightEntryEntity.toSync(profileSyncId: String) = SyncWeight(
        // Weigh-ins already had a name that travels, because Health Connect needed one. Giving
        // them a second would mean the same reading arriving twice on an upgraded phone.
        syncId = clientRecordId,
        profileSyncId = profileSyncId,
        timestampUtcMillis = timestampUtcMillis,
        zoneOffsetSeconds = zoneOffsetSeconds,
        localDate = localDate,
        grams = grams,
        bodyFatPercent = bodyFatPercent,
        note = note,
        tags = tags.split(",").filter { it.isNotBlank() },
        source = source,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

    private fun MeasurementEntity.toSync(profileSyncId: String) = SyncMeasurement(
        syncId = syncId,
        profileSyncId = profileSyncId,
        timestampUtcMillis = timestampUtcMillis,
        localDate = localDate,
        type = type,
        valueMm = valueMm,
        note = note,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

    private fun WaterEntryEntity.toSync(profileSyncId: String) = SyncWater(
        syncId = syncId,
        profileSyncId = profileSyncId,
        timestampUtcMillis = timestampUtcMillis,
        localDate = localDate,
        millilitres = millilitres,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

    private fun FastEntity.toSync(profileSyncId: String) = SyncFast(
        syncId = syncId,
        profileSyncId = profileSyncId,
        startUtcMillis = startUtcMillis,
        endUtcMillis = endUtcMillis,
        targetMinutes = targetMinutes,
        note = note,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

    private fun GoalEntity.toSync(profileSyncId: String) = SyncGoal(
        syncId = syncId,
        profileSyncId = profileSyncId,
        direction = direction,
        startGrams = startGrams,
        targetGrams = targetGrams,
        startDate = startDate,
        targetDate = targetDate,
        milestoneStepGrams = milestoneStepGrams,
        active = active,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

    private fun MacroTargetEntity.toSync(profileSyncId: String) = SyncMacroTarget(
        syncId = syncId,
        profileSyncId = profileSyncId,
        dayOfWeek = dayOfWeek,
        kcal = kcal,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        basis = basis,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

    private fun DeletionEntity.toSync(): SyncDeletion? {
        val known = runCatching { SyncKind.valueOf(kind) }.getOrNull() ?: return null
        return SyncDeletion(kind = known, syncId = syncId, deletedAtUtcMillis = deletedAtUtcMillis)
    }
}
