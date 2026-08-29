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
) {
    fun observe(): Flow<MacroTargets> =
        profiles.activeProfileId
            .flatMapLatest { dao.observeAll(it) }
            .map { rows -> rows.toTargets() }

    suspend fun current(): MacroTargets = dao.all(profiles.activeId()).toTargets()

    /** Sets the target for one day, or for every day without one when [day] is null. */
    suspend fun set(target: MacroTarget, day: DayOfWeek? = null) {
        dao.upsert(
            MacroTargetEntity(
                profileId = profiles.activeId(),
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
    suspend fun clear(day: DayOfWeek?) = dao.clear(profiles.activeId(), day?.name)

    suspend fun clearAll() = dao.clearAll(profiles.activeId())

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
