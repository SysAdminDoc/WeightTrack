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
 * Who a weight off a shared scale belongs to, as far as the weight can say.
 *
 * The old answer was a profile or nothing, and "nearest inside eight kilograms" was treated as
 * an answer however close the second-nearest was. Two adults within a few kilograms of each
 * other is not an unusual household, and in one the app was quietly filing every weigh-in under
 * whoever happened to be marginally nearer that morning: a step change in one person's trend and
 * a hole in the other's, with nothing on any screen suggesting a choice had been made.
 */
sealed interface ScaleRouting {

    /** One person is clearly nearest. Filed without asking, which is the point of the feature. */
    data class Clear(val profileId: Long) : ScaleRouting

    /**
     * Two or more people whose last weights sit too close together to tell apart.
     *
     * Named in order of nearness, so a picker can lead with the likeliest. Asking is the honest
     * answer here: the scale cannot tell them apart and neither can this.
     */
    data class Ambiguous(val profileIds: List<Long>) : ScaleRouting

    /** Nobody is near enough. The screen asks in its own way. */
    data object Unknown : ScaleRouting
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
     * How much nearer the best match has to be than the next one to count as an answer.
     *
     * Two kilograms. Inside that, two people in a household are not distinguishable by weight on
     * one morning, and the app has no business deciding for them: a wrong guess puts a step
     * change in one person's trend and a hole in the other's, and neither is visible.
     */
    const val AMBIGUOUS_MARGIN_GRAMS = 2_000

    /**
     * Who a reading belongs to, or that it cannot be told.
     *
     * Everybody inside the tolerance, nearest first. Clear when the nearest is enough clearer
     * than the next; ambiguous when it is not.
     */
    fun route(
        grams: Int,
        lastKnownByProfile: Map<Long, Int>,
        toleranceGrams: Int = DEFAULT_TOLERANCE_GRAMS,
        marginGrams: Int = AMBIGUOUS_MARGIN_GRAMS,
    ): ScaleRouting {
        if (grams < MIN_GRAMS || grams > MAX_GRAMS) return ScaleRouting.Unknown
        val near = lastKnownByProfile
            .filterValues { abs(grams - it) <= toleranceGrams }
            .entries
            .sortedWith(compareBy({ abs(grams - it.value) }, { it.key }))
        if (near.isEmpty()) return ScaleRouting.Unknown
        if (near.size == 1) return ScaleRouting.Clear(near.first().key)

        val nearest = abs(grams - near[0].value)
        val next = abs(grams - near[1].value)
        if (next - nearest >= marginGrams) return ScaleRouting.Clear(near.first().key)
        // Everybody who is within the margin of the nearest, so the picker offers the people it
        // genuinely could be rather than the whole household.
        return ScaleRouting.Ambiguous(
            near.filter { abs(grams - it.value) - nearest < marginGrams }.map { it.key },
        )
    }

    /** Whether a reading may be recorded without asking first. */
    fun recordsWithoutAsking(match: ScaleMatch): Boolean =
        match == ScaleMatch.MATCHES || match == ScaleMatch.NO_HISTORY
}
