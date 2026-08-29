package com.weighttrack.core.nutrition

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UsdaFoodDataClientTest {

    private val requested = mutableListOf<String>()
    private var response: String? = null
    private var key: String? = "abc123"
    private var clock = 0L

    private fun client() = UsdaFoodDataClient(
        fetch = { url, _ -> requested += url; response },
        userAgent = "WeightTrack/0.3.1",
        apiKey = { key },
        now = { clock },
    )

    private val potato = """
        {
          "foods": [
            {
              "description": "Potatoes, raw",
              "brandOwner": null,
              "foodNutrients": [
                {"nutrientId": 1008, "value": 77.0},
                {"nutrientId": 1003, "value": 2.05},
                {"nutrientId": 1004, "value": 0.09},
                {"nutrientId": 1005, "value": 17.5},
                {"nutrientId": 1079, "value": 2.1},
                {"nutrientId": 2000, "value": 0.82},
                {"nutrientId": 1093, "value": 6.0}
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `an ingredient search reads the nutrients by their identifiers`() = runTest {
        response = potato

        val found = (client().search("potato") as UsdaFoodDataClient.Result.Found).value

        assertThat(requested.single()).startsWith("https://api.nal.usda.gov/fdc/v1/foods/search?query=potato")
        assertThat(requested.single()).contains("api_key=abc123")

        val food = found.single()
        assertThat(food.name).isEqualTo("Potatoes, raw")
        assertThat(food.origin).isEqualTo(FoodOrigin.USDA)
        assertThat(food.per100g.kcal).isWithin(1e-9).of(77.0)
        assertThat(food.per100g.proteinG!!).isWithin(1e-9).of(2.05)
        assertThat(food.per100g.fibreG!!).isWithin(1e-9).of(2.1)
        // Reported as sodium in milligrams; salt is sodium times 2.5, a thousand to the gram.
        assertThat(food.per100g.saltG!!).isWithin(1e-9).of(6.0 * 2.5 / 1000.0)
    }

    @Test
    fun `no key means nothing is asked for, rather than a request that will be refused`() = runTest {
        key = null

        assertThat(client().search("potato")).isInstanceOf(UsdaFoodDataClient.Result.NoKey::class.java)
        assertThat(requested).isEmpty()

        key = "   "
        assertThat(client().search("potato")).isInstanceOf(UsdaFoodDataClient.Result.NoKey::class.java)
        assertThat(requested).isEmpty()
    }

    @Test
    fun `a food with no energy is not something this app can log`() = runTest {
        response = """{"foods":[{"description":"Water","foodNutrients":[]}]}"""

        val found = (client().search("water") as UsdaFoodDataClient.Result.Found).value

        assertThat(found).isEmpty()
    }

    @Test
    fun `an empty search costs nothing`() = runTest {
        val found = (client().search("  ") as UsdaFoodDataClient.Result.Found).value

        assertThat(found).isEmpty()
        assertThat(requested).isEmpty()
    }

    @Test
    fun `the allowance is kept and comes back`() = runTest {
        response = potato
        val client = client()

        repeat(UsdaFoodDataClient.REQUESTS_PER_MINUTE) { client.search("potato") }

        assertThat(client.search("potato"))
            .isInstanceOf(UsdaFoodDataClient.Result.RateLimited::class.java)
        clock += RateLimiter.WINDOW_MILLIS
        assertThat(client.search("potato"))
            .isInstanceOf(UsdaFoodDataClient.Result.Found::class.java)
    }

    @Test
    fun `nothing back, or nonsense back, is not an empty result`() = runTest {
        response = null
        assertThat(client().search("potato"))
            .isInstanceOf(UsdaFoodDataClient.Result.Unreachable::class.java)

        response = "<html>service unavailable</html>"
        assertThat(client().search("potato"))
            .isInstanceOf(UsdaFoodDataClient.Result.Unreachable::class.java)
    }

    @Test
    fun `the app says where to get a key rather than shipping one`() {
        // A key inside an open source app is a shared quota in a public repository.
        assertThat(UsdaFoodDataClient.KEY_SIGNUP_URL).startsWith("https://")
        assertThat(UsdaFoodDataClient.ATTRIBUTION).contains("public domain")
    }
}
