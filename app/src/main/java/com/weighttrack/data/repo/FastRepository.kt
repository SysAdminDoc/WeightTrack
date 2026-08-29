package com.weighttrack.data.repo

import com.weighttrack.core.model.Fast
import com.weighttrack.data.db.FastDao
import com.weighttrack.data.db.FastEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Why a fast edit did or did not save. */
enum class FastUpdateResult { SAVED, BACKWARDS, MISSING }

@Singleton
class FastRepository @Inject constructor(
    private val dao: FastDao,
) {
    fun observeActive(): Flow<Fast?> = dao.observeActive().map { it?.toDomain() }

    fun observeCompleted(): Flow<List<Fast>> =
        dao.observeCompleted().map { rows -> rows.map { it.toDomain() } }

    fun observeCompletedCount(): Flow<Int> = dao.observeCompletedCount()

    suspend fun active(): Fast? = dao.active()?.toDomain()

    /** Starts a fast. Returns null when one is already running, so a double tap is a no-op. */
    suspend fun start(targetMinutes: Int, at: Instant = Instant.now()): Long? =
        dao.startFast(at.toEpochMilli(), targetMinutes)

    /** Ends the running fast. Does nothing when none is running. */
    suspend fun stop(at: Instant = Instant.now()): Boolean {
        val open = dao.active() ?: return false
        // A stop time before the start would store a negative fast, so it is clamped rather
        // than refused: the fast happened, the clock is what is wrong.
        val end = maxOf(at.toEpochMilli(), open.startUtcMillis)
        dao.update(open.copy(endUtcMillis = end, updatedAtUtcMillis = System.currentTimeMillis()))
        return true
    }

    /** Abandons the running fast without recording it, for a start tapped by mistake. */
    suspend fun cancelActive(): Boolean {
        val open = dao.active() ?: return false
        dao.delete(open)
        return true
    }

    /**
     * Saves a corrected fast.
     *
     * Both refusals are reported rather than swallowed: the caller shows the reason, because a
     * dialog that closes and changes nothing looks like it saved.
     */
    suspend fun update(fast: Fast): FastUpdateResult {
        if (fast.end != null && fast.end.isBefore(fast.start)) return FastUpdateResult.BACKWARDS
        val existing = dao.byId(fast.id) ?: return FastUpdateResult.MISSING
        dao.update(
            existing.copy(
                startUtcMillis = fast.start.toEpochMilli(),
                endUtcMillis = fast.end?.toEpochMilli(),
                targetMinutes = fast.targetMinutes,
                note = fast.note?.takeIf { it.isNotBlank() },
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
        return FastUpdateResult.SAVED
    }

    suspend fun delete(fast: Fast) {
        dao.byId(fast.id)?.let { dao.delete(it) }
    }

    suspend fun deleteAll() = dao.deleteAll()

    private fun FastEntity.toDomain(): Fast = Fast(
        id = id,
        start = Instant.ofEpochMilli(startUtcMillis),
        end = endUtcMillis?.let { Instant.ofEpochMilli(it) },
        targetMinutes = targetMinutes,
        note = note,
    )
}
