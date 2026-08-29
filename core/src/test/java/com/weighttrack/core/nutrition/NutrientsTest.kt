package com.weighttrack.core.nutrition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NutrientsTest {

    private val oats = Nutrients(kcal = 379.0, proteinG = 13.2, carbsG = 67.7, fatG = 6.5, fibreG = 10.1)

    @Test
    fun `a food scales to the amount actually eaten`() {
        val bowl = oats.forGrams(40.0)

        assertThat(bowl.kcal).isWithin(1e-9).of(151.6)
        assertThat(bowl.proteinG!!).isWithin(1e-9).of(5.28)
        assertThat(bowl.fibreG!!).isWithin(1e-9).of(4.04)
    }

    @Test
    fun `an unrecorded macro stays unrecorded rather than becoming zero`() {
        // Plenty of entries in a crowdsourced database carry calories and nothing else, and
        // zero grams of protein is a claim about the food that nobody made.
        val onlyCalories = Nutrients(kcal = 250.0)

        assertThat(onlyCalories.forGrams(50.0).proteinG).isNull()
        assertThat(onlyCalories.forGrams(50.0).kcal).isWithin(1e-9).of(125.0)
    }

    @Test
    fun `a day adds up`() {
        val total = oats.forGrams(40.0) + Nutrients(kcal = 90.0, proteinG = 0.5, carbsG = 23.0)

        assertThat(total.kcal).isWithin(1e-9).of(241.6)
        assertThat(total.proteinG!!).isWithin(1e-9).of(5.78)
    }

    @Test
    fun `what is known survives being added to what is not`() {
        // A day with one food whose protein nobody recorded still has the protein from
        // everything else in it. Reporting the total as unknown throws that away.
        val known = Nutrients(kcal = 100.0, proteinG = 10.0)
        val unknown = Nutrients(kcal = 50.0)

        assertThat((known + unknown).proteinG!!).isWithin(1e-9).of(10.0)
        assertThat((unknown + known).proteinG!!).isWithin(1e-9).of(10.0)
        // Only when nothing at all has been recorded is it still nothing.
        assertThat((unknown + unknown).proteinG).isNull()
    }

    @Test
    fun `a brand is part of what to call a food, when there is one`() {
        assertThat(Food(name = "Oats", brand = "Quaker", per100g = oats).label)
            .isEqualTo("Oats (Quaker)")
        assertThat(Food(name = "Oats", per100g = oats).label).isEqualTo("Oats")
        assertThat(Food(name = "Oats", brand = "   ", per100g = oats).label).isEqualTo("Oats")
    }

    @Test
    fun `a log line shows whole calories`() {
        assertThat(Food(name = "Oats", per100g = oats).kcalForGrams(40.0)).isEqualTo(152)
    }
}
