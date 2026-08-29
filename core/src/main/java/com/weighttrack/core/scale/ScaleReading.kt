package com.weighttrack.core.scale

/**
 * One reading from a scale, converted into the units this app stores.
 *
 * Everything past the weight is optional: a plain scale sends a weight, a body composition one
 * sends whatever its electrodes managed. A field that was not measured is null rather than zero,
 * because zero body fat is a value and "not measured" is not.
 */
data class ScaleReading(
    val grams: Int,
    val bodyFatPercent: Double? = null,
    val muscleMassGrams: Int? = null,
    val fatFreeMassGrams: Int? = null,
    val softLeanMassGrams: Int? = null,
    val bodyWaterMassGrams: Int? = null,
    val musclePercent: Double? = null,
    val impedanceOhms: Double? = null,
    val basalMetabolismKcal: Double? = null,
    val heightMm: Int? = null,
    val bmi: Double? = null,
    /**
     * The user slot the scale thinks this belongs to, when it has slots at all.
     *
     * Not the same thing as a profile in this app. It is kept because a family scale that does
     * its own recognition is worth listening to before falling back to a weight range.
     */
    val scaleUserId: Int? = null,
    /**
     * What the scale's own clock said, as raw components, or null when it did not say.
     *
     * Deliberately not turned into an instant to record against. Scale clocks are wrong far more
     * often than they are right, and the person is standing on it now. This is only here so a
     * broadcast repeated fifty times in a row can be recognised as one reading.
     */
    val scaleClock: ScaleClock? = null,
)

/** A scale's own idea of the time, exactly as it sent it. Any part may be zero for "unknown". */
data class ScaleClock(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/**
 * A weight seen in a scale's advertisement, which arrives many times a second while someone is
 * still settling.
 *
 * Separate from [ScaleReading] because most of these are not readings yet: the interesting part
 * is whether the number has stopped moving.
 */
data class ScaleBroadcast(
    val reading: ScaleReading,
    val stabilized: Boolean,
    val weightRemoved: Boolean,
) {
    /** Worth recording: the number settled and the person is still on the scale. */
    val isFinal: Boolean get() = stabilized && !weightRemoved && reading.grams > 0
}
