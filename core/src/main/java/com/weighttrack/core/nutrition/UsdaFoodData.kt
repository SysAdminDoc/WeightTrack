package com.weighttrack.core.nutrition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads USDA FoodData Central.
 *
 * Worth having alongside Open Food Facts because it covers plain ingredients, which a barcode
 * database does not: a crowdsourced product database knows about a tin of beans and knows
 * nothing about a potato.
 *
 * It needs an API key, and the key belongs to whoever is using the app. Shipping one inside an
 * open source app would put a shared quota in a public repository, so this asks for one and
 * says where to get it. The data itself is public domain, so nothing has to be credited, but
 * saying where it came from is honest and costs nothing.
 */
class UsdaFoodDataClient(
    private val fetch: suspend (url: String, userAgent: String) -> String?,
    private val userAgent: String,
    private val apiKey: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val limiter: RateLimiter = RateLimiter(REQUESTS_PER_MINUTE),
) {
    sealed interface Result<out T> {
        data class Found<T>(val value: T) : Result<T>
        data object NotFound : Result<Nothing>
        /** No key has been entered, which is not a failure so much as a thing left undone. */
        data object NoKey : Result<Nothing>
        data class RateLimited(val retryInMillis: Long) : Result<Nothing>
        data object Unreachable : Result<Nothing>
    }

    suspend fun search(query: String, pageSize: Int = DEFAULT_PAGE_SIZE): Result<List<Food>> {
        val text = query.trim()
        if (text.isEmpty()) return Result.Found(emptyList())
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return Result.NoKey
        if (!limiter.tryAcquire(now())) return Result.RateLimited(limiter.waitMillis(now()))

        val url = "$BASE/v1/foods/search?query=${encode(text)}" +
            "&pageSize=${pageSize.coerceIn(1, 50)}&api_key=${encode(key)}"
        val body = fetch(url, userAgent) ?: return Result.Unreachable
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Result.Unreachable
        val foods = root["foods"]?.jsonArray ?: return Result.Found(emptyList())
        return Result.Found(foods.mapNotNull { foodOf(it.jsonObject) })
    }

    private fun foodOf(food: JsonObject): Food? {
        val name = food["description"]?.jsonPrimitive?.contentOrNull()?.trim()
        if (name.isNullOrEmpty()) return null
        // Everything here is already per hundred grams, which is the one thing that makes this
        // database easy to work with.
        val nutrients = food["foodNutrients"]?.jsonArray.orEmpty()
            .mapNotNull { entry ->
                val obj = entry.jsonObject
                val id = obj["nutrientId"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: return@mapNotNull null
                val value = obj["value"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                id to value
            }
            .toMap()

        val kcal = nutrients[ENERGY_KCAL] ?: return null
        return Food(
            name = name,
            brand = food["brandOwner"]?.jsonPrimitive?.contentOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            barcode = food["gtinUpc"]?.jsonPrimitive?.contentOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            per100g = Nutrients(
                kcal = kcal,
                proteinG = nutrients[PROTEIN],
                carbsG = nutrients[CARBS],
                fatG = nutrients[FAT],
                fibreG = nutrients[FIBRE],
                sugarG = nutrients[SUGAR],
                // Reported as sodium in milligrams. Salt is sodium times 2.5, and a thousand
                // milligrams to the gram.
                saltG = nutrients[SODIUM_MG]?.let { it * SALT_PER_SODIUM / 1000.0 },
            ),
            origin = FoodOrigin.USDA,
        )
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()?.takeIf { it != "null" }

    private fun encode(text: String): String =
        text.replace(" ", "%20").filter { it.isLetterOrDigit() || it in "%-_.~" }

    companion object {
        const val BASE = "https://api.nal.usda.gov/fdc"

        /** Where somebody gets their own key, shown next to the field that asks for it. */
        const val KEY_SIGNUP_URL = "https://fdc.nal.usda.gov/api-key-signup.html"

        const val ATTRIBUTION = "Ingredient data from USDA FoodData Central, which is public domain."

        /**
         * A default key allows an hour's worth of requests a day, and a real one far more.
         * Either way this is a phone looking up a food, not a bulk download.
         */
        const val REQUESTS_PER_MINUTE = 30

        private const val DEFAULT_PAGE_SIZE = 20

        // The identifiers this database gives its nutrients.
        private const val ENERGY_KCAL = 1008
        private const val PROTEIN = 1003
        private const val FAT = 1004
        private const val CARBS = 1005
        private const val FIBRE = 1079
        private const val SUGAR = 2000
        private const val SODIUM_MG = 1093

        private const val SALT_PER_SODIUM = 2.5

        private val json = Json { ignoreUnknownKeys = true }
    }
}
