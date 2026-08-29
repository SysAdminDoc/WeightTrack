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

    @Query(
        "SELECT * FROM weight_entries WHERE profileId = :profileId " +
            "AND clientRecordId = :clientRecordId",
    )
    suspend fun byClientRecordId(profileId: Long, clientRecordId: String): WeightEntryEntity?

    @Query(
        "SELECT * FROM weight_entries WHERE profileId = :profileId " +
            "AND healthConnectId = :healthConnectId",
    )
    suspend fun byHealthConnectId(profileId: Long, healthConnectId: String): WeightEntryEntity?

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
     * Inserts, or updates the row that already represents this reading, within its own profile.
     *
     * Health Connect sync runs repeatedly over overlapping windows, so "insert if new" has to
     * be a single transaction or a second pass duplicates everything it already imported.
     *
     * Scoped to the entry's profile. Matching across profiles does not merge two rows, it
     * rewrites the profile column of the one it found, which silently moves somebody else's
     * reading. A backup restored, or a file imported, under a second person did exactly that.
     */
    @Transaction
    suspend fun upsertByIdentity(entry: WeightEntryEntity): Long {
        val existing = byClientRecordId(entry.profileId, entry.clientRecordId)
            ?: entry.healthConnectId?.let { byHealthConnectId(entry.profileId, it) }
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

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun byId(id: Long): GoalEntity?

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
    @Query("SELECT fileName FROM progress_photos WHERE profileId = :profileId")
    suspend fun photoFileNames(profileId: Long): List<String>

    @Transaction
    suspend fun deleteWithData(profile: ProfileEntity) {
        deleteWeightEntries(profile.id)
        deleteMeasurements(profile.id)
        deleteGoals(profile.id)
        deleteWaterEntries(profile.id)
        deleteFasts(profile.id)
        deleteProgressPhotos(profile.id)
        deleteFoodLog(profile.id)
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

    @Query("DELETE FROM food_log_entries WHERE profileId = :profileId")
    suspend fun deleteFoodLog(profileId: Long)
}

/** A recipe with everything in it, which is the only useful way to read one. */
data class RecipeWithItems(
    @androidx.room.Embedded val recipe: RecipeEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "recipeId")
    val items: List<RecipeItemEntity>,
)

@Dao
interface FoodDao {

    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun byId(id: Long): FoodEntity?

    /** The newest entry for a barcode. Two brands share a code often enough to matter. */
    @Query("SELECT * FROM foods WHERE barcode = :barcode ORDER BY updatedAtUtcMillis DESC LIMIT 1")
    suspend fun byBarcode(barcode: String): FoodEntity?

    @Query(
        """
        SELECT * FROM foods
        WHERE name LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%'
        ORDER BY favourite DESC, lastUsedAtUtcMillis DESC, name ASC
        LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int): Flow<List<FoodEntity>>

    /** What somebody actually eats, which is a far better first offer than a search box. */
    @Query(
        "SELECT * FROM foods WHERE lastUsedAtUtcMillis > 0 " +
            "ORDER BY lastUsedAtUtcMillis DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods WHERE favourite = 1 ORDER BY name ASC")
    fun observeFavourites(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods WHERE origin = :origin ORDER BY name ASC")
    fun observeByOrigin(origin: String): Flow<List<FoodEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(food: FoodEntity): Long

    @Update
    suspend fun update(food: FoodEntity)

    @Delete
    suspend fun delete(food: FoodEntity)

    @Query("UPDATE foods SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE foods SET lastUsedAtUtcMillis = :atUtcMillis WHERE id = :id")
    suspend fun markUsed(id: Long, atUtcMillis: Long)

    /**
     * Files a food looked up online without making a second copy of it.
     *
     * A cached product is looked up by barcode every time it is scanned, so a plain insert
     * would build a pile of identical rows. What comes back may be better than what was
     * cached, though, so the stored row is refreshed rather than left alone.
     */
    @Transaction
    suspend fun cache(food: FoodEntity): Long {
        val existing = food.barcode?.let { byBarcode(it) }
        return if (existing == null) {
            insert(food)
        } else {
            // Whether it is a favourite, and when it was last eaten, belong to the person
            // rather than to the database it came from.
            update(
                food.copy(
                    id = existing.id,
                    favourite = existing.favourite,
                    lastUsedAtUtcMillis = existing.lastUsedAtUtcMillis,
                ),
            )
            existing.id
        }
    }

    @Query("DELETE FROM foods")
    suspend fun deleteAll()

    // ---- recipes ----

    @Transaction
    @Query("SELECT * FROM recipes ORDER BY name ASC")
    fun observeRecipes(): Flow<List<RecipeWithItems>>

    @Transaction
    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun recipeById(id: Long): RecipeWithItems?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecipe(recipe: RecipeEntity): Long

    @Update
    suspend fun updateRecipe(recipe: RecipeEntity)

    @Delete
    suspend fun deleteRecipe(recipe: RecipeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecipeItems(items: List<RecipeItemEntity>)

    @Query("DELETE FROM recipe_items WHERE recipeId = :recipeId")
    suspend fun deleteRecipeItems(recipeId: Long)

    /** Replaces a recipe's contents wholesale, which is what editing one amounts to. */
    @Transaction
    suspend fun replaceRecipeItems(recipeId: Long, items: List<RecipeItemEntity>) {
        deleteRecipeItems(recipeId)
        insertRecipeItems(items.map { it.copy(recipeId = recipeId) })
    }

    @Query("DELETE FROM recipes")
    suspend fun deleteAllRecipes()
}

/** A day's totals, summed in SQL so a screen never loads a month to add one day up. */
data class DailyIntakeRow(
    val localDate: String,
    val kcal: Double,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
)

@Dao
interface FoodLogDao {

    @Query(
        "SELECT * FROM food_log_entries WHERE profileId = :profileId AND localDate = :localDate " +
            "ORDER BY loggedAtUtcMillis ASC",
    )
    fun observeForDate(profileId: Long, localDate: String): Flow<List<FoodLogEntryEntity>>

    @Query(
        "SELECT * FROM food_log_entries WHERE profileId = :profileId AND localDate = :localDate " +
            "ORDER BY loggedAtUtcMillis ASC",
    )
    suspend fun forDate(profileId: Long, localDate: String): List<FoodLogEntryEntity>

    @Query(
        """
        SELECT localDate,
               SUM(kcal) AS kcal,
               SUM(proteinG) AS proteinG,
               SUM(carbsG) AS carbsG,
               SUM(fatG) AS fatG
        FROM food_log_entries
        WHERE profileId = :profileId
        GROUP BY localDate
        ORDER BY localDate DESC
        LIMIT :days
        """,
    )
    fun observeRecentDays(profileId: Long, days: Int): Flow<List<DailyIntakeRow>>

    @Query("SELECT * FROM food_log_entries WHERE id = :id")
    suspend fun byId(id: Long): FoodLogEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: FoodLogEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<FoodLogEntryEntity>)

    @Update
    suspend fun update(entry: FoodLogEntryEntity)

    @Delete
    suspend fun delete(entry: FoodLogEntryEntity)

    @Query("DELETE FROM food_log_entries WHERE profileId = :profileId AND localDate = :localDate")
    suspend fun deleteForDate(profileId: Long, localDate: String)

    @Query("DELETE FROM food_log_entries")
    suspend fun deleteAll()
}
