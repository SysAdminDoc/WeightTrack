package com.weighttrack.core.nutrition

import java.time.DayOfWeek
import kotlin.math.roundToInt

/** How somebody prefers to say what they are aiming for. */
enum class MacroBasis {
    /** So many grams of each, which is how lifting and medical advice is usually written. */
    GRAMS,

    /** A share of the day's calories, which is how most diets are described. */
    PERCENT,
}

/**
 * What a day is meant to come to.
 *
 * Calories are the target; the macros are a split of them. Stored as grams whatever the person
 * typed, because grams are what a food is measured in and a percentage of a calorie figure that
 * later changes would silently mean something different.
 */
data class MacroTarget(
    val kcal: Double,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    /** Only how it is shown and edited. What is stored is always grams. */
    val basis: MacroBasis = MacroBasis.GRAMS,
    /** Null for the target that applies to any day without one of its own. */
    val day: DayOfWeek? = null,
) {
    /** The share of the day's calories each macro accounts for. */
    fun percentOf(grams: Double?, kcalPerGram: Double): Double? =
        percentFromGrams(kcal, grams, kcalPerGram)

    val proteinPercent: Double? get() = percentOf(proteinG, KCAL_PER_GRAM_PROTEIN)
    val carbsPercent: Double? get() = percentOf(carbsG, KCAL_PER_GRAM_CARBS)
    val fatPercent: Double? get() = percentOf(fatG, KCAL_PER_GRAM_FAT)

    /**
     * What the macros add up to against the calorie target.
     *
     * Worth showing rather than enforcing. A split that comes to 95% is not wrong, and one that
     * comes to 130% is worth knowing about before the week rather than after it.
     */
    val macroKcal: Double
        get() = (proteinG ?: 0.0) * KCAL_PER_GRAM_PROTEIN +
            (carbsG ?: 0.0) * KCAL_PER_GRAM_CARBS +
            (fatG ?: 0.0) * KCAL_PER_GRAM_FAT

    val macroPercent: Double? get() = if (kcal <= 0) null else macroKcal / kcal * 100.0

    /** What is left of the day, which is the number people actually look at. */
    fun remaining(eaten: Nutrients): Remaining = Remaining(
        kcal = kcal - eaten.kcal,
        proteinG = proteinG?.let { it - (eaten.proteinG ?: 0.0) },
        carbsG = carbsG?.let { it - (eaten.carbsG ?: 0.0) },
        fatG = fatG?.let { it - (eaten.fatG ?: 0.0) },
    )

    companion object {
        const val KCAL_PER_GRAM_PROTEIN = 4.0
        const val KCAL_PER_GRAM_CARBS = 4.0
        const val KCAL_PER_GRAM_FAT = 9.0

        /** Grams from a share of a calorie target, which is what the percent editor produces. */
        fun gramsFromPercent(kcal: Double, percent: Double?, kcalPerGram: Double): Double? {
            if (percent == null || kcal <= 0) return null
            return kcal * percent / 100.0 / kcalPerGram
        }

        /**
         * The other direction, for moving an editor between the two ways of saying it.
         *
         * The pair matters more than either half. Switching the editor from grams to shares and
         * leaving the numbers where they were reads 150 grams of protein as 150 per cent of the
         * day, which is 750 grams once it is stored.
         */
        fun percentFromGrams(kcal: Double, grams: Double?, kcalPerGram: Double): Double? {
            if (grams == null || kcal <= 0) return null
            return grams * kcalPerGram / kcal * 100.0
        }

        /**
         * A split that is not far off a hundred, filled in from the other two.
         *
         * Somebody who sets protein and fat has decided the carbohydrate as well, whether or not
         * they say so, and making them work it out is arithmetic the app can do.
         */
        fun completeCarbs(kcal: Double, proteinG: Double?, fatG: Double?): Double? {
            if (kcal <= 0) return null
            val used = (proteinG ?: 0.0) * KCAL_PER_GRAM_PROTEIN + (fatG ?: 0.0) * KCAL_PER_GRAM_FAT
            val left = kcal - used
            return if (left <= 0) 0.0 else left / KCAL_PER_GRAM_CARBS
        }
    }
}

/** What is left of a day's target. Negative means over it, which is a fact and not a telling-off. */
data class Remaining(
    val kcal: Double,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
) {
    val isOver: Boolean get() = kcal < 0

    /** Rounded, because a target is not accurate to a tenth of a calorie and pretending is silly. */
    val kcalRounded: Int get() = kcal.roundToInt()
}

/**
 * A person's targets: one for most days, and any number of exceptions.
 *
 * Different days genuinely want different numbers. Eating the same on a rest day as on a long
 * run is the thing per-day targets exist for.
 */
data class MacroTargets(
    val default: MacroTarget?,
    val byDay: Map<DayOfWeek, MacroTarget> = emptyMap(),
) {
    /** The target that applies on a given day, which is its own if it has one. */
    fun forDay(day: DayOfWeek): MacroTarget? = byDay[day] ?: default

    val hasAny: Boolean get() = default != null || byDay.isNotEmpty()

    companion object {
        val NONE = MacroTargets(default = null)
    }
}
