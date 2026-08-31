package com.weighttrack.data.repo

import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.data.db.GoalDao
import com.weighttrack.data.db.toDomain
import com.weighttrack.core.sync.SyncKind
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
    private val deletions: DeletionRecorder,
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
        /** For the restore. See [WeightRepository.upsertAll]. */
        owner: Long? = null,
    ): Long {
        val profileId = owner ?: profiles.activeId()
        return dao.replaceActive(
            profileId = profileId,
            goal = Goal(
                direction = direction,
                startGrams = startGrams,
                targetGrams = targetGrams,
                startDate = startDate,
                targetDate = targetDate,
                milestoneStepGrams = milestoneStepGrams,
                active = true,
            ).toEntity(profileId = profileId),
        )
    }

    suspend fun update(goal: Goal) {
        val existing = dao.byId(goal.id) ?: return
        dao.update(
            goal.toEntity(
                profileId = existing.profileId,
                createdAtUtcMillis = existing.createdAtUtcMillis,
                syncId = existing.syncId,
            ),
        )
    }

    suspend fun clearActive() =
        dao.deactivateAll(profiles.activeId(), System.currentTimeMillis())

    suspend fun delete(goal: Goal) {
        val existing = dao.byId(goal.id) ?: return
        deletions.asOne {
            dao.delete(existing)
            deletions.record(SyncKind.GOAL, existing.syncId, profileId = existing.profileId)
        }
    }

    /** Read back off the stored row, so an edit cannot move a goal to another profile. */
    private suspend fun profileOf(id: Long): Long =
        dao.byId(id)?.profileId ?: profiles.activeId()

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
