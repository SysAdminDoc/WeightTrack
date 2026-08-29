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

    @Query("SELECT * FROM weight_entries WHERE profileId = :profileId ORDER BY timestampUtcMillis DESC")
    fun observeAll(profileId: Long): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries WHERE profileId = :profileId ORDER BY timestampUtcMillis ASC")
    fun observeAllAscending(profileId: Long): Flow<List<WeightEntryEntity>>

    @Query(
        """
        SELECT localDate, AVG(grams) AS grams
        FROM weight_entries
        WHERE profileId = :profileId
        GROUP BY localDate
        ORDER BY localDate ASC
        """,
    )
    fun observeDailyAverages(profileId: Long): Flow<List<DailyWeightRow>>

    @Query(
        "SELECT * FROM weight_entries WHERE profileId = :profileId " +
            "ORDER BY timestampUtcMillis DESC LIMIT 1",
    )
    fun observeLatest(profileId: Long): Flow<WeightEntryEntity?>

    @Query(
        "SELECT * FROM weight_entries WHERE profileId = :profileId " +
            "ORDER BY timestampUtcMillis DESC LIMIT 1",
    )
    suspend fun latest(profileId: Long): WeightEntryEntity?

    @Query(
        "SELECT * FROM weight_entries WHERE profileId = :profileId " +
            "ORDER BY timestampUtcMillis ASC LIMIT 1",
    )
    suspend fun earliest(profileId: Long): WeightEntryEntity?

    /** The most recent reading of every profile, which is how a scale reading finds its owner. */
    @Query(
        """
        SELECT w.* FROM weight_entries w
        INNER JOIN (
            SELECT profileId, MAX(timestampUtcMillis) AS newest
            FROM weight_entries GROUP BY profileId
        ) latest ON w.profileId = latest.profileId AND w.timestampUtcMillis = latest.newest
        """,
    )
    suspend fun latestPerProfile(): List<WeightEntryEntity>

    @Query(
        "SELECT * FROM weight_entries WHERE profileId = :profileId " +
            "AND timestampUtcMillis <= :atUtcMillis " +
            "ORDER BY timestampUtcMillis DESC LIMIT 1",
    )
    suspend fun latestAtOrBefore(profileId: Long, atUtcMillis: Long): WeightEntryEntity?

    @Query("SELECT * FROM weight_entries WHERE id = :id")
    suspend fun byId(id: Long): WeightEntryEntity?

    @Query("SELECT * FROM weight_entries WHERE clientRecordId = :clientRecordId")
    suspend fun byClientRecordId(clientRecordId: String): WeightEntryEntity?

    @Query("SELECT * FROM weight_entries WHERE healthConnectId = :healthConnectId")
    suspend fun byHealthConnectId(healthConnectId: String): WeightEntryEntity?

    @Query(
        "SELECT * FROM weight_entries WHERE profileId = :profileId AND localDate = :localDate " +
            "ORDER BY timestampUtcMillis ASC",
    )
    suspend fun byLocalDate(profileId: Long, localDate: String): List<WeightEntryEntity>

    @Query(
        """
        SELECT * FROM weight_entries
        WHERE profileId = :profileId
        AND timestampUtcMillis BETWEEN :fromUtcMillis AND :toUtcMillis
        ORDER BY timestampUtcMillis ASC
        """,
    )
    suspend fun between(
        profileId: Long,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): List<WeightEntryEntity>

    @Query(
        """
        SELECT * FROM weight_entries
        WHERE profileId = :profileId
        AND (:query = '' OR note LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%')
        ORDER BY timestampUtcMillis DESC
        """,
    )
    fun search(profileId: Long, query: String): Flow<List<WeightEntryEntity>>

    @Query("SELECT COUNT(*) FROM weight_entries WHERE profileId = :profileId")
    fun observeCount(profileId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM weight_entries WHERE profileId = :profileId")
    suspend fun count(profileId: Long): Int

    @Query(
        "SELECT * FROM weight_entries WHERE profileId = :profileId " +
            "AND bodyFatPercent IS NOT NULL ORDER BY timestampUtcMillis DESC LIMIT 1",
    )
    suspend fun latestWithBodyFat(profileId: Long): WeightEntryEntity?

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

    @Query("SELECT * FROM measurements WHERE profileId = :profileId ORDER BY timestampUtcMillis DESC")
    fun observeAll(profileId: Long): Flow<List<MeasurementEntity>>

    @Query(
        "SELECT * FROM measurements WHERE profileId = :profileId AND type = :type " +
            "ORDER BY timestampUtcMillis ASC",
    )
    fun observeByType(profileId: Long, type: String): Flow<List<MeasurementEntity>>

    /**
     * The newest reading for each measurement type, which is what the body fat estimate and
     * the measurements screen both want.
     */
    @Query(
        """
        SELECT m.* FROM measurements m
        INNER JOIN (
            SELECT type, MAX(timestampUtcMillis) AS newest
            FROM measurements WHERE profileId = :profileId GROUP BY type
        ) latest ON m.type = latest.type AND m.timestampUtcMillis = latest.newest
        WHERE m.profileId = :profileId
        """,
    )
    fun observeLatestPerType(profileId: Long): Flow<List<MeasurementEntity>>

    @Query(
        """
        SELECT m.* FROM measurements m
        INNER JOIN (
            SELECT type, MAX(timestampUtcMillis) AS newest
            FROM measurements WHERE profileId = :profileId GROUP BY type
        ) latest ON m.type = latest.type AND m.timestampUtcMillis = latest.newest
        WHERE m.profileId = :profileId
        """,
    )
    suspend fun latestPerType(profileId: Long): List<MeasurementEntity>

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

    @Query(
        "SELECT * FROM goals WHERE profileId = :profileId AND active = 1 " +
            "ORDER BY createdAtUtcMillis DESC LIMIT 1",
    )
    fun observeActive(profileId: Long): Flow<GoalEntity?>

    @Query(
        "SELECT * FROM goals WHERE profileId = :profileId AND active = 1 " +
            "ORDER BY createdAtUtcMillis DESC LIMIT 1",
    )
    suspend fun active(profileId: Long): GoalEntity?

    @Query("SELECT * FROM goals WHERE profileId = :profileId ORDER BY createdAtUtcMillis DESC")
    fun observeAll(profileId: Long): Flow<List<GoalEntity>>

    @Query("UPDATE goals SET active = 0 WHERE profileId = :profileId")
    suspend fun deactivateAll(profileId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("DELETE FROM goals")
    suspend fun deleteAll()

    /**
     * Only one goal may be active per profile, so setting a new one retires the previous in the
     * same step. Scoped, or setting a goal would quietly retire everybody else's.
     */
    @Transaction
    suspend fun replaceActive(profileId: Long, goal: GoalEntity): Long {
        deactivateAll(profileId)
        return insert(goal.copy(profileId = profileId, active = true))
    }
}

/** A day's water total, summed in SQL so the screen never loads a whole history to add it up. */
data class DailyWaterRow(
    val localDate: String,
    val millilitres: Int,
)

@Dao
interface WaterDao {

    @Query(
        "SELECT * FROM water_entries WHERE profileId = :profileId AND localDate = :localDate " +
            "ORDER BY timestampUtcMillis DESC",
    )
    fun observeForDate(profileId: Long, localDate: String): Flow<List<WaterEntryEntity>>

    @Query(
        "SELECT COALESCE(SUM(millilitres), 0) FROM water_entries " +
            "WHERE profileId = :profileId AND localDate = :localDate",
    )
    fun observeTotalForDate(profileId: Long, localDate: String): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(millilitres), 0) FROM water_entries " +
            "WHERE profileId = :profileId AND localDate = :localDate",
    )
    suspend fun totalForDate(profileId: Long, localDate: String): Int

    @Query(
        """
        SELECT localDate, CAST(SUM(millilitres) AS INTEGER) AS millilitres
        FROM water_entries
        WHERE profileId = :profileId
        GROUP BY localDate
        ORDER BY localDate DESC
        LIMIT :days
        """,
    )
    fun observeRecentDays(profileId: Long, days: Int): Flow<List<DailyWaterRow>>

    @Query("SELECT * FROM water_entries WHERE id = :id")
    suspend fun byId(id: Long): WaterEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: WaterEntryEntity): Long

    @Delete
    suspend fun delete(entry: WaterEntryEntity)

    @Query("UPDATE water_entries SET healthConnectId = :clientRecordId WHERE id = :id")
    suspend fun setHealthConnectId(id: Long, clientRecordId: String)

    @Query("DELETE FROM water_entries WHERE profileId = :profileId AND localDate = :localDate")
    suspend fun deleteForDate(profileId: Long, localDate: String)

    @Query("DELETE FROM water_entries")
    suspend fun deleteAll()
}

