package com.weighttrack.core.model

/**
 * Weight is stored everywhere as whole grams. Display units are a presentation concern only,
 * so switching units never rewrites stored data and never loses precision.
 */
enum class WeightUnit {
    KG,
    LB,
    ST_LB,
}

enum class LengthUnit {
    CM,
    IN,
}

enum class Sex {
    MALE,
    FEMALE,
}

/** Multipliers applied to BMR to estimate total daily energy expenditure. */
enum class ActivityLevel(val factor: Double) {
    SEDENTARY(1.200),
    LIGHT(1.375),
    MODERATE(1.550),
    ACTIVE(1.725),
    VERY_ACTIVE(1.900),
}

enum class GoalDirection {
    LOSE,
    GAIN,
    MAINTAIN,
}

/** Where a record came from. Used to dedupe and to decide what may be overwritten by a sync. */
enum class EntrySource {
    MANUAL,
    HEALTH_CONNECT,
    IMPORT,
    SCALE,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED,
}
