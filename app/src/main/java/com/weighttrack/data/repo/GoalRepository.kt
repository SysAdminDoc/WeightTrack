package com.weighttrack.data.repo

import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.data.db.GoalDao
import com.weighttrack.data.db.toDomain
import com.weighttrack.data.db.toEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Scoped to the active profile, which it asks for rather than being told. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GoalRepository @Inject constructor(
    private val dao: GoalDao,
    private val profiles: ProfileRepository,
) {
    private fun <T> scoped(query: (Long) -> Flow<T>): Flow<T> =
        profiles.activeProfileId.flatMapLatest(query)

    fun observeActive(): Flow<Goal?> = scoped { dao.observeActive(it) }.map { it?.toDomain() }

    fun observeAll(): Flow<List<Goal>> =
        scoped { dao.observeAll(it) }.map { rows -> rows.map { it.toDomain() } }

    suspend fun active(): Goal? = dao.active(profiles.activeId())?.toDomain()

    /**
     * Sets the goal, retiring any previous one.
     *
     * The direction is derived from the numbers rather than trusted from the caller, so a
     * target above the current weight always behaves as a gain goal even if the UI passed
     * something else.
     */
    suspend fun setGoal(
        startGrams: Int,
        targetGrams: Int,
        milestoneStepGrams: Int,
        startDate: LocalDate = LocalDate.now(),
        targetDate: LocalDate? = null,
        direction: GoalDirection = directionFor(startGrams, targetGrams),
    ): Long = dao.replaceActive(
        profileId = profiles.activeId(),
        goal = Goal(
            direction = direction,
            startGrams = startGrams,
            targetGrams = targetGrams,
            startDate = startDate,
            targetDate = targetDate,
            milestoneStepGrams = milestoneStepGrams,
            active = true,
        ).toEntity(profileId = profiles.activeId()),
    )

    suspend fun update(goal: Goal) = dao.update(goal.toEntity(profileId = profileOf(goal.id)))

    suspend fun clearActive() = dao.deactivateAll(profiles.activeId())

    suspend fun delete(goal: Goal) = dao.delete(goal.toEntity(profileId = profileOf(goal.id)))

    /**
     * A goal only ever reaches this screen from the active profile, so that is where it stays.
     * There is no lookup by identifier on this table to read it back from.
     */
    private suspend fun profileOf(id: Long): Long = profiles.activeId()

    suspend fun deleteAll() = dao.deleteAll()

    companion object {
        /** Equal start and target is a maintain goal, not a zero-length loss. */
        fun directionFor(startGrams: Int, targetGrams: Int): GoalDirection = when {
            targetGrams < startGrams -> GoalDirection.LOSE
            targetGrams > startGrams -> GoalDirection.GAIN
            else -> GoalDirection.MAINTAIN
        }
    }
}
