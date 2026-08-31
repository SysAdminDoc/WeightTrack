package com.weighttrack.core.nutrition

import kotlin.math.abs
import kotlin.math.max

/** Why a product's numbers cannot be believed. */
enum class NutrientProblem {
    /** A negative amount of something. */
    NEGATIVE,

    /** More energy than a hundred grams of anything can hold. */
    IMPOSSIBLE_ENERGY,

    /** More than a hundred grams of contents in a hundred grams of food. */
    TOO_MUCH_IN_IT,

    /** The energy and the macros describe different foods. */
    ENERGY_DISAGREES,
}

/**
 * Whether a looked-up product's numbers are worth keeping.
 *
 * Open Food Facts is crowdsourced, which is why it exists and why it holds things nobody meant:
 * a per-serving figure typed into a per-hundred-grams field, a decimal point in the wrong place,
 * an energy value with every macro left at zero. The loudest complaint about food databases in
 * open trackers is contradictory numbers, and a wrong number in a diary is worse than a missing
 * one because nothing about it looks wrong.
 *
 * Deliberately generous. Refusing a real product means somebody cannot log their lunch, which is
 * a worse outcome than a figure that is a little off, so only what is impossible or plainly
 * self-contradictory is turned away.
 */
object NutrientPlausibility {

    /** A hundred grams of pure fat. Nothing edible holds more energy than this. */
    const val MAX_KCAL_PER_100G = 950.0

    /** A little over a hundred, because rounded label figures routinely add up to slightly more. */
    const val MAX_CONTENTS_GRAMS = 105.0

    /** How far the energy may sit from what the macros predict. */
    const val TOLERANCE = 0.20

    /**
     * How far it may sit regardless, in kcal.
     *
     * A percentage alone is unfair to a low-energy food: a salad leaf at 15 kcal against a
     * prediction of 11 is off by a third and is perfectly ordinary.
     */
    const val TOLERANCE_FLOOR_KCAL = 30.0

    /** Above this, macros of nothing at all cannot be right whatever the drink is. */
    const val MAX_KCAL_FROM_NOTHING = 400.0

    private const val KCAL_PER_GRAM_PROTEIN = 4.0
    private const val KCAL_PER_GRAM_CARB = 4.0
    private const val KCAL_PER_GRAM_FAT = 9.0
    private const val KCAL_PER_GRAM_ALCOHOL = 7.0

    /**
     * [alcoholG] is grams of alcohol per hundred grams, when the entry says.
     *
     * Worth carrying separately rather than being ignored: a spirit is two hundred and fifty
     * calories of nothing but alcohol, and a check that does not know about it throws away every
     * drink in the database.
     */
    fun problemWith(nutrients: Nutrients, alcoholG: Double? = null): NutrientProblem? {
        val values = listOfNotNull(
            nutrients.kcal,
            nutrients.proteinG,
            nutrients.carbsG,
            nutrients.fatG,
            nutrients.fibreG,
            nutrients.sugarG,
            nutrients.saltG,
            alcoholG,
        )
        if (values.any { it < 0 || it.isNaN() }) return NutrientProblem.NEGATIVE
        if (nutrients.kcal > MAX_KCAL_PER_100G) return NutrientProblem.IMPOSSIBLE_ENERGY

        val protein = nutrients.proteinG
        val carbs = nutrients.carbsG
        val fat = nutrients.fatG
        // Sugar is part of the carbohydrate figure and fibre usually is too, so neither is added
        // again here. Adding them would turn an ordinary loaf into a hundred and thirty grams.
        val contents = listOfNotNull(protein, carbs, fat, alcoholG).sum()
        if (contents > MAX_CONTENTS_GRAMS) return NutrientProblem.TOO_MUCH_IN_IT

        // Only when the entry claims to know all three. Most of the database gives an energy
        // figure and nothing else, and that is not a contradiction, it is a gap.
        if (protein == null || carbs == null || fat == null) return null

        val predicted = protein * KCAL_PER_GRAM_PROTEIN +
            carbs * KCAL_PER_GRAM_CARB +
            fat * KCAL_PER_GRAM_FAT +
            (alcoholG ?: 0.0) * KCAL_PER_GRAM_ALCOHOL
        if (predicted <= 0.0) {
            // Nothing in it at all. Water is fine; four hundred calories of nothing is not.
            return if (nutrients.kcal > MAX_KCAL_FROM_NOTHING) {
                NutrientProblem.ENERGY_DISAGREES
            } else {
                null
            }
        }
        val allowed = max(TOLERANCE * max(nutrients.kcal, predicted), TOLERANCE_FLOOR_KCAL)
        return if (abs(nutrients.kcal - predicted) > allowed) {
            NutrientProblem.ENERGY_DISAGREES
        } else {
            null
        }
    }

    fun isBelievable(nutrients: Nutrients, alcoholG: Double? = null): Boolean =
        problemWith(nutrients, alcoholG) == null
}
