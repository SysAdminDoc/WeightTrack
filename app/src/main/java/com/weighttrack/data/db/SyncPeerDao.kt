package com.weighttrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncPeerDao {

    @Query("SELECT * FROM sync_peers ORDER BY deviceId")
    suspend fun all(): List<SyncPeerEntity>

    @Query("SELECT * FROM sync_peers ORDER BY lastSeenAtUtcMillis DESC")
    fun observeAll(): Flow<List<SyncPeerEntity>>

    @Query("SELECT * FROM sync_peers WHERE deviceId = :deviceId")
    suspend fun byDeviceId(deviceId: String): SyncPeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(peers: List<SyncPeerEntity>)

    /**
     * Says a device is gone for good.
     *
     * The only thing that lets the others stop waiting for it before they forget a deletion. It
     * never touches that device's readings: everything it ever sent stays exactly where it is.
     */
    @Query(
        "UPDATE sync_peers SET retiredAtUtcMillis = :retiredAt, " +
            "retirementDecidedAtUtcMillis = :decidedAt WHERE deviceId = :deviceId",
    )
    suspend fun setRetired(deviceId: String, retiredAt: Long, decidedAt: Long)

    @Query("DELETE FROM sync_peers")
    suspend fun deleteAll()
}
