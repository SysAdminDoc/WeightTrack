package com.weighttrack.data.food

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.nutrition.FoodOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the shelf that actually ships, not a fixture of one.
 *
 * That is the point of these: a stand-in database would prove the queries parse and nothing about
 * whether the asset built from the Open Food Facts export is any good, and the asset is the part
 * that arrives on somebody's phone.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineFoodStoreTest {

    private lateinit var store: OfflineFoodStore

    @Before
    fun setUp() {
        store = OfflineFoodStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `the shelf ships with the app`() {
        assertThat(store.available).isTrue()
    }

    @Test
    fun `everyday foods are on it`() = runTest {
        // Breadth rather than a fixed list of products, which would only be testing this month's
        // export. Somebody opening the app in a shop is looking for things like these.
        for (word in listOf("chocolate", "milk", "bread", "yogurt", "rice", "coffee", "cheese")) {
            assertThat(store.search(word)).isNotEmpty()
        }
    }

    @Test
    fun `every product on the shelf is usable`() = runTest {
        val found = store.search("chocolate", limit = 25)
        assertThat(found).isNotEmpty()
        for (food in found) {
            assertThat(food.name).isNotEmpty()
            // Digits only. The export holds internal identifiers that no scanner will ever read.
            assertThat(food.barcode!!.all { it.isDigit() }).isTrue()
            assertThat(food.barcode!!.length).isAtLeast(6)
            assertThat(food.per100g.kcal).isGreaterThan(0.0)
            assertThat(food.per100g.kcal).isLessThan(950.0)
            // Open Food Facts data wherever it was read from, so the licence follows it.
            assertThat(food.origin).isEqualTo(FoodOrigin.OPEN_FOOD_FACTS)
            // Nobody's food table has it yet, and a zero is what says so.
            assertThat(food.id).isEqualTo(0L)
        }
    }

    @Test
    fun `words match in any order`() = runTest {
        val found = store.search("dark chocolate")
        assertThat(found).isNotEmpty()
        for (food in found) {
            val text = "${food.name} ${food.brand.orEmpty()}".lowercase()
            assertThat(text).contains("dark")
            assertThat(text).contains("chocolate")
        }
    }

    @Test
    fun `a barcode found by searching can be looked up again`() = runTest {
        val fromSearch = store.search("chocolate").first()
        val fromBarcode = store.byBarcode(fromSearch.barcode!!)
        assertThat(fromBarcode).isNotNull()
        assertThat(fromBarcode!!.name).isEqualTo(fromSearch.name)
        assertThat(fromBarcode.per100g.kcal).isEqualTo(fromSearch.per100g.kcal)
    }

    @Test
    fun `a barcode that is not on the shelf is not invented`() = runTest {
        // Deliberately not a real product. Answering with something would be worse than nothing,
        // because the app would stop asking Open Food Facts for the real one.
        assertThat(store.byBarcode("0000000000000")).isNull()
        assertThat(store.byBarcode("")).isNull()
        assertThat(store.byBarcode("   ")).isNull()
    }

    @Test
    fun `a query too short to mean anything returns nothing`() = runTest {
        // The whole shelf is not an answer, and these results sit under somebody's own foods.
        assertThat(store.search("")).isEmpty()
        assertThat(store.search("  ")).isEmpty()
        assertThat(store.search("a")).isEmpty()
        assertThat(store.search("ch")).isEmpty()
    }

    @Test
    fun `wildcards typed into the search box are just characters`() = runTest {
        // Unescaped, a percent sign matches every row on the shelf and buries the food somebody
        // was actually looking for.
        assertThat(store.search("%%%")).isEmpty()
        assertThat(store.search("___")).isEmpty()
    }

    @Test
    fun `a percent sign in the middle of a query still finds the product`() = runTest {
        // Standing in front of a tub of yoghurt with no signal. Nearly a thousand products on
        // the shelf have a percent sign in the name, and every one of them was unreachable while
        // the escape clause belonged to the statement instead of to each LIKE: it bound to the
        // last pattern only, and the earlier ones went off hunting for a literal backslash.
        for (query in listOf("0% fat", "100% apple", "70% chocolate")) {
            assertThat(store.search(query)).isNotEmpty()
        }
    }

    @Test
    fun `a percent sign is still not a wildcard when it comes first`() = runTest {
        // The escaping has to survive being applied to every word, not just be present.
        val loose = store.search("0% fat")
        for (food in loose) {
            val text = "${food.name} ${food.brand.orEmpty()}".lowercase()
            assertThat(text).contains("0%")
            assertThat(text).contains("fat")
        }
    }

    @Test
    fun `the limit is kept to`() = runTest {
        assertThat(store.search("chocolate", limit = 3)).hasSize(3)
        assertThat(store.search("chocolate", limit = 1)).hasSize(1)
    }

    @Test
    fun `looking up is not case sensitive`() = runTest {
        val lower = store.search("chocolate").map { it.barcode }
        val upper = store.search("CHOCOLATE").map { it.barcode }
        assertThat(upper).isEqualTo(lower)
    }
}