@Dao
interface FastDao {

    @Query(
        "SELECT * FROM fasts WHERE profileId = :profileId AND endUtcMillis IS NULL " +
            "ORDER BY startUtcMillis DESC LIMIT 1",
    )
    fun observeActive(profileId: Long): Flow<FastEntity?>

    @Query(
        "SELECT * FROM fasts WHERE profileId = :profileId AND endUtcMillis IS NULL " +
            "ORDER BY startUtcMillis DESC LIMIT 1",
    )
    suspend fun active(profileId: Long): FastEntity?

    @Query(
        "SELECT * FROM fasts WHERE profileId = :profileId AND endUtcMillis IS NOT NULL " +
            "ORDER BY startUtcMillis DESC",
    )
    fun observeCompleted(profileId: Long): Flow<List<FastEntity>>

    @Query("SELECT * FROM fasts WHERE id = :id")
    suspend fun byId(id: Long): FastEntity?

    @Query(
        "SELECT COUNT(*) FROM fasts WHERE profileId = :profileId AND endUtcMillis IS NOT NULL",
    )
    fun observeCompletedCount(profileId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(fast: FastEntity): Long

    @Update
    suspend fun update(fast: FastEntity)

    @Delete
    suspend fun delete(fast: FastEntity)

    @Query("DELETE FROM fasts")
    suspend fun deleteAll()

    /**
     * Starts a fast, unless one is already running.
     *
     * This used to close the running fast at the new start. That turned a double tap into a
     * zero length fast in history, and a backwards clock correction between two starts stored an
     * end before its own start. A second start is a double tap, not a new fast, so it is refused
     * and the running fast is returned untouched. The check sits inside the transaction with the
     * insert so two taps landing together cannot both get through it.
     */
    @Transaction
    suspend fun startFast(profileId: Long, startUtcMillis: Long, targetMinutes: Int): Long? {
        if (active(profileId) != null) return null
        return insert(
            FastEntity(
                profileId = profileId,
                startUtcMillis = startUtcMillis,
                endUtcMillis = null,
                targetMinutes = targetMinutes,
                note = null,
                updatedAtUtcMillis = startUtcMillis,
            ),
        )
    }
}

@Dao
interface ProgressPhotoDao {

