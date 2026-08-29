package com.weighttrack.ble

import kotlin.math.abs

/** Whether a reading off a scale looks like it belongs to the person using the app. */
enum class ScaleMatch {
    /** Close enough to the last known weight to be the same person. */
    MATCHES,

    /**
     * Too far from the last known weight.
     *
     * A shared bathroom scale is the normal case for this: a partner stepping on it should not
     * put a step change through someone else's trend. The reading is offered rather than
     * dropped, because the other reason for a jump is a real one.
     */
    OUT_OF_RANGE,

    /** Nothing to compare against yet, so there is no reason to doubt it. */
    NO_HISTORY,

    /** Not a weight a person has. */
    IMPLAUSIBLE,
}

/**
 * Decides what to do with a weight that arrived on its own.
 *
 * A reading typed into the app is deliberate. One off a scale is not: whoever stood on it last
 * is who it belongs to, and the app has no way to ask at that moment. The weight range is the
 * only signal available, and it is the one a family scale uses too.
 */
object ScaleReadingRouter {

    /** Nobody using this app weighs less or more than these. */
    const val MIN_GRAMS = 20_000
    const val MAX_GRAMS = 400_000

    /**
     * How far from the last known weight a reading may land and still be the same person.
     *
     * Eight kilograms is wide enough for a fortnight away, heavy clothing, or a scale that
     * disagrees with the last one, and narrow enough that another adult in the house lands
     * outside it.
     */
    const val DEFAULT_TOLERANCE_GRAMS = 8_000

    fun match(
        grams: Int,
        lastKnownGrams: Int?,
        toleranceGrams: Int = DEFAULT_TOLERANCE_GRAMS,
    ): ScaleMatch = when {
        grams < MIN_GRAMS || grams > MAX_GRAMS -> ScaleMatch.IMPLAUSIBLE
        lastKnownGrams == null -> ScaleMatch.NO_HISTORY
        abs(grams - lastKnownGrams) <= toleranceGrams -> ScaleMatch.MATCHES
        else -> ScaleMatch.OUT_OF_RANGE
    }

    /**
     * Which profile a weight off a shared scale belongs to.
     *
     * The nearest last known weight wins, as long as it is near enough to be the same person.
     * This is the whole point of a family scale: whoever stood on it last is who it belongs to,
     * and the weight is the only thing the scale can say about them.
     *
     * Null when nobody is close enough, which is the case the screen has to ask about.
     */
    fun owner(
        grams: Int,
        lastKnownByProfile: Map<Long, Int>,
        toleranceGrams: Int = DEFAULT_TOLERANCE_GRAMS,
    ): Long? {
        if (grams < MIN_GRAMS || grams > MAX_GRAMS) return null
        return lastKnownByProfile
            .filterValues { abs(grams - it) <= toleranceGrams }
            .minByOrNull { abs(grams - it.value) }
            ?.key
    }

    /** Whether a reading may be recorded without asking first. */
    fun recordsWithoutAsking(match: ScaleMatch): Boolean =
        match == ScaleMatch.MATCHES || match == ScaleMatch.NO_HISTORY
}
