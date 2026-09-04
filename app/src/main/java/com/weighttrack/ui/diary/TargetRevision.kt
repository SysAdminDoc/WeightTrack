package com.weighttrack.ui.diary

import com.weighttrack.core.nutrition.MacroBasis
import com.weighttrack.core.nutrition.MacroTarget
import java.time.DayOfWeek
import kotlin.math.roundToInt

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
     * One macro field moved between grams and shares of the day.
     *
     * The editor holds whichever the person is looking at, so changing which that is has to
     * change the numbers with it. Left alone, 150 grams of protein becomes 150 per cent of the
     * day the moment the chip is tapped, and saving stores 750 grams.
     *
     * Text in and text out, because that is what the field holds, and anything unreadable comes
     * back untouched: a half-typed number is not a reason to throw away what somebody typed.
     */
    fun movedTo(
        text: String,
        basis: MacroBasis,
        kcal: Double?,
        kcalPerGram: Double,
        read: (String) -> Double?,
    ): String {
        val value = read(text) ?: return text
        if (kcal == null || kcal <= 0) return text
        val moved = if (basis == MacroBasis.PERCENT) {
            MacroTarget.percentFromGrams(kcal, value, kcalPerGram)
        } else {
            MacroTarget.gramsFromPercent(kcal, value, kcalPerGram)
        }
        return moved?.roundToInt()?.toString() ?: text
    }

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
