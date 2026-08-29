package com.weighttrack.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Context a person can attach to a reading. Weight swings for reasons that have nothing to do
 * with fat, and being able to say "travel" or "high salt" turns a demoralising spike into an
 * explained one.
 */
enum class EntryTag {
    POST_WORKOUT,
    FASTED,
    WELL_HYDRATED,
    TRAVEL,
    ALCOHOL,
    HIGH_SALT,
    PERIOD,
    ILL,
    POOR_SLEEP,
    STRESSED,
}

enum class MeasurementType {
    NECK,
    SHOULDERS,
    CHEST,
    WAIST,
    HIPS,
    LEFT_ARM,
    RIGHT_ARM,
    LEFT_FOREARM,
    RIGHT_FOREARM,
    LEFT_THIGH,
    RIGHT_THIGH,
    LEFT_CALF,
    RIGHT_CALF,
    ;

    /** The three the Navy body fat estimate needs. */
    val usedForBodyFat: Boolean
        get() = this == NECK || this == WAIST || this == HIPS
}

data class WeightEntry(
    val id: Long = 0,
    val timestamp: Instant,
    val zoneOffset: ZoneOffset,
    val localDate: LocalDate,
    val grams: Int,
    val bodyFatPercent: Double? = null,
    val note: String? = null,
    val tags: Set<EntryTag> = emptySet(),
    val source: EntrySource = EntrySource.MANUAL,
    /**
     * Stable identifier this app owns. It is written into Health Connect as the client record
     * id, which is what lets a later read recognise our own rows instead of importing them
     * back as duplicates.
     */
    val clientRecordId: String,
    /** Health Connect's own identifier, present only for records that arrived from there. */
    val healthConnectId: String? = null,
)

data class BodyMeasurement(
    val id: Long = 0,
    val timestamp: Instant,
    val localDate: LocalDate,
    val type: MeasurementType,
    val valueMm: Int,
    val note: String? = null,
)

data class Goal(
    val id: Long = 0,
    val direction: GoalDirection,
    val startGrams: Int,
    val targetGrams: Int,
    val startDate: LocalDate,
    val targetDate: LocalDate? = null,
    val milestoneStepGrams: Int,
    val active: Boolean = true,
)

/** The handful of facts the body composition formulas need. Held in settings, not the database. */
data class UserProfile(
    val heightMm: Int = 0,
    val sex: Sex = Sex.MALE,
    val birthYear: Int = 0,
    val activityLevel: ActivityLevel = ActivityLevel.LIGHT,
) {
    val hasHeight: Boolean get() = heightMm > 0

    fun ageYears(today: LocalDate): Int =
        if (birthYear <= 0) 0 else (today.year - birthYear).coerceAtLeast(0)
}
