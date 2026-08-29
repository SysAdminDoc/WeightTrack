package com.weighttrack.data.repo

import com.weighttrack.core.nutrition.MacroBasis
import com.weighttrack.core.nutrition.MacroTarget
import com.weighttrack.core.nutrition.MacroTargets
import com.weighttrack.data.db.MacroTargetDao
import com.weighttrack.data.db.MacroTargetEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What each person is aiming for, per day of the week.
 *
 * Targets belong to a person, so they live beside the rest of that profile's data and go with it
 * when the profile is deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MacroTargetRepository @Inject constructor(
    private val dao: MacroTargetDao,
    private val profiles: ProfileRepository,
    private val deletions: DeletionRecorder,
) {
    fun observe(): Flow<MacroTargets> =
        profiles.activeProfileId
            .flatMapLatest { dao.observeAll(it) }
            .map { rows -> rows.toTargets() }

    suspend fun current(): MacroTargets = dao.all(profiles.activeId()).toTargets()

    /** Sets the target for one day, or for every day without one when [day] is null. */
    suspend fun set(target: MacroTarget, day: DayOfWeek? = null) {
        val profileId = profiles.activeId()
        // The upsert replaces whatever row is already under this profile and day, so it has to
        // carry that row's name forward. A fresh one each time would make every change look like
        // a brand new target to the person's other devices, and the old one would never go away.
        val existing = dao.forDay(profileId, day?.name)
        dao.upsert(
            MacroTargetEntity(
                syncId = existing?.syncId ?: com.weighttrack.data.db.newSyncId(),
                profileId = profileId,
                dayOfWeek = day?.name,
                kcal = target.kcal,
                proteinG = target.proteinG,
                carbsG = target.carbsG,
                fatG = target.fatG,
                basis = target.basis.name,
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    /** Removes a day's own target, which puts it back on the everyday one. */
    suspend fun clear(day: DayOfWeek?) {
        val profileId = profiles.activeId()
        dao.forDay(profileId, day?.name)?.let {
            deletions.record(
                com.weighttrack.core.sync.SyncKind.MACRO_TARGET,
                it.syncId,
                profileId = profileId,
            )
        }
        dao.clear(profileId, day?.name)
    }

    suspend fun clearAll() {
        val profileId = profiles.activeId()
        deletions.record(
            com.weighttrack.core.sync.SyncKind.MACRO_TARGET,
            dao.all(profileId).map { it.syncId },
            profileId = profileId,
        )
        dao.clearAll(profileId)
    }

    private fun List<MacroTargetEntity>.toTargets(): MacroTargets {
        val byDay = mutableMapOf<DayOfWeek, MacroTarget>()
        var default: MacroTarget? = null
        forEach { row ->
            val target = row.toDomain()
            val day = row.dayOfWeek?.let { name ->
                runCatching { DayOfWeek.valueOf(name) }.getOrNull()
            }
            if (day == null) default = target else byDay[day] = target
        }
        return MacroTargets(default = default, byDay = byDay)
    }

    private fun MacroTargetEntity.toDomain(): MacroTarget = MacroTarget(
        kcal = kcal,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        basis = runCatching { MacroBasis.valueOf(basis) }.getOrDefault(MacroBasis.GRAMS),
        day = dayOfWeek?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() },
    )
}
