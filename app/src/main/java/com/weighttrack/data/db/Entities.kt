package com.weighttrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stored columns stay primitive on purpose. Enums and dates are held as text, so the database
 * can be read by any tool, a schema migration never has to chase a converter, and an export
 * is a straight dump of what is already there.
 */
@Entity(
    tableName = "weight_entries",
    indices = [
        Index(value = ["timestampUtcMillis"]),
        Index(value = ["localDate"]),
        Index(value = ["clientRecordId"], unique = true),
        Index(value = ["healthConnectId"]),
    ],
)
data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampUtcMillis: Long,
    val zoneOffsetSeconds: Int,
    /**
     * ISO date in the zone the reading was taken in, denormalised so day grouping never has to
     * re-derive it. A reading logged at 11pm stays on the day the person took it, even after
     * they fly somewhere else.
     */
    val localDate: String,
    val grams: Int,
    val bodyFatPercent: Double?,
    val note: String?,
    /** Comma separated EntryTag names. Empty string means none. */
    val tags: String,
    val source: String,
    val clientRecordId: String,
    val healthConnectId: String?,
    val updatedAtUtcMillis: Long,
)

@Entity(
    tableName = "measurements",
    indices = [
        Index(value = ["timestampUtcMillis"]),
        Index(value = ["type", "timestampUtcMillis"]),
    ],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampUtcMillis: Long,
    val localDate: String,
    val type: String,
    val valueMm: Int,
    val note: String?,
    val updatedAtUtcMillis: Long,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: String,
    val startGrams: Int,
    val targetGrams: Int,
    val startDate: String,
    val targetDate: String?,
    val milestoneStepGrams: Int,
    val active: Boolean,
    val createdAtUtcMillis: Long,
)
