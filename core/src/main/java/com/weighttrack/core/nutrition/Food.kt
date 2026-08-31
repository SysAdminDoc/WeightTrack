package com.weighttrack.core.nutrition

import kotlin.math.roundToInt

/**
 * What a food is made of, always per 100 grams.
 *
 * One basis for everything, decided once, because the alternative is a label saying "per
 * serving", another saying "per 100 ml" and a total that quietly means nothing. A serving is a
 * weight, held on the food, and the arithmetic happens at the point of logging.
 *
 * Everything but energy is optional. Plenty of entries in a crowdsourced database carry a
 * calorie figure and nothing else, and a missing macro is not zero grams of it.
 */
data class Nutrients(
    val kcal: Double,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val fibreG: Double? = null,
    val sugarG: Double? = null,
    val saltG: Double? = null,
) {
    /** This food scaled to a real amount. */
    fun forGrams(grams: Double): Nutrients {
        val factor = grams / 100.0
        return Nutrients(
            kcal = kcal * factor,
            proteinG = proteinG?.times(factor),
            carbsG = carbsG?.times(factor),
            fatG = fatG?.times(factor),
            fibreG = fibreG?.times(factor),
            sugarG = sugarG?.times(factor),
            saltG = saltG?.times(factor),
        )
    }

    operator fun plus(other: Nutrients): Nutrients = Nutrients(
        kcal = kcal + other.kcal,
        proteinG = add(proteinG, other.proteinG),
        carbsG = add(carbsG, other.carbsG),
        fatG = add(fatG, other.fatG),
        fibreG = add(fibreG, other.fibreG),
        sugarG = add(sugarG, other.sugarG),
        saltG = add(saltG, other.saltG),
    )

    /**
     * Adding a known amount to an unknown one gives the known amount, not null.
     *
     * A day with one food whose protein nobody recorded still has the protein from everything
     * else in it, and reporting that as "unknown" would throw away what is known. It stays null
     * only while nothing at all has been recorded.
     */
    private fun add(a: Double?, b: Double?): Double? = when {
        a == null && b == null -> null
        else -> (a ?: 0.0) + (b ?: 0.0)
    }

    companion object {
        val NONE = Nutrients(kcal = 0.0)
    }
}

/** Where a food came from, which decides whether it may be edited and what has to be credited. */
enum class FoodOrigin {
    /** Typed in by the person using the app. Theirs to change. */
    CUSTOM,

    /** Open Food Facts. Shared under ODbL, so it carries an attribution wherever it is shown. */
    OPEN_FOOD_FACTS,

    /** USDA FoodData Central, which is public domain. */
    USDA,

    /** Worked out from a recipe's ingredients rather than stored. */
    RECIPE,
}

/**
 * One thing that can be eaten.
 *
 * A barcode is not the identity: two brands share a code often enough, and a custom food has
 * none at all.
 */
data class Food(
    val id: Long = 0,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val per100g: Nutrients,
    /**
     * What one serving weighs, when the label says.
     *
     * Null means the only honest way to log it is by weight, which is what a scale is for.
     */
    val servingGrams: Double? = null,
    val origin: FoodOrigin = FoodOrigin.CUSTOM,
    /**
     * When this was last read from the service it came from, for a product that came from one.
     *
     * Zero means never, which is every food somebody typed in themselves. A label changes and a
     * cached product does not: kept once, a tin scanned two years ago goes on reporting the
     * recipe it had then, and nothing on the screen says how old the numbers are.
     */
    val fetchedAtUtcMillis: Long = 0,
) {
    /** What the label calls it, brand and all. */
    val label: String
        get() = brand?.takeIf { it.isNotBlank() }?.let { "$name ($it)" } ?: name

    fun forGrams(grams: Double): Nutrients = per100g.forGrams(grams)

    /** Rounded calories for an amount, which is what a log line shows. */
    fun kcalForGrams(grams: Double): Int = per100g.forGrams(grams).kcal.roundToInt()
}
