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

    /**
     * Forgets tombstones, within one owner.
     *
     * Scoped, because a row's travelling name is only unique within a profile. Two people who
     * imported the same file both hold rows called the same thing, and an unscoped forget would
     * clear one person's tombstone while putting the other person's row back: their deletion
     * stops travelling and the row they deleted comes home from their other phone.
     */
    @Query(
        "DELETE FROM deletions WHERE kind = :kind AND profileSyncId = :profileSyncId " +
            "AND syncId IN (:syncIds)",
    )
    suspend fun forget(kind: String, profileSyncId: String, syncIds: List<String>)

    @Query("DELETE FROM deletions")
    suspend fun deleteAll()
}