    @Query("SELECT * FROM progress_photos WHERE profileId = :profileId ORDER BY timestampUtcMillis DESC")
    fun observeAll(profileId: Long): Flow<List<ProgressPhotoEntity>>

    /** Every profile's photos, for the delete that has to leave no file behind. */
    @Query("SELECT * FROM progress_photos ORDER BY timestampUtcMillis DESC")
    suspend fun all(): List<ProgressPhotoEntity>

    @Query("SELECT * FROM progress_photos WHERE id = :id")
    suspend fun byId(id: Long): ProgressPhotoEntity?

    @Query("SELECT COUNT(*) FROM progress_photos WHERE profileId = :profileId")
    fun observeCount(profileId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: ProgressPhotoEntity): Long

    @Delete
    suspend fun delete(photo: ProgressPhotoEntity)

    @Query("DELETE FROM progress_photos")
    suspend fun deleteAll()
}

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY position ASC, id ASC")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun byId(id: Long): ProfileEntity?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM profiles")
    suspend fun highestPosition(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Delete
    suspend fun delete(profile: ProfileEntity)

    /**
     * Removes a profile and everything that belonged to it, in one transaction.
     *
     * Deleting the row on its own would leave rows pointing at nothing, which is worse than
     * losing them: they would still be counted and still be exported, but never shown.
     */
    @Transaction
    suspend fun deleteWithData(profile: ProfileEntity) {
        deleteWeightEntries(profile.id)
        deleteMeasurements(profile.id)
        deleteGoals(profile.id)
        deleteWaterEntries(profile.id)
        deleteFasts(profile.id)
        deleteProgressPhotos(profile.id)
        delete(profile)
    }

    @Query("DELETE FROM weight_entries WHERE profileId = :profileId")
    suspend fun deleteWeightEntries(profileId: Long)

    @Query("DELETE FROM measurements WHERE profileId = :profileId")
    suspend fun deleteMeasurements(profileId: Long)

    @Query("DELETE FROM goals WHERE profileId = :profileId")
    suspend fun deleteGoals(profileId: Long)

    @Query("DELETE FROM water_entries WHERE profileId = :profileId")
    suspend fun deleteWaterEntries(profileId: Long)

    @Query("DELETE FROM fasts WHERE profileId = :profileId")
    suspend fun deleteFasts(profileId: Long)

    @Query("DELETE FROM progress_photos WHERE profileId = :profileId")
    suspend fun deleteProgressPhotos(profileId: Long)
}
