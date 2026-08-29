package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.FoodOrigin
import com.weighttrack.core.nutrition.Nutrients
import com.weighttrack.data.food.OfflineFoodStore
import com.weighttrack.data.db.WeightTrackDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FoodRepositoryTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var foods: FoodRepository

    private val oats = Food(
        name = "Oats",
        brand = "Quaker",
        per100g = Nutrients(kcal = 379.0, proteinG = 13.2, carbsG = 67.7, fatG = 6.5),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        foods = FoodRepository(database.foodDao(), OfflineFoodStore(ApplicationProvider.getApplicationContext()))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a food is found by name or by brand`() = runTest {
        foods.add(oats)

        // Their own foods only. The bundled shelf answers all three of these as well, which is
        // the point of it, so counting everything back would be counting the shelf.
        assertThat(foods.search("oat").first().mine()).hasSize(1)
        assertThat(foods.search("quaker").first().mine()).hasSize(1)
        assertThat(foods.search("beans").first().mine()).isEmpty()
    }

    /** What this person has saved, as against what ships with the app. */
    private fun List<Food>.mine(): List<Food> = filter { it.id > 0 }

    @Test
    fun `scanning the same tin twice does not make a second copy of it`() = runTest {
        val scanned = oats.copy(barcode = "5000108001111")

        val first = foods.cache(scanned)
        val second = foods.cache(scanned.copy(per100g = scanned.per100g.copy(kcal = 380.0)))

        assertThat(second).isEqualTo(first)
        assertThat(foods.search("oats").first().mine()).hasSize(1)
        // What comes back may be better than what was cached, so the row is refreshed.
        assertThat(foods.byBarcode("5000108001111")!!.per100g.kcal).isWithin(1e-9).of(380.0)
    }

    @Test
    fun `a favourite and when it was last eaten belong to the person, not the database`() =
        runTest {
            val scanned = oats.copy(barcode = "5000108001111")
            val id = foods.cache(scanned)
            foods.setFavourite(id, true)
            foods.markUsed(id, atUtcMillis = 5_000)

            foods.cache(scanned.copy(name = "Oats, rolled"))

            val refreshed = foods.byId(id)!!
            assertThat(refreshed.name).isEqualTo("Oats, rolled")
            assertThat(foods.observeFavourites().first().map { it.id }).containsExactly(id)
            assertThat(foods.observeRecent().first().map { it.id }).containsExactly(id)
        }

    @Test
    fun `recents are what somebody actually eats, newest first`() = runTest {
        val first = foods.add(oats)
        val second = foods.add(oats.copy(name = "Beans"))
        foods.add(oats.copy(name = "Never eaten"))

        foods.markUsed(first, atUtcMillis = 1_000)
        foods.markUsed(second, atUtcMillis = 2_000)

        assertThat(foods.observeRecent().first().map { it.id })
            .containsExactly(second, first)
            .inOrder()
    }

    @Test
    fun `a recipe works out its own nutrition rather than storing it`() = runTest {
        val oatId = foods.add(oats)
        val milk = foods.add(
            Food(name = "Milk", per100g = Nutrients(kcal = 47.0, proteinG = 3.4, fatG = 1.7)),
        )

        val id = foods.saveRecipe(
            name = "Porridge",
            servings = 2,
            items = listOf(
                RecipeItem(foods.byId(oatId)!!, grams = 100.0),
                RecipeItem(foods.byId(milk)!!, grams = 300.0),
            ),
        )

        val recipe = foods.recipeById(id)!!
        assertThat(recipe.totalGrams).isWithin(1e-9).of(400.0)
        assertThat(recipe.total.kcal).isWithin(1e-6).of(379.0 + 47.0 * 3)
        // The total divided by the portions, not scaled by grams, which would be wrong twice.
        assertThat(recipe.perServing.kcal).isWithin(1e-6).of((379.0 + 141.0) / 2)
        assertThat(recipe.servingGrams!!).isWithin(1e-9).of(200.0)
    }

    @Test
    fun `a recipe can be logged like any other food, priced per hundred grams`() = runTest {
        val oatId = foods.add(oats)
        val id = foods.saveRecipe(
            name = "Just oats",
            servings = 1,
            items = listOf(RecipeItem(foods.byId(oatId)!!, grams = 200.0)),
        )

        val asFood = foods.recipeById(id)!!.asFood()

        assertThat(asFood.origin).isEqualTo(FoodOrigin.RECIPE)
        // 200 g of a 379 kcal food is 758 kcal in total, so 379 per hundred grams.
        assertThat(asFood.per100g.kcal).isWithin(1e-6).of(379.0)
    }

    @Test
    fun `editing a recipe replaces what is in it rather than adding to it`() = runTest {
        val oatId = foods.add(oats)
        val id = foods.saveRecipe(
            name = "Porridge",
            servings = 2,
            items = listOf(RecipeItem(foods.byId(oatId)!!, grams = 100.0)),
        )

        foods.saveRecipe(
            name = "Porridge",
            servings = 2,
            items = listOf(RecipeItem(foods.byId(oatId)!!, grams = 50.0)),
            id = id,
        )

        val recipe = foods.recipeById(id)!!
        assertThat(recipe.items).hasSize(1)
        assertThat(recipe.totalGrams).isWithin(1e-9).of(50.0)
    }

    @Test
    fun `an ingredient whose food has gone is dropped, not counted as nothing`() = runTest {
        val oatId = foods.add(oats)
        val beansId = foods.add(oats.copy(name = "Beans"))
        val id = foods.saveRecipe(
            name = "Mix",
            servings = 1,
            items = listOf(
                RecipeItem(foods.byId(oatId)!!, grams = 100.0),
                RecipeItem(foods.byId(beansId)!!, grams = 100.0),
            ),
        )

        foods.delete(foods.byId(beansId)!!)

        // Counting it as zero would quietly make the recipe look lighter than it is.
        val recipe = foods.recipeById(id)!!
        assertThat(recipe.items).hasSize(1)
        assertThat(recipe.totalGrams).isWithin(1e-9).of(100.0)
    }

    @Test
    fun `a food typed in is the person's to change and to delete`() = runTest {
        val id = foods.add(oats)

        foods.update(foods.byId(id)!!.copy(name = "Porridge oats"))

        assertThat(foods.observeCustom().first().single().name).isEqualTo("Porridge oats")
        foods.delete(foods.byId(id)!!)
        assertThat(foods.observeCustom().first()).isEmpty()
    }

    @Test
    fun `the bundled shelf answers a barcode nobody has saved`() = runTest {
        val known = foods.search("chocolate").first().first { it.barcode != null }
        // Straight off the shelf, so no signal and no request. A zero identifier says it is not
        // in this person's foods yet.
        val found = foods.byBarcode(known.barcode!!)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(0L)
    }

    @Test
    fun `their own copy of a product wins over the bundled one`() = runTest {
        val shelf = foods.search("chocolate").first().first { it.barcode != null }
        val mine = shelf.copy(
            name = "My chocolate",
            brand = null,
            per100g = Nutrients(kcal = 111.0),
            origin = FoodOrigin.CUSTOM,
        )
        foods.add(mine)

        // Somebody who has corrected a label expects to see their version, not the packet's.
        val found = foods.byBarcode(shelf.barcode!!)
        assertThat(found!!.name).isEqualTo("My chocolate")
        assertThat(found.per100g.kcal).isEqualTo(111.0)
        assertThat(found.id).isGreaterThan(0L)
    }

    @Test
    fun `a product is not offered twice over`() = runTest {
        val shelf = foods.search("chocolate").first().first { it.barcode != null }
        foods.add(shelf.copy(origin = FoodOrigin.CUSTOM))

        val results = foods.search("chocolate").first()
        // Once as theirs, never again as the bundled copy: the same tin listed twice reads as a
        // duplicate rather than as a choice.
        assertThat(results.count { it.barcode == shelf.barcode }).isEqualTo(1)
        assertThat(results.first().id).isGreaterThan(0L)
    }

    @Test
    fun `their own foods come first`() = runTest {
        foods.add(oats.copy(name = "Chocolate oats"))
        val results = foods.search("chocolate").first()
        assertThat(results.first().name).isEqualTo("Chocolate oats")
        assertThat(results.first().id).isGreaterThan(0L)
    }

    @Test
    fun `the shelf never pushes the search past its limit`() = runTest {
        val results = foods.search("chocolate", limit = 4).first()
        assertThat(results.size).isAtMost(4)
    }
}
