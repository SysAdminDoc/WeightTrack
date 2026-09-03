package com.weighttrack.core.medication

import com.weighttrack.core.model.WeightPlausibility
import kotlin.math.roundToInt

/**
 * How much protein a day, while losing weight fast on a GLP-1.
 *
 * People on these lose ten to thirteen percent of their body weight, and a large part of what
 * comes off is lean tissue unless protein intake is kept up. The published guidance for that
 * situation is 1.2 to 1.6 grams per kilogram of body weight per day.
 *
 * Shown only where the diary already shows what somebody ate. It is a number to aim at, not a
 * verdict on a day, and it deliberately does not appear anywhere it would read as one.
 */
object ProteinTarget {

    const val LOW_GRAMS_PER_KG = 1.2
    const val HIGH_GRAMS_PER_KG = 1.6

    /** Grams of protein a day, or null when the body mass it would rest on is not believable. */
    fun dailyGrams(bodyMassGrams: Int): IntRange? {
        // The mass comes off the trend line, which one mistyped reading drags kilograms. A target
        // worked out from four kilograms is worse than no target at all.
        if (!WeightPlausibility.isWeightPlausible(bodyMassGrams)) return null
        val kg = bodyMassGrams / 1_000.0
        return (kg * LOW_GRAMS_PER_KG).roundToInt()..(kg * HIGH_GRAMS_PER_KG).roundToInt()
    }
}
