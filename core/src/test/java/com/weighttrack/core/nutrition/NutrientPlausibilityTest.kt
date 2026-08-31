package com.weighttrack.core.nutrition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which crowdsourced numbers are worth keeping.
 *
 * Open Food Facts holds things nobody meant: a per-serving figure typed into a per-hundred-grams
 * field, a decimal point in the wrong place, an energy value with every macro left at zero. A
 * wrong number in somebody's diary is worse than a missing one, because nothing about it looks
 * wrong. The other half of the job is not turning away real food, so most of this is about
 * products that must go through.
 */
class NutrientPlausibilityTest {

    @Test
    fun `energy with no macros behind it at all is refused`() {
        // The case the item names: nine hundred calories of nothing.
        val nonsense = Nutrients(kcal = 900.0, proteinG = 0.0, carbsG = 0.0, fatG = 0.0)

        assertThat(NutrientPlausibility.problemWith(nonsense))
            .isEqualTo(NutrientProblem.ENERGY_DISAGREES)
    }

    @Test
    fun `a decimal point in the wrong place is refused`() {
        // Oats at 379 kcal with the carbohydrate typed as 6.77 instead of 67.7. The macros then
        // account for 138 calories and the label says 379.
        val slipped = Nutrients(kcal = 379.0, proteinG = 13.2, carbsG = 6.77, fatG = 6.5)

        assertThat(NutrientPlausibility.problemWith(slipped))
            .isEqualTo(NutrientProblem.ENERGY_DISAGREES)

        // The same slip the other way puts more than a hundred grams into a hundred grams, which
        // is a different thing to notice and is noticed first.
        val overflowing = Nutrients(kcal = 379.0, proteinG = 13.2, carbsG = 67.7, fatG = 65.0)
        assertThat(NutrientPlausibility.problemWith(overflowing))
            .isEqualTo(NutrientProblem.TOO_MUCH_IN_IT)
    }

    @Test
    fun `more than a hundred grams inside a hundred grams is refused`() {
        val impossible = Nutrients(kcal = 500.0, proteinG = 40.0, carbsG = 40.0, fatG = 40.0)

        assertThat(NutrientPlausibility.problemWith(impossible))
            .isEqualTo(NutrientProblem.TOO_MUCH_IN_IT)
    }

    @Test
    fun `more energy than pure fat holds is refused`() {
        assertThat(NutrientPlausibility.problemWith(Nutrients(kcal = 4_000.0)))
            .isEqualTo(NutrientProblem.IMPOSSIBLE_ENERGY)
    }

    @Test
    fun `a negative anything is refused`() {
        assertThat(NutrientPlausibility.problemWith(Nutrients(kcal = -1.0)))
            .isEqualTo(NutrientProblem.NEGATIVE)
        assertThat(NutrientPlausibility.problemWith(Nutrients(kcal = 100.0, proteinG = -2.0)))
            .isEqualTo(NutrientProblem.NEGATIVE)
    }

    @Test
    fun `real food goes through`() {
        val real = listOf(
            // Oats, olive oil, semi-skimmed milk, chicken breast, a cucumber.
            Nutrients(kcal = 379.0, proteinG = 13.2, carbsG = 67.7, fatG = 6.5),
            Nutrients(kcal = 884.0, proteinG = 0.0, carbsG = 0.0, fatG = 100.0),
            Nutrients(kcal = 47.0, proteinG = 3.4, carbsG = 4.8, fatG = 1.7),
            Nutrients(kcal = 165.0, proteinG = 31.0, carbsG = 0.0, fatG = 3.6),
            Nutrients(kcal = 15.0, proteinG = 0.7, carbsG = 3.6, fatG = 0.1),
        )

        real.forEach { assertThat(NutrientPlausibility.problemWith(it)).isNull() }
    }

    @Test
    fun `a drink is not thrown away for being mostly alcohol`() {
        // A check that does not know about alcohol throws away every drink in the database: a
        // spirit is two hundred and fifty calories of nothing else at all.
        val gin = Nutrients(kcal = 263.0, proteinG = 0.0, carbsG = 0.0, fatG = 0.0)
        assertThat(NutrientPlausibility.problemWith(gin, alcoholG = 37.5)).isNull()

        val beer = Nutrients(kcal = 43.0, proteinG = 0.5, carbsG = 3.6, fatG = 0.0)
        assertThat(NutrientPlausibility.problemWith(beer, alcoholG = 3.9)).isNull()

        // And the same drink with the alcohol left out of the entry is still not refused, because
        // its energy is below the point where nothing at all cannot explain it.
        assertThat(NutrientPlausibility.problemWith(beer)).isNull()
    }

    @Test
    fun `water and a diet drink are not contradictions`() {
        assertThat(
            NutrientPlausibility.problemWith(
                Nutrients(kcal = 0.0, proteinG = 0.0, carbsG = 0.0, fatG = 0.0),
            ),
        ).isNull()
        assertThat(
            NutrientPlausibility.problemWith(
                Nutrients(kcal = 1.0, proteinG = 0.0, carbsG = 0.1, fatG = 0.0),
            ),
        ).isNull()
    }

    @Test
    fun `an entry that only knows its energy is a gap rather than a contradiction`() {
        // Most of the database is like this, and refusing it would empty the app.
        assertThat(NutrientPlausibility.problemWith(Nutrients(kcal = 250.0))).isNull()
        assertThat(NutrientPlausibility.problemWith(Nutrients(kcal = 250.0, proteinG = 5.0)))
            .isNull()
    }

    @Test
    fun `a low-energy food is not refused for being a third out`() {
        // Eleven predicted against fifteen measured is a third, and a lettuce leaf.
        val leaf = Nutrients(kcal = 15.0, proteinG = 0.7, carbsG = 2.0, fatG = 0.1)

        assertThat(NutrientPlausibility.problemWith(leaf)).isNull()
    }
}
