package com.weighttrack.core.nutrition

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OpenFoodFactsClientTest {

    private val requested = mutableListOf<Pair<String, String>>()
    private var response: String? = null
    private var clock = 0L

    private fun client(
        productLimiter: RateLimiter = RateLimiter(RateLimiter.PRODUCT_READS_PER_MINUTE),
        searchLimiter: RateLimiter = RateLimiter(RateLimiter.SEARCHES_PER_MINUTE),
    ) = OpenFoodFactsClient(
        fetch = { url, agent -> requested += url to agent; response },
        userAgent = OpenFoodFactsClient.userAgent("0.4.0"),
        now = { clock },
        productLimiter = productLimiter,
        searchLimiter = searchLimiter,
    )

    /** Trimmed from a real response for barcode 3017624010701. */
    private val nutella = """
        {
          "code": "3017624010701",
          "status": "success",
          "product": {
            "product_name": "Nutella",
            "brands": "Ferrero, Nutella",
            "code": "3017624010701",
            "serving_quantity": "15",
            "nutriments": {
              "energy-kcal_100g": 539,
              "proteins_100g": 6.3,
              "carbohydrates_100g": 57.5,
              "fat_100g": 30.9,
              "sugars_100g": 56.3,
              "salt_100g": 0.107
            }
          }
        }
    """.trimIndent()

    @Test
    fun `a product read asks for only the fields it uses`() = runTest {
        response = nutella

        val result = client().byBarcode("3017624010701")

        val (url, agent) = requested.single()
        assertThat(url).startsWith("https://world.openfoodfacts.org/api/v3/product/3017624010701.json")
        // The full product record is enormous and this runs on a phone, on mobile data, against
        // a service that counts requests.
        assertThat(url).contains("fields=product_name,brands,code,serving_quantity,serving_size,nutriments")
        // The service asks every caller to identify itself, and blocks the ones that do not.
        assertThat(agent).startsWith("WeightTrack/0.4.0 (")

        val food = (result as OpenFoodFactsClient.Result.Found).value
        assertThat(food.name).isEqualTo("Nutella")
        // Only the first brand, because "Ferrero, Nutella" is a list, not a name.
        assertThat(food.brand).isEqualTo("Ferrero")
        assertThat(food.barcode).isEqualTo("3017624010701")
        assertThat(food.origin).isEqualTo(FoodOrigin.OPEN_FOOD_FACTS)
        assertThat(food.per100g.kcal).isWithin(1e-9).of(539.0)
        assertThat(food.per100g.proteinG!!).isWithin(1e-9).of(6.3)
        assertThat(food.per100g.saltG!!).isWithin(1e-9).of(0.107)
        // The serving arrives as a string on this endpoint and a number on the other.
        assertThat(food.servingGrams!!).isWithin(1e-9).of(15.0)
    }

    @Test
    fun `kilojoules are a unit, not a different measurement`() = runTest {
        response = """
            {"status":"success","product":{"product_name":"Oats","nutriments":{"energy-kj_100g":1560}}}
        """.trimIndent()

        val food = (client().byBarcode("1") as OpenFoodFactsClient.Result.Found).value

        assertThat(food.per100g.kcal).isWithin(0.1).of(1560 / 4.184)
    }

    @Test
    fun `a product with no energy at all is not a food this app can log`() = runTest {
        response = """{"status":"success","product":{"product_name":"Mystery","nutriments":{}}}"""

        assertThat(client().byBarcode("1")).isInstanceOf(OpenFoodFactsClient.Result.NotFound::class.java)
    }

    @Test
    fun `an unknown barcode and a nonsense one are both simply not found`() = runTest {
        response = """{"status":"failure","product":null}"""
        assertThat(client().byBarcode("0000")).isInstanceOf(OpenFoodFactsClient.Result.NotFound::class.java)

        requested.clear()
        assertThat(client().byBarcode("not-a-barcode"))
            .isInstanceOf(OpenFoodFactsClient.Result.NotFound::class.java)
        // A barcode that cannot be one is not worth a request against a counted allowance.
        assertThat(requested).isEmpty()
    }

    @Test
    fun `nothing back from the network is not the same as no such product`() = runTest {
        response = null

        assertThat(client().byBarcode("3017624010701"))
            .isInstanceOf(OpenFoodFactsClient.Result.Unreachable::class.java)
    }

    @Test
    fun `the published limit is kept rather than tested by trying`() = runTest {
        response = nutella
        val client = client()

        repeat(RateLimiter.PRODUCT_READS_PER_MINUTE) {
            assertThat(client.byBarcode("3017624010701"))
                .isInstanceOf(OpenFoodFactsClient.Result.Found::class.java)
        }

        // Going over is what gets an address banned, so the sixteenth never leaves the phone.
        val over = client.byBarcode("3017624010701")
        assertThat(over).isInstanceOf(OpenFoodFactsClient.Result.RateLimited::class.java)
        assertThat((over as OpenFoodFactsClient.Result.RateLimited).retryInMillis).isGreaterThan(0)
        assertThat(requested).hasSize(RateLimiter.PRODUCT_READS_PER_MINUTE)

        // A minute later the allowance is back.
        clock += RateLimiter.WINDOW_MILLIS
        assertThat(client.byBarcode("3017624010701"))
            .isInstanceOf(OpenFoodFactsClient.Result.Found::class.java)
    }

    @Test
    fun `searching and reading a product have separate allowances`() = runTest {
        response = """{"hits":[]}"""
        val client = client()

        repeat(RateLimiter.SEARCHES_PER_MINUTE) { client.search("oats") }
        assertThat(client.search("oats"))
            .isInstanceOf(OpenFoodFactsClient.Result.RateLimited::class.java)

        // The product allowance is untouched, because the service counts them separately.
        response = nutella
        assertThat(client.byBarcode("3017624010701"))
            .isInstanceOf(OpenFoodFactsClient.Result.Found::class.java)
    }

    @Test
    fun `free text goes to the search service, which is a different one`() = runTest {
        response = """
            {
              "count": 1,
              "hits": [
                {
                  "code": "0009800800049",
                  "product_name": "Nutella and go",
                  "brands": "Nutella",
                  "nutriments": {"energy-kcal_100g": 539, "fiber_100g": 3.2}
                },
                {"code": "1", "product_name": "", "nutriments": {"energy-kcal_100g": 100}}
              ]
            }
        """.trimIndent()

        val found = (client().search("nutella spread") as OpenFoodFactsClient.Result.Found).value

        // The v2 and v3 APIs deliberately do not do free text at all.
        assertThat(requested.single().first).startsWith("https://search.openfoodfacts.org/search?q=")
        assertThat(requested.single().first).contains("nutella+spread")
        // A hit with no name is not something anybody can pick off a list.
        assertThat(found).hasSize(1)
        assertThat(found.single().name).isEqualTo("Nutella and go")
        assertThat(found.single().per100g.fibreG!!).isWithin(1e-9).of(3.2)
    }

    @Test
    fun `an empty search is not a request`() = runTest {
        val found = (client().search("   ") as OpenFoodFactsClient.Result.Found).value

        assertThat(found).isEmpty()
        assertThat(requested).isEmpty()
    }

    @Test
    fun `a body that is not json does not bring the search down`() = runTest {
        response = "<html>upstream is having a moment</html>"

        assertThat(client().search("oats"))
            .isInstanceOf(OpenFoodFactsClient.Result.Unreachable::class.java)
    }

    @Test
    fun `the licence is credited in words the app can show`() {
        assertThat(OpenFoodFactsClient.ATTRIBUTION).contains("Open Food Facts")
        assertThat(OpenFoodFactsClient.ATTRIBUTION).contains("Open Database Licence")
    }
}
