package com.weighttrack.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDoseDao {

    @Query("SELECT * FROM medication_doses WHERE profileId = :profileId ORDER BY timestampUtcMillis DESC")
    fun observeAll(profileId: Long): Flow<List<MedicationDoseEntity>>

    @Query(
        "SELECT * FROM medication_doses WHERE profileId = :profileId " +
            "AND timestampUtcMillis BETWEEN :from AND :to ORDER BY timestampUtcMillis",
    )
    suspend fun between(profileId: Long, from: Long, to: Long): List<MedicationDoseEntity>

    @Query("SELECT * FROM medication_doses WHERE id = :id")
    suspend fun byId(id: Long): MedicationDoseEntity?

    @Query("SELECT syncId FROM medication_doses WHERE profileId = :profileId")
    suspend fun namesFor(profileId: Long): List<String>

    /** Where the last few went, newest first, for the rotation. */
    @Query(
        "SELECT site FROM medication_doses WHERE profileId = :profileId " +
            "ORDER BY timestampUtcMillis DESC LIMIT :limit",
    )
    suspend fun recentSites(profileId: Long, limit: Int): List<String>

    @Query("SELECT * FROM medication_doses")
    suspend fun all(): List<MedicationDoseEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(dose: MedicationDoseEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(doses: List<MedicationDoseEntity>)

    @Update
    suspend fun update(dose: MedicationDoseEntity)

    @Update
    suspend fun updateAll(doses: List<MedicationDoseEntity>)

    @Delete
    suspend fun delete(dose: MedicationDoseEntity)

    @Query("DELETE FROM medication_doses WHERE syncId IN (:syncIds)")
    suspend fun deleteByNames(syncIds: List<String>)

    @Query("DELETE FROM medication_doses")
    suspend fun deleteAll()
}

@Dao
interface SideEffectDao {

    @Query("SELECT * FROM side_effects WHERE profileId = :profileId ORDER BY timestampUtcMillis DESC")
    fun observeAll(profileId: Long): Flow<List<SideEffectEntity>>

    @Query(
        "SELECT * FROM side_effects WHERE profileId = :profileId " +
            "AND timestampUtcMillis BETWEEN :from AND :to ORDER BY timestampUtcMillis",
    )
    suspend fun between(profileId: Long, from: Long, to: Long): List<SideEffectEntity>

    @Query("SELECT * FROM side_effects WHERE id = :id")
    suspend fun byId(id: Long): SideEffectEntity?

    @Query("SELECT syncId FROM side_effects WHERE profileId = :profileId")
    suspend fun namesFor(profileId: Long): List<String>

    @Query("SELECT * FROM side_effects")
    suspend fun all(): List<SideEffectEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(effect: SideEffectEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(effects: List<SideEffectEntity>)

    @Update
    suspend fun update(effect: SideEffectEntity)

    @Update
    suspend fun updateAll(effects: List<SideEffectEntity>)

    @Delete
    suspend fun delete(effect: SideEffectEntity)

    @Query("DELETE FROM side_effects WHERE syncId IN (:syncIds)")
    suspend fun deleteByNames(syncIds: List<String>)

    @Query("DELETE FROM side_effects")
    suspend fun deleteAll()
}
