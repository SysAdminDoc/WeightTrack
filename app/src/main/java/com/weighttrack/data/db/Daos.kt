package com.weighttrack.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One calendar day's mean weight, computed in SQL so the trend never loads the whole table. */
data class DailyWeightRow(
    val localDate: String,
    val grams: Double,
)

@Dao
interface WeightEntryDao {

    @Query("SELECT * FROM weight_entries ORDER BY timestampUtcMillis DESC")
    fun observeAll(): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries ORDER BY timestampUtcMillis ASC")
    fun observeAllAscending(): Flow<List<WeightEntryEntity>>

    @Query(
        """
        SELECT localDate, AVG(grams) AS grams
        FROM weight_entries
        GROUP BY localDate
        ORDER BY localDate ASC
        """,
    )
    fun observeDailyAverages(): Flow<List<DailyWeightRow>>

    @Query("SELECT * FROM weight_entries ORDER BY timestampUtcMillis DESC LIMIT 1")
    fun observeLatest(): Flow<WeightEntryEntity?>

    @Query("SELECT * FROM weight_entries ORDER BY timestampUtcMillis DESC LIMIT 1")
    suspend fun latest(): WeightEntryEntity?

    @Query("SELECT * FROM weight_entries ORDER BY timestampUtcMillis ASC LIMIT 1")
    suspend fun earliest(): WeightEntryEntity?

    @Query("SELECT * FROM weight_entries WHERE id = :id")
    suspend fun byId(id: Long): WeightEntryEntity?

    @Query("SELECT * FROM weight_entries WHERE clientRecordId = :clientRecordId")
    suspend fun byClientRecordId(clientRecordId: String): WeightEntryEntity?

    @Query("SELECT * FROM weight_entries WHERE healthConnectId = :healthConnectId")
    suspend fun byHealthConnectId(healthConnectId: String): WeightEntryEntity?

    @Query("SELECT * FROM weight_entries WHERE localDate = :localDate ORDER BY timestampUtcMillis ASC")
    suspend fun byLocalDate(localDate: String): List<WeightEntryEntity>

    @Query(
        """
        SELECT * FROM weight_entries
        WHERE timestampUtcMillis BETWEEN :fromUtcMillis AND :toUtcMillis
        ORDER BY timestampUtcMillis ASC
        """,
    )
    suspend fun between(fromUtcMillis: Long, toUtcMillis: Long): List<WeightEntryEntity>

    @Query(
        """
        SELECT * FROM weight_entries
        WHERE (:query = '' OR note LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%')
        ORDER BY timestampUtcMillis DESC
        """,
    )
    fun search(query: String): Flow<List<WeightEntryEntity>>

    @Query("SELECT COUNT(*) FROM weight_entries")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM weight_entries")
    suspend fun count(): Int

    @Query("SELECT * FROM weight_entries WHERE bodyFatPercent IS NOT NULL ORDER BY timestampUtcMillis DESC LIMIT 1")
    suspend fun latestWithBodyFat(): WeightEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: WeightEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<WeightEntryEntity>): List<Long>

    @Update
    suspend fun update(entry: WeightEntryEntity)

    @Delete
    suspend fun delete(entry: WeightEntryEntity)

    @Query("DELETE FROM weight_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM weight_entries")
    suspend fun deleteAll()

    /**
     * Inserts, or updates the row that already represents this reading.
     *
     * Health Connect sync runs repeatedly over overlapping windows, so "insert if new" has to
     * be a single transaction or a second pass duplicates everything it already imported.
     */
    @Transaction
    suspend fun upsertByIdentity(entry: WeightEntryEntity): Long {
        val existing = byClientRecordId(entry.clientRecordId)
            ?: entry.healthConnectId?.let { byHealthConnectId(it) }
        return if (existing == null) {
            insert(entry)
        } else {
            update(entry.copy(id = existing.id))
            existing.id
        }
    }
}

@Dao
interface MeasurementDao {

    @Query("SELECT * FROM measurements ORDER BY timestampUtcMillis DESC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE type = :type ORDER BY timestampUtcMillis ASC")
    fun observeByType(type: String): Flow<List<MeasurementEntity>>

    /**
     * The newest reading for each measurement type, which is what the body fat estimate and
     * the measurements screen both want.
     */
    @Query(
        """
        SELECT m.* FROM measurements m
        INNER JOIN (
            SELECT type, MAX(timestampUtcMillis) AS newest
            FROM measurements GROUP BY type
        ) latest ON m.type = latest.type AND m.timestampUtcMillis = latest.newest
        """,
    )
    fun observeLatestPerType(): Flow<List<MeasurementEntity>>

    @Query(
        """
        SELECT m.* FROM measurements m
        INNER JOIN (
            SELECT type, MAX(timestampUtcMillis) AS newest
            FROM measurements GROUP BY type
        ) latest ON m.type = latest.type AND m.timestampUtcMillis = latest.newest
        """,
    )
    suspend fun latestPerType(): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun byId(id: Long): MeasurementEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: MeasurementEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(measurements: List<MeasurementEntity>): List<Long>

    @Update
    suspend fun update(measurement: MeasurementEntity)

    @Delete
    suspend fun delete(measurement: MeasurementEntity)

    @Query("DELETE FROM measurements WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()
}

@Dao
interface GoalDao {

    @Query("SELECT * FROM goals WHERE active = 1 ORDER BY createdAtUtcMillis DESC LIMIT 1")
    fun observeActive(): Flow<GoalEntity?>

    @Query("SELECT * FROM goals WHERE active = 1 ORDER BY createdAtUtcMillis DESC LIMIT 1")
    suspend fun active(): GoalEntity?

    @Query("SELECT * FROM goals ORDER BY createdAtUtcMillis DESC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("UPDATE goals SET active = 0")
    suspend fun deactivateAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("DELETE FROM goals")
    suspend fun deleteAll()

    /** Only one goal may be active, so setting a new one retires the previous in the same step. */
    @Transaction
    suspend fun replaceActive(goal: GoalEntity): Long {
        deactivateAll()
        return insert(goal.copy(active = true))
    }
}
