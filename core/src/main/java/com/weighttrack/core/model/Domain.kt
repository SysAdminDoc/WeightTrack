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
    /**
     * What a scale said beyond the weight, when one did.
     *
     * Null for a weight typed in or imported. Everything a body-composition scale sends used to
     * reach the screen and then be dropped on save except the body-fat percentage.
     */
    val composition: BodyComposition? = null,
    /**
     * Which app and which device wrote this, for the ones that arrived from Health Connect.
     *
     * Null for anything typed in here or read off a scale over Bluetooth, where the source alone
     * says everything there is to say.
     */
    val origin: RecordOrigin? = null,
)

data class BodyMeasurement(
    val id: Long = 0,
    val timestamp: Instant,
    val localDate: LocalDate,
    val type: MeasurementType,
    val valueMm: Int,
    val note: String? = null,
)

/**
 * How far either way still counts as being at the target.
 *
 * A kilogram, until somebody says otherwise. Nobody holds a weight to the gram, and a band that
 * is only a rounding error turns a maintain goal into a thing that is failed most mornings.
 */
const val DEFAULT_GOAL_BAND_GRAMS = 1000

data class Goal(
    val id: Long = 0,
    val direction: GoalDirection,
    val startGrams: Int,
    val targetGrams: Int,
    val startDate: LocalDate,
    val targetDate: LocalDate? = null,
    val milestoneStepGrams: Int,
    /**
     * How far either way still counts as being there.
     *
     * A maintain goal is held inside this band. A loss or gain goal uses it to tell holding at
     * the target apart from having gone past it, which are different things to be told.
     */
    val bandGrams: Int = DEFAULT_GOAL_BAND_GRAMS,
    val active: Boolean = true,
    /**
     * When this goal was set, which is not the same as [startDate].
     *
     * Editing a goal keeps the date it started from, on purpose, so the progress bar does not
     * reset. That leaves nothing on the row saying when the target actually changed, which is
     * what anything reacting to a change of mind has to read.
     */
    val setAtUtcMillis: Long = 0,
    /** Last write to this goal. On a retired one, when it was retired. */
    val changedAtUtcMillis: Long = 0,
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
