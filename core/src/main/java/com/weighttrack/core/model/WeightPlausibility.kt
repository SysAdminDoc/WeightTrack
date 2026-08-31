package com.weighttrack.core.model

import java.time.Instant

/** One boundary for readings that arrive without being typed deliberately in WeightTrack. */
object WeightPlausibility {

    const val MIN_GRAMS = 20_000
    const val MAX_GRAMS = 400_000
    const val MAX_FUTURE_SECONDS = 24 * 60 * 60L

    enum class Problem { WEIGHT, TIMESTAMP }

    fun isWeightPlausible(grams: Int): Boolean = grams in MIN_GRAMS..MAX_GRAMS

    fun isTimestampPlausible(timestamp: Instant, now: Instant): Boolean =
        !timestamp.isBefore(Instant.EPOCH) &&
            !timestamp.isAfter(now.plusSeconds(MAX_FUTURE_SECONDS))

    fun problem(grams: Int, timestamp: Instant, now: Instant): Problem? = when {
        !isWeightPlausible(grams) -> Problem.WEIGHT
        !isTimestampPlausible(timestamp, now) -> Problem.TIMESTAMP
        else -> null
    }
}
