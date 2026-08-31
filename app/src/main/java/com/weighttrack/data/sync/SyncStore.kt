package com.weighttrack.data.sync

import androidx.room.withTransaction
import com.weighttrack.core.sync.SyncDeletion
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.core.sync.SyncFast
import com.weighttrack.core.sync.SyncFood
import com.weighttrack.core.sync.SyncFoodLogEntry
import com.weighttrack.core.sync.SyncRecipe
import com.weighttrack.core.sync.SyncRecipeItem
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
import com.weighttrack.data.db.FoodEntity
import com.weighttrack.data.db.FoodLogEntryEntity
import com.weighttrack.data.db.RecipeEntity
import com.weighttrack.data.db.RecipeItemEntity
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
    private val database: com.weighttrack.data.db.WeightTrackDatabase,
    private val dao: SyncDao,
    private val deletions: DeletionDao,
) {

    /** This device's own view of everything, ready to be written to a file. */
    suspend fun snapshot(deviceId: String, now: Long): SyncDocument = withContext(Dispatchers.IO) {
        val profiles = nameAnythingUnnamed()
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
            foods = dao.foods().map { it.toSync() },
            recipes = dao.recipes().map { it.toSync() },
            // An ingredient names its recipe and its food by their travelling names. One whose
            // recipe or food has gone is left out rather than written with a dangling reference.
            recipeItems = run {
                val recipeNames = dao.recipes().associate { it.id to it.syncId }
                val foodNames = dao.foods().associate { it.id to it.syncId }
                dao.recipeItems().mapNotNull { row ->
                    val recipe = recipeNames[row.recipeId] ?: return@mapNotNull null
                    val food = foodNames[row.foodId] ?: return@mapNotNull null
                    row.toSync(recipe, food)
                }
            },
            foodLog = run {
                val foodNames = dao.foods().associate { it.id to it.syncId }
                dao.foodLog().mapNotNull { row ->
                    nameOf[row.profileId]?.let { row.toSync(it, row.foodId?.let(foodNames::get)) }
                }
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
        // One commit for the whole document. It spans eleven tables and the ingredients and the
        // diary both point at rows written earlier in the same run, so a failure halfway through
        // used to leave a database that no single writer could have produced: a recipe with no
        // food under it, a day's eating attached to a profile that never arrived. A restore is
        // the moment somebody has the most to lose, so it either lands or it does not.
        database.withTransaction { applyEverything(merged, now) }
    }

    private suspend fun applyEverything(merged: SyncDocument, now: Long): SyncChanges {
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
        changes += applyFoods(merged)
        changes += applyRecipes(merged)
        // After the foods and the recipes, because an ingredient points at both.
        changes += applyRecipeItems(merged)
        changes += applyFoodLog(merged, profileIdOf)
        changes += applyDeletions(merged)

        // Everybody else's tombstones are kept as if they were this device's own, so a deletion
        // that arrived here goes on travelling to a third device that has not seen it yet.
        deletions.recordAll(
            merged.deletions.map {
                DeletionEntity(it.kind.name, it.syncId, it.deletedAtUtcMillis, it.profileSyncId)
            },
        )
        deletions.forgetBefore(now - SyncMerge.TOMBSTONE_LIFETIME_MILLIS)
        return changes
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
                        heightMm = remote.heightMm,
                        sex = remote.sex,
                        birthYear = remote.birthYear,
                        activityLevel = remote.activityLevel,
                        syncId = remote.syncId,
                        updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    ),
                )
                added++
            } else {
                val candidate = existing.copy(
                    name = remote.name,
                    reminderEnabled = remote.reminderEnabled,
                    reminderHour = remote.reminderHour,
                    reminderMinute = remote.reminderMinute,
                    reminderDays = remote.reminderDays,
                    // Blank means the other device is on a version that did not carry these, so
                    // what is here is kept rather than being wiped by an older phone's silence.
                    heightMm = remote.heightMm.takeIf { it > 0 } ?: existing.heightMm,
                    sex = remote.sex.ifBlank { existing.sex },
                    birthYear = remote.birthYear.takeIf { it > 0 } ?: existing.birthYear,
                    activityLevel = remote.activityLevel.ifBlank { existing.activityLevel },
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    // Whether this phone talks to Health Connect is a fact about this phone, so
                    // it is never taken from another one.
                )
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    dao.updateProfile(candidate)
                    updated++
                }
            }
        }
        return SyncChanges(added = added, updated = updated)
    }

    // ---- the rows that belong to a profile ----

    private suspend fun applyWeights(
        merged: SyncDocument,
        profileIdOf: Map<String, Long>,
    ): SyncChanges {
        // Keyed on the profile as well as the name. A weigh-in's name is only unique within a
        // profile: the same backup restored for two people, or the same file imported twice,
        // gives both of them rows called the same thing. Keyed on the name alone, one person's
        // correction lands on the other person's morning and a delete takes out both.
        val local = dao.weights().associateBy { it.profileId to it.clientRecordId }
        val fresh = mutableListOf<WeightEntryEntity>()
        val revised = mutableListOf<WeightEntryEntity>()
        for (remote in merged.weights) {
            val profileId = profileIdOf[remote.profileSyncId] ?: continue
            val existing = local[profileId to remote.syncId]
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
                    muscleMassGrams = remote.muscleMassGrams,
                    fatFreeMassGrams = remote.fatFreeMassGrams,
                    softLeanMassGrams = remote.softLeanMassGrams,
                    bodyWaterMassGrams = remote.bodyWaterMassGrams,
                    musclePercent = remote.musclePercent,
                    impedanceOhms = remote.impedanceOhms,
                    basalMetabolismKcal = remote.basalMetabolismKcal,
                    scaleBmi = remote.scaleBmi,
                    scaleHeightMm = remote.scaleHeightMm,
                    scaleUserId = remote.scaleUserId,
                    compositionDevice = remote.compositionDevice,
                    compositionProtocol = remote.compositionProtocol,
                    compositionQuality = remote.compositionQuality,
                    originPackage = remote.originPackage,
                    originDevice = remote.originDevice,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            } else {
                val candidate = existing.copy(
                    timestampUtcMillis = remote.timestampUtcMillis,
                    zoneOffsetSeconds = remote.zoneOffsetSeconds,
                    localDate = remote.localDate,
                    grams = remote.grams,
                    bodyFatPercent = remote.bodyFatPercent,
                    note = remote.note,
                    tags = remote.tags.joinToString(","),
                    source = remote.source,
                    muscleMassGrams = remote.muscleMassGrams,
                    fatFreeMassGrams = remote.fatFreeMassGrams,
                    softLeanMassGrams = remote.softLeanMassGrams,
                    bodyWaterMassGrams = remote.bodyWaterMassGrams,
                    musclePercent = remote.musclePercent,
                    impedanceOhms = remote.impedanceOhms,
                    basalMetabolismKcal = remote.basalMetabolismKcal,
                    scaleBmi = remote.scaleBmi,
                    scaleHeightMm = remote.scaleHeightMm,
                    scaleUserId = remote.scaleUserId,
                    compositionDevice = remote.compositionDevice,
                    compositionProtocol = remote.compositionProtocol,
                    compositionQuality = remote.compositionQuality,
                    originPackage = remote.originPackage,
                    originDevice = remote.originDevice,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
                // The merge has already decided which version wins, including when two carry the
                // same millisecond. Comparing what would be written against what is here keeps a
                // sync that changes nothing reporting nothing, which a plain "newer or equal"
                // would not: every record would be rewritten on every pass.
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    revised += candidate
                }
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
            } else {
                val candidate = existing.copy(
                    timestampUtcMillis = remote.timestampUtcMillis,
                    localDate = remote.localDate,
                    type = remote.type,
                    valueMm = remote.valueMm,
                    note = remote.note,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
                // The merge has already decided which version wins, including when two carry the
                // same millisecond. Comparing what would be written against what is here keeps a
                // sync that changes nothing reporting nothing, which a plain "newer or equal"
                // would not: every record would be rewritten on every pass.
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    revised += candidate
                }
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
            } else {
                val candidate = existing.copy(
                    timestampUtcMillis = remote.timestampUtcMillis,
                    localDate = remote.localDate,
                    millilitres = remote.millilitres,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
                // The merge has already decided which version wins, including when two carry the
                // same millisecond. Comparing what would be written against what is here keeps a
                // sync that changes nothing reporting nothing, which a plain "newer or equal"
                // would not: every record would be rewritten on every pass.
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    revised += candidate
                }
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
            } else {
                val candidate = existing.copy(
                    startUtcMillis = remote.startUtcMillis,
                    endUtcMillis = remote.endUtcMillis,
                    targetMinutes = remote.targetMinutes,
                    note = remote.note,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
                // The merge has already decided which version wins, including when two carry the
                // same millisecond. Comparing what would be written against what is here keeps a
                // sync that changes nothing reporting nothing, which a plain "newer or equal"
                // would not: every record would be rewritten on every pass.
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    revised += candidate
                }
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
                    bandGrams = remote.bandGrams,
                    active = remote.active,
                    createdAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            } else {
                val candidate = existing.copy(
                    direction = remote.direction,
                    startGrams = remote.startGrams,
                    targetGrams = remote.targetGrams,
                    startDate = remote.startDate,
                    targetDate = remote.targetDate,
                    milestoneStepGrams = remote.milestoneStepGrams,
                    bandGrams = remote.bandGrams,
                    active = remote.active,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
                // The merge has already decided which version wins, including when two carry the
                // same millisecond. Comparing what would be written against what is here keeps a
                // sync that changes nothing reporting nothing, which a plain "newer or equal"
                // would not: every record would be rewritten on every pass.
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    revised += candidate
                }
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
        // Kept up to date as the loop runs rather than read once at the top. Two devices that
        // each set a Monday target independently put two Monday rows in the merged document, and
        // a snapshot taken beforehand shows neither of them to a third device with no Monday row
        // yet: both would be inserted, and the second insert breaks the unique index and takes
        // the whole sync down.
        val byDay = dao.macroTargets().associateBy { it.profileId to it.dayOfWeek }.toMutableMap()
        val fresh = mutableListOf<MacroTargetEntity>()
        val revised = mutableListOf<MacroTargetEntity>()
        // One row per profile and day is all the table allows, so the merged list is reduced
        // to one before any of it is applied. Two devices that each set a Monday target put two
        // Monday rows in the document, and applying them one after the other means the second
        // tries to correct a row the first has not inserted yet.
        val perDay = merged.macroTargets
            .groupBy { it.profileSyncId to it.dayOfWeek }
            .values
            .map { candidates ->
                candidates.maxWith(compareBy({ it.updatedAtUtcMillis }, { it.syncId }))
            }
        for (remote in perDay) {
            val profileId = profileIdOf[remote.profileSyncId] ?: continue
            val existing = local[remote.syncId] ?: byDay[profileId to remote.dayOfWeek]
            if (existing == null) {
                val made = MacroTargetEntity(
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
                fresh += made
                // Claimed at once, so a second target for the same day later in the same document
                // replaces this one instead of colliding with it.
                byDay[profileId to remote.dayOfWeek] = made
            } else {
                val candidate = existing.copy(
                    dayOfWeek = remote.dayOfWeek,
                    kcal = remote.kcal,
                    proteinG = remote.proteinG,
                    carbsG = remote.carbsG,
                    fatG = remote.fatG,
                    basis = remote.basis,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                )
                // The merge has already decided which version wins, including when two carry the
                // same millisecond. Comparing what would be written against what is here keeps a
                // sync that changes nothing reporting nothing, which a plain "newer or equal"
                // would not: every record would be rewritten on every pass.
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    revised += candidate
                }
            }
        }
        if (fresh.isNotEmpty()) dao.insertMacroTargets(fresh)
        if (revised.isNotEmpty()) dao.updateMacroTargets(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    // ---- the food side, which belongs to the household rather than to one person ----

    private suspend fun applyFoods(merged: SyncDocument): SyncChanges {
        val local = dao.foods().associateBy { it.syncId }
        val fresh = mutableListOf<FoodEntity>()
        val revised = mutableListOf<FoodEntity>()
        for (remote in merged.foods) {
            val existing = local[remote.syncId]
            if (existing == null) {
                fresh += FoodEntity(
                    name = remote.name,
                    brand = remote.brand,
                    barcode = remote.barcode,
                    kcalPer100g = remote.kcalPer100g,
                    proteinPer100g = remote.proteinPer100g,
                    carbsPer100g = remote.carbsPer100g,
                    fatPer100g = remote.fatPer100g,
                    fibrePer100g = remote.fibrePer100g,
                    sugarPer100g = remote.sugarPer100g,
                    saltPer100g = remote.saltPer100g,
                    servingGrams = remote.servingGrams,
                    origin = remote.origin,
                    // Whether it is a favourite, and when it was last eaten, stay where they
                    // were: "recently used" is a fact about one person's phone.
                    favourite = false,
                    lastUsedAtUtcMillis = 0,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                )
            } else {
                val candidate = existing.copy(
                    name = remote.name,
                    brand = remote.brand,
                    barcode = remote.barcode,
                    kcalPer100g = remote.kcalPer100g,
                    proteinPer100g = remote.proteinPer100g,
                    carbsPer100g = remote.carbsPer100g,
                    fatPer100g = remote.fatPer100g,
                    fibrePer100g = remote.fibrePer100g,
                    sugarPer100g = remote.sugarPer100g,
                    saltPer100g = remote.saltPer100g,
                    servingGrams = remote.servingGrams,
                    origin = remote.origin,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    revised += candidate
                }
            }
        }
        if (fresh.isNotEmpty()) dao.insertFoods(fresh)
        if (revised.isNotEmpty()) dao.updateFoods(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    private suspend fun applyRecipes(merged: SyncDocument): SyncChanges {
        val local = dao.recipes().associateBy { it.syncId }
        val fresh = mutableListOf<RecipeEntity>()
        val revised = mutableListOf<RecipeEntity>()
        for (remote in merged.recipes) {
            val existing = local[remote.syncId]
            if (existing == null) {
                fresh += RecipeEntity(
                    name = remote.name,
                    servings = remote.servings,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                    syncId = remote.syncId,
                )
            } else {
                val candidate = existing.copy(
                    name = remote.name,
                    servings = remote.servings,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    revised += candidate
                }
            }
        }
        if (fresh.isNotEmpty()) dao.insertRecipes(fresh)
        if (revised.isNotEmpty()) dao.updateRecipes(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    private suspend fun applyRecipeItems(merged: SyncDocument): SyncChanges {
        val recipeIdOf = dao.recipes().associate { it.syncId to it.id }
        val foodIdOf = dao.foods().associate { it.syncId to it.id }
        val local = dao.recipeItems().associateBy { it.syncId }
        val fresh = mutableListOf<RecipeItemEntity>()
        val revised = mutableListOf<RecipeItemEntity>()
        for (remote in merged.recipeItems) {
            // An ingredient without its recipe or its food is not an ingredient. Skipped rather
            // than written pointing at nothing, which would show as a blank line in a recipe.
            val recipeId = recipeIdOf[remote.recipeSyncId] ?: continue
            val foodId = foodIdOf[remote.foodSyncId] ?: continue
            val existing = local[remote.syncId]
            if (existing == null) {
                fresh += RecipeItemEntity(
                    recipeId = recipeId,
                    foodId = foodId,
                    grams = remote.grams,
                    syncId = remote.syncId,
                )
            } else {
                val candidate = existing.copy(
                    recipeId = recipeId,
                    foodId = foodId,
                    grams = remote.grams,
                )
                if (candidate != existing) revised += candidate
            }
        }
        if (fresh.isNotEmpty()) dao.insertRecipeItems(fresh)
        if (revised.isNotEmpty()) dao.updateRecipeItems(revised)
        return SyncChanges(added = fresh.size, updated = revised.size)
    }

    private suspend fun applyFoodLog(
        merged: SyncDocument,
        profileIdOf: Map<String, Long>,
    ): SyncChanges {
        val foodIdOf = dao.foods().associate { it.syncId to it.id }
        val local = dao.foodLog().associateBy { it.profileId to it.syncId }
        val fresh = mutableListOf<FoodLogEntryEntity>()
        val revised = mutableListOf<FoodLogEntryEntity>()
        for (remote in merged.foodLog) {
            val profileId = profileIdOf[remote.profileSyncId] ?: continue
            // The food may not be here, and that is fine: the nutrition is on the row. A meal
            // whose food was deleted still counts towards the day it was eaten.
            val foodId = remote.foodSyncId?.let(foodIdOf::get)
            val existing = local[profileId to remote.syncId]
            if (existing == null) {
                fresh += FoodLogEntryEntity(
                    profileId = profileId,
                    localDate = remote.localDate,
                    meal = remote.meal,
                    foodId = foodId,
                    name = remote.name,
                    grams = remote.grams,
                    kcal = remote.kcal,
                    proteinG = remote.proteinG,
                    carbsG = remote.carbsG,
                    fatG = remote.fatG,
                    loggedAtUtcMillis = remote.loggedAtUtcMillis,
                    syncId = remote.syncId,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
            } else {
                val candidate = existing.copy(
                    localDate = remote.localDate,
                    meal = remote.meal,
                    foodId = foodId,
                    name = remote.name,
                    grams = remote.grams,
                    kcal = remote.kcal,
                    proteinG = remote.proteinG,
                    carbsG = remote.carbsG,
                    fatG = remote.fatG,
                    loggedAtUtcMillis = remote.loggedAtUtcMillis,
                    updatedAtUtcMillis = remote.updatedAtUtcMillis,
                )
                if (remote.updatedAtUtcMillis >= existing.updatedAtUtcMillis && candidate != existing) {
                    revised += candidate
                }
            }
        }
        if (fresh.isNotEmpty()) dao.insertFoodLog(fresh)
        if (revised.isNotEmpty()) dao.updateFoodLog(revised)
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
            SyncKind.FOOD to merged.foods.map { it.syncId }.toSet(),
            SyncKind.RECIPE to merged.recipes.map { it.syncId }.toSet(),
            SyncKind.RECIPE_ITEM to merged.recipeItems.map { it.syncId }.toSet(),
            SyncKind.FOOD_LOG to merged.foodLog.map { it.syncId }.toSet(),
        )
        // A tombstone naming a profile applies to that profile. One naming none applies
        // wherever the name is found, which is what a file written before deletions carried a
        // profile says, and what a profile's own deletion means.
        val profileIdOf = dao.profiles().associate { it.syncId to it.id }
        fun gone(kind: SyncKind): List<SyncDeletion> = merged.deletions
            .filter { it.kind == kind && it.syncId !in surviving.getValue(kind) }

        var removed = 0
        gone(SyncKind.WEIGHT).ifNotEmpty { rows ->
            // Per profile. A name identifies a weigh-in only within one, so a single unscoped
            // statement would delete one person's morning along with another's.
            for ((owner, id) in profileIdOf) {
                val names = rows.filter { it.profileSyncId.isBlank() || it.profileSyncId == owner }
                    .map { it.syncId }
                if (names.isNotEmpty()) dao.deleteWeights(id, names)
            }
            removed += rows.size
        }
        gone(SyncKind.MEASUREMENT).ifNotEmpty { removed += it.size; dao.deleteMeasurements(it.map { d -> d.syncId }) }
        gone(SyncKind.WATER).ifNotEmpty { removed += it.size; dao.deleteWater(it.map { d -> d.syncId }) }
        gone(SyncKind.FAST).ifNotEmpty { removed += it.size; dao.deleteFasts(it.map { d -> d.syncId }) }
        gone(SyncKind.GOAL).ifNotEmpty { removed += it.size; dao.deleteGoals(it.map { d -> d.syncId }) }
        gone(SyncKind.MACRO_TARGET).ifNotEmpty {
            removed += it.size
            dao.deleteMacroTargets(it.map { d -> d.syncId })
        }
        gone(SyncKind.FOOD).ifNotEmpty { removed += it.size; dao.deleteFoods(it.map { d -> d.syncId }) }
        gone(SyncKind.RECIPE).ifNotEmpty { removed += it.size; dao.deleteRecipes(it.map { d -> d.syncId }) }
        gone(SyncKind.RECIPE_ITEM).ifNotEmpty {
            removed += it.size
            dao.deleteRecipeItems(it.map { d -> d.syncId })
        }
        gone(SyncKind.FOOD_LOG).ifNotEmpty {
            removed += it.size
            dao.deleteFoodLog(it.map { d -> d.syncId })
        }

        val profilesGone = gone(SyncKind.PROFILE).map { it.syncId }
        if (profilesGone.isNotEmpty()) {
            val present = dao.profiles()
            val doomed = present.filter { it.syncId in profilesGone }
            // There has to be one left. An app with no profile has nowhere to put a reading, so
            // a delete that would empty the table is refused however many devices agree on it.
            //
            // Refusing quietly is not enough. Two people's phones, each deleting a different
            // profile, both end up refusing the other's delete and holding a different survivor
            // forever: no amount of syncing settles it, because both are tombstoned and neither
            // will bring the other back. So the survivor is brought back to life properly, with
            // a time later than the tombstone that buried it. Both devices then see a profile
            // that was edited after it was deleted, which the merge already knows how to handle,
            // and they agree again.
            val emptying = doomed.size >= present.size
            val allowed = if (emptying) doomed.drop(1) else doomed
            if (emptying) {
                doomed.firstOrNull()?.let { survivor ->
                    val buried = merged.deletions
                        .firstOrNull { it.kind == SyncKind.PROFILE && it.syncId == survivor.syncId }
                        ?.deletedAtUtcMillis ?: 0
                    dao.updateProfile(
                        survivor.copy(updatedAtUtcMillis = maxOf(buried, survivor.updatedAtUtcMillis) + 1),
                    )
                }
            }
            if (allowed.isNotEmpty()) {
                // Everything belonging to them goes too. Nothing cascades in this schema, and
                // rows left behind would sit there invisible and unreachable forever.
                val names = allowed.map { it.syncId }
                val ids = allowed.map { it.id }.toSet()
                for (owner in ids) {
                    // Per profile, because a weigh-in's name is only unique within one and an
                    // unscoped delete would take somebody else's rows out with these.
                    dao.deleteWeights(
                        owner,
                        dao.weights().filter { it.profileId == owner }.map { it.clientRecordId },
                    )
                }
                dao.deleteMeasurements(dao.measurements().filter { it.profileId in ids }.map { it.syncId })
                dao.deleteWater(dao.water().filter { it.profileId in ids }.map { it.syncId })
                dao.deleteFasts(dao.fasts().filter { it.profileId in ids }.map { it.syncId })
                dao.deleteGoals(dao.goals().filter { it.profileId in ids }.map { it.syncId })
                dao.deleteMacroTargets(
                    dao.macroTargets().filter { it.profileId in ids }.map { it.syncId },
                )
                dao.deleteFoodLog(dao.foodLog().filter { it.profileId in ids }.map { it.syncId })
                dao.deleteProfiles(names)
                removed += allowed.size
            }
        }
        return SyncChanges(removed = removed)
    }

    private inline fun <T> List<T>.ifNotEmpty(block: (List<T>) -> Unit) {
        if (isNotEmpty()) block(this)
    }

    // ---- mapping ----

    /**
     * Gives a name to any profile that somehow has none, and writes it back.
     *
     * The migration names every row, so this should never do anything. Naming one here without
     * storing it would be worse than leaving it: the profile would go out under a fresh random
     * name every single sync and pile up duplicates on every other device.
     */
    private suspend fun nameAnythingUnnamed(): List<ProfileEntity> {
        val profiles = dao.profiles()
        val unnamed = profiles.filter { it.syncId.isBlank() }
        if (unnamed.isEmpty()) return profiles
        unnamed.forEach { dao.updateProfile(it.copy(syncId = newSyncId())) }
        return dao.profiles()
    }

    private fun ProfileEntity.toSync() = SyncProfile(
        syncId = syncId,
        name = name,
        position = position,
        createdAtUtcMillis = createdAtUtcMillis,
        reminderEnabled = reminderEnabled,
        reminderHour = reminderHour,
        reminderMinute = reminderMinute,
        reminderDays = reminderDays,
        heightMm = heightMm,
        sex = sex,
        birthYear = birthYear,
        activityLevel = activityLevel,
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
        muscleMassGrams = muscleMassGrams,
        fatFreeMassGrams = fatFreeMassGrams,
        softLeanMassGrams = softLeanMassGrams,
        bodyWaterMassGrams = bodyWaterMassGrams,
        musclePercent = musclePercent,
        impedanceOhms = impedanceOhms,
        basalMetabolismKcal = basalMetabolismKcal,
        scaleBmi = scaleBmi,
        scaleHeightMm = scaleHeightMm,
        scaleUserId = scaleUserId,
        compositionDevice = compositionDevice,
        compositionProtocol = compositionProtocol,
        compositionQuality = compositionQuality,
        originPackage = originPackage,
        originDevice = originDevice,
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
        bandGrams = bandGrams,
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

    private fun FoodEntity.toSync() = SyncFood(
        syncId = syncId,
        name = name,
        brand = brand,
        barcode = barcode,
        kcalPer100g = kcalPer100g,
        proteinPer100g = proteinPer100g,
        carbsPer100g = carbsPer100g,
        fatPer100g = fatPer100g,
        fibrePer100g = fibrePer100g,
        sugarPer100g = sugarPer100g,
        saltPer100g = saltPer100g,
        servingGrams = servingGrams,
        origin = origin,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

    private fun RecipeEntity.toSync() = SyncRecipe(
        syncId = syncId,
        name = name,
        servings = servings,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

    private fun RecipeItemEntity.toSync(recipeSyncId: String, foodSyncId: String) = SyncRecipeItem(
        syncId = syncId,
        recipeSyncId = recipeSyncId,
        foodSyncId = foodSyncId,
        grams = grams,
        // An ingredient has no time of its own; the recipe it belongs to carries that.
        updatedAtUtcMillis = 0,
    )

    private fun FoodLogEntryEntity.toSync(profileSyncId: String, foodSyncId: String?) =
        SyncFoodLogEntry(
            syncId = syncId,
            profileSyncId = profileSyncId,
            localDate = localDate,
            meal = meal,
            foodSyncId = foodSyncId,
            name = name,
            grams = grams,
            kcal = kcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            loggedAtUtcMillis = loggedAtUtcMillis,
            updatedAtUtcMillis = updatedAtUtcMillis,
        )

    private fun DeletionEntity.toSync(): SyncDeletion? {
        val known = runCatching { SyncKind.valueOf(kind) }.getOrNull() ?: return null
        return SyncDeletion(
            kind = known,
            syncId = syncId,
            deletedAtUtcMillis = deletedAtUtcMillis,
            profileSyncId = profileSyncId,
        )
    }
}
