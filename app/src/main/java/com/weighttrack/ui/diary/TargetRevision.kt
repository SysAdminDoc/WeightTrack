package com.weighttrack.ui.diary

import com.weighttrack.core.nutrition.MacroBasis
import com.weighttrack.core.nutrition.MacroTarget
import java.time.DayOfWeek

/**
 * Turning a recommendation into a target.
 *
 * Two decisions, both easy to get wrong and neither visible from the screen: which row the new
 * figure belongs in, and what happens to the macro split that was already there.
 */
object TargetRevision {

    /**
     * Which row to write to.
     *
     * The one the person is looking at. A day with a target of its own is showing that one, and
     * writing the answer into the everyday row instead would replace the target the other six
     * days were using and leave the day on screen exactly as it was, so the button would appear
     * to do nothing at all.
     */
    fun rowFor(day: DayOfWeek, dayHasItsOwn: Boolean): DayOfWeek? = day.takeIf { dayHasItsOwn }

    /**
     * The new target.
     *
     * The split is kept in proportion rather than carried across untouched. Four hundred calories
     * more with the same grams of protein, carbohydrate and fat is a target whose parts no longer
     * add up to its whole, and the diary shows that as a percentage that does not reach a hundred.
     */
    fun revised(existing: MacroTarget?, recommendedKcal: Double): MacroTarget {
        val scale = existing?.kcal?.takeIf { it > 0 }?.let { recommendedKcal / it }
        return MacroTarget(
            kcal = recommendedKcal,
            proteinG = existing?.proteinG?.scaledBy(scale),
            carbsG = existing?.carbsG?.scaledBy(scale),
            fatG = existing?.fatG?.scaledBy(scale),
            basis = existing?.basis ?: MacroBasis.GRAMS,
        )
    }

    private fun Double.scaledBy(scale: Double?): Double = if (scale == null) this else this * scale
}
