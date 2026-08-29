package com.weighttrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeletionDao {

    /**
     * Records that a row is gone.
     *
     * Replaces on conflict, so deleting something that was already deleted moves the time
     * forward rather than being ignored. That matters when a row comes back from another device
     * and is deleted again: the older tombstone would lose to the record's own newer timestamp.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(deletion: DeletionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordAll(deletions: List<DeletionEntity>)

    @Query("SELECT * FROM deletions WHERE deletedAtUtcMillis >= :since")
    suspend fun since(since: Long): List<DeletionEntity>

    @Query("SELECT * FROM deletions")
    suspend fun all(): List<DeletionEntity>

    /** Forgets tombstones old enough that no device can still be holding the row. */
    @Query("DELETE FROM deletions WHERE deletedAtUtcMillis < :before")
    suspend fun forgetBefore(before: Long)

    @Query("DELETE FROM deletions WHERE kind = :kind AND syncId IN (:syncIds)")
    suspend fun forget(kind: String, syncIds: List<String>)

    @Query("DELETE FROM deletions")
    suspend fun deleteAll()
}
