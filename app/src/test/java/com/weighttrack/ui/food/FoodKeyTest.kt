package com.weighttrack.ui.food

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.FoodOrigin
import com.weighttrack.core.nutrition.Nutrients
import org.junit.Test

/**
 * The list key, which is not a cosmetic detail.
 *
 * A lazy list handed the same key twice throws rather than drawing anything, so this is the
 * difference between a working Foods screen and a crash the moment the bundled shelf appears.
 */
class FoodKeyTest {

    private fun shelf(barcode: String?, name: String) = Food(
        id = 0,
        name = name,
        barcode = barcode,
        per100g = Nutrients(kcal = 100.0),
        origin = FoodOrigin.OPEN_FOOD_FACTS,
    )

    private fun saved(id: Long, name: String) = Food(
        id = id,
        name = name,
        per100g = Nutrients(kcal = 100.0),
        origin = FoodOrigin.CUSTOM,
    )

    @Test
    fun `a shelf of products all carrying a zero still gets distinct keys`() {
        val list = listOf(
            shelf("5000108001111", "Oats"),
            shelf("5000108002222", "Oats"),
            shelf("5000108003333", "Cornflakes"),
        )
        assertThat(list.map(::foodKey).toSet()).hasSize(3)
    }

    @Test
    fun `a saved food and a shelf one never collide`() {
        // The saved row's identifier is 1 and the shelf row's is 0. Keyed on the number alone
        // these are already different, but "1" and "" are one keystroke apart in a name.
        val list = listOf(saved(1, "Oats"), shelf("1", "Oats"), shelf(null, "Oats"))
        assertThat(list.map(::foodKey).toSet()).hasSize(3)
    }

    @Test
    fun `a food with no barcode is still told apart by its name`() {
        val list = listOf(shelf(null, "Oats"), shelf(null, "Cornflakes"))
        assertThat(list.map(::foodKey).toSet()).hasSize(2)
    }

    @Test
    fun `the key does not change between draws`() {
        val food = shelf("5000108001111", "Oats")
        assertThat(foodKey(food)).isEqualTo(foodKey(food.copy()))
    }
}
