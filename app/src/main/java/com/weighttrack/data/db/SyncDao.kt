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

    // What one profile owns, by the names its rows travel under. Read before the profile is
    // deleted, so the deletions can be made to travel with it.

    @Query("SELECT clientRecordId FROM weight_entries WHERE profileId = :profileId")
    suspend fun weightNames(profileId: Long): List<String>

    @Query("SELECT syncId FROM measurements WHERE profileId = :profileId")
    suspend fun measurementNames(profileId: Long): List<String>

    @Query("SELECT syncId FROM water_entries WHERE profileId = :profileId")
    suspend fun waterNames(profileId: Long): List<String>

    @Query("SELECT syncId FROM fasts WHERE profileId = :profileId")
    suspend fun fastNames(profileId: Long): List<String>

    @Query("SELECT syncId FROM goals WHERE profileId = :profileId")
    suspend fun goalNames(profileId: Long): List<String>

    @Query("SELECT syncId FROM macro_targets WHERE profileId = :profileId")
    suspend fun macroTargetNames(profileId: Long): List<String>

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

    @Query("SELECT * FROM foods")
    suspend fun foods(): List<FoodEntity>

    @Query("SELECT * FROM recipes")
    suspend fun recipes(): List<RecipeEntity>

    @Query("SELECT * FROM recipe_items")
    suspend fun recipeItems(): List<RecipeItemEntity>

    @Query("SELECT * FROM food_log_entries")
    suspend fun foodLog(): List<FoodLogEntryEntity>

    @Query("SELECT syncId FROM food_log_entries WHERE profileId = :profileId")
    suspend fun foodLogNames(profileId: Long): List<String>

    @Query("SELECT syncId FROM medication_doses WHERE profileId = :profileId")
    suspend fun medicationDoseNames(profileId: Long): List<String>

    @Query("SELECT syncId FROM side_effects WHERE profileId = :profileId")
    suspend fun sideEffectNames(profileId: Long): List<String>

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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFoods(rows: List<FoodEntity>)

    @Update
    suspend fun updateFoods(rows: List<FoodEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecipes(rows: List<RecipeEntity>)

    @Update
    suspend fun updateRecipes(rows: List<RecipeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecipeItems(rows: List<RecipeItemEntity>)

    @Update
    suspend fun updateRecipeItems(rows: List<RecipeItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFoodLog(rows: List<FoodLogEntryEntity>)

    @Update
    suspend fun updateFoodLog(rows: List<FoodLogEntryEntity>)

    /**
     * Removes weigh-ins by name, within one profile.
     *
     * Scoped, because a weigh-in's name is only unique within a profile. The same backup restored
     * for two people, or the same file imported twice, gives both of them rows with identical
     * names, and an unscoped delete would take one person's history out with the other's.
     */
    @Query("DELETE FROM weight_entries WHERE profileId = :profileId AND clientRecordId IN (:syncIds)")
    suspend fun deleteWeights(profileId: Long, syncIds: List<String>)

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

    @Query("DELETE FROM foods WHERE syncId IN (:syncIds)")
    suspend fun deleteFoods(syncIds: List<String>)

    @Query("DELETE FROM recipes WHERE syncId IN (:syncIds)")
    suspend fun deleteRecipes(syncIds: List<String>)

    @Query("DELETE FROM recipe_items WHERE syncId IN (:syncIds)")
    suspend fun deleteRecipeItems(syncIds: List<String>)

    @Query("DELETE FROM food_log_entries WHERE syncId IN (:syncIds)")
    suspend fun deleteFoodLog(syncIds: List<String>)

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
