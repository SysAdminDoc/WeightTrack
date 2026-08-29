package com.weighttrack.ui.food

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.FoodOrigin
import com.weighttrack.core.nutrition.Nutrients
import org.junit.Test

/**
 * The Open Database Licence asks for credit wherever the data is shown. This decides where that
 * is, so getting it wrong is a licence problem rather than a cosmetic one.
 */
class OpenFoodFactsCreditTest {

    private fun food(id: Long, origin: FoodOrigin) = Food(
        id = id,
        name = "Oats",
        per100g = Nutrients(kcal = 379.0),
        origin = origin,
    )

    @Test
    fun `a product straight off the bundled shelf is credited`() {
        assertThat(needsOpenFoodFactsCredit(listOf(food(0, FoodOrigin.OPEN_FOOD_FACTS)))).isTrue()
    }

    @Test
    fun `a product kept from the shelf is still credited`() {
        // The whole defect. Keeping one gives it an identifier of its own, and a check for "not
        // saved yet" credited the licence right up until the moment it stopped being true. The
        // scanner keeps products without anybody deciding to, so the Foods screen most people
        // see most often was a list of Open Food Facts data with no credit on it.
        assertThat(needsOpenFoodFactsCredit(listOf(food(7, FoodOrigin.OPEN_FOOD_FACTS)))).isTrue()
    }

    @Test
    fun `somebody's own foods need no credit`() {
        val mine = listOf(
            food(1, FoodOrigin.CUSTOM),
            food(2, FoodOrigin.RECIPE),
            food(3, FoodOrigin.USDA),
        )
        assertThat(needsOpenFoodFactsCredit(mine)).isFalse()
    }

    @Test
    fun `one Open Food Facts row among their own is enough`() {
        val mixed = listOf(food(1, FoodOrigin.CUSTOM), food(9, FoodOrigin.OPEN_FOOD_FACTS))
        assertThat(needsOpenFoodFactsCredit(mixed)).isTrue()
    }

    @Test
    fun `an empty list credits nothing`() {
        assertThat(needsOpenFoodFactsCredit(emptyList())).isFalse()
    }
}
