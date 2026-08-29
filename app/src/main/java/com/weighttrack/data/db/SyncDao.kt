package com.weighttrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Everything sync needs to read and write, in one place.
 *
 * Deliberately not scoped to a profile, unlike every other query in the app. Sync carries the
 * whole database between a person's own devices, and a household of two would otherwise find
 * that only whichever profile happened to be open ever travelled.
 */
@Dao
interface SyncDao {

    @Query("SELECT * FROM profiles ORDER BY position")
    suspend fun profiles(): List<ProfileEntity>

    @Query("SELECT * FROM weight_entries")
    suspend fun weights(): List<WeightEntryEntity>

    @Query("SELECT * FROM measurements")
    suspend fun measurements(): List<MeasurementEntity>

    @Query("SELECT * FROM water_entries")
    suspend fun water(): List<WaterEntryEntity>

    @Query("SELECT * FROM fasts")
    suspend fun fasts(): List<FastEntity>

    @Query("SELECT * FROM goals")
    suspend fun goals(): List<GoalEntity>

    @Query("SELECT * FROM macro_targets")
    suspend fun macroTargets(): List<MacroTargetEntity>

    // Inserts abort rather than replace. Everything written here has been looked up by its
    // travelling name first, so a conflict would mean the lookup was wrong, and replacing would
    // quietly overwrite a row belonging to somebody else's profile.

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWeights(rows: List<WeightEntryEntity>)

    @Update
    suspend fun updateWeights(rows: List<WeightEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMeasurements(rows: List<MeasurementEntity>)

    @Update
    suspend fun updateMeasurements(rows: List<MeasurementEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWater(rows: List<WaterEntryEntity>)

    @Update
    suspend fun updateWater(rows: List<WaterEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFasts(rows: List<FastEntity>)

    @Update
    suspend fun updateFasts(rows: List<FastEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGoals(rows: List<GoalEntity>)

    @Update
    suspend fun updateGoals(rows: List<GoalEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMacroTargets(rows: List<MacroTargetEntity>)

    @Update
    suspend fun updateMacroTargets(rows: List<MacroTargetEntity>)

    @Query("DELETE FROM weight_entries WHERE clientRecordId IN (:syncIds)")
    suspend fun deleteWeights(syncIds: List<String>)

    @Query("DELETE FROM measurements WHERE syncId IN (:syncIds)")
    suspend fun deleteMeasurements(syncIds: List<String>)

    @Query("DELETE FROM water_entries WHERE syncId IN (:syncIds)")
    suspend fun deleteWater(syncIds: List<String>)

    @Query("DELETE FROM fasts WHERE syncId IN (:syncIds)")
    suspend fun deleteFasts(syncIds: List<String>)

    @Query("DELETE FROM goals WHERE syncId IN (:syncIds)")
    suspend fun deleteGoals(syncIds: List<String>)

    @Query("DELETE FROM macro_targets WHERE syncId IN (:syncIds)")
    suspend fun deleteMacroTargets(syncIds: List<String>)

    /**
     * Removes profiles by their travelling name.
     *
     * Whether removing one is allowed at all is decided before calling this: there has to be a
     * profile left afterwards, because an app with none has nowhere to put a reading and no
     * screen that can draw. Guarding it inside the statement would mean a subquery counting the
     * table it is deleting from, which is not a thing to rely on.
     */
    @Query("DELETE FROM profiles WHERE syncId IN (:syncIds)")
    suspend fun deleteProfiles(syncIds: List<String>)
}
