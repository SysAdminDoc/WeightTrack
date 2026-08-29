package com.weighttrack.data.repo

import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.data.db.GoalDao
import com.weighttrack.data.db.toDomain
import com.weighttrack.data.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalRepository @Inject constructor(
    private val dao: GoalDao,
) {
    fun observeActive(): Flow<Goal?> = dao.observeActive().map { it?.toDomain() }

    fun observeAll(): Flow<List<Goal>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun active(): Goal? = dao.active()?.toDomain()

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
        Goal(
            direction = direction,
            startGrams = startGrams,
            targetGrams = targetGrams,
            startDate = startDate,
            targetDate = targetDate,
            milestoneStepGrams = milestoneStepGrams,
            active = true,
        ).toEntity(),
    )

    suspend fun update(goal: Goal) = dao.update(goal.toEntity())

    suspend fun clearActive() = dao.deactivateAll()

    suspend fun delete(goal: Goal) = dao.delete(goal.toEntity())

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
