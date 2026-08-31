package com.weighttrack.core.nutrition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads Open Food Facts.
 *
 * The database is crowdsourced and shared under the Open Database Licence, so anything taken
 * from it is credited wherever it is shown. See [ATTRIBUTION].
 *
 * Two different services, because there is only one of them for each job: a product is read from
 * the v3 product endpoint, and free text is searched through Search-a-licious, since the v2 and
 * v3 APIs deliberately do not do free text at all.
 *
 * The network call is a parameter rather than a dependency, so every URL this builds, every
 * limit it keeps and every field it reads can be checked without touching the internet.
 */
class OpenFoodFactsClient(
    private val fetch: suspend (url: String, userAgent: String) -> String?,
    private val userAgent: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val productLimiter: RateLimiter = RateLimiter(RateLimiter.PRODUCT_READS_PER_MINUTE),
    private val searchLimiter: RateLimiter = RateLimiter(RateLimiter.SEARCHES_PER_MINUTE),
) {
    /** What came back, or why nothing did. */
    sealed interface Result<out T> {
        data class Found<T>(val value: T) : Result<T>
        data object NotFound : Result<Nothing>
        /** The allowance is spent. Trying anyway is how an address gets banned. */
        data class RateLimited(val retryInMillis: Long) : Result<Nothing>
        data object Unreachable : Result<Nothing>
    }

    suspend fun byBarcode(barcode: String): Result<Food> {
        val code = barcode.trim()
        if (code.isEmpty() || !code.all { it.isDigit() }) return Result.NotFound
        if (!productLimiter.tryAcquire(now())) {
            return Result.RateLimited(productLimiter.waitMillis(now()))
        }
        // Only the fields that are used. The full product record is enormous and this runs on a
        // phone, often on mobile data, against a service that counts requests.
        val url = "$PRODUCT_BASE/api/v3/product/$code.json?fields=$PRODUCT_FIELDS"
        val body = fetch(url, userAgent) ?: return Result.Unreachable
        val root = parse(body) ?: return Result.Unreachable
        if (root["status"]?.jsonPrimitive?.contentOrNullSafe() != "success") return Result.NotFound
        val product = root["product"]?.jsonObject ?: return Result.NotFound
        return productOf(product, code)?.let { Result.Found(it) } ?: Result.NotFound
    }

    suspend fun search(query: String, pageSize: Int = DEFAULT_PAGE_SIZE): Result<List<Food>> {
        val text = query.trim()
        if (text.isEmpty()) return Result.Found(emptyList())
        if (!searchLimiter.tryAcquire(now())) {
            return Result.RateLimited(searchLimiter.waitMillis(now()))
        }
        val url = "$SEARCH_BASE/search?q=${encode(text)}&page_size=${pageSize.coerceIn(1, 50)}"
        val body = fetch(url, userAgent) ?: return Result.Unreachable
        val root = parse(body) ?: return Result.Unreachable
        val hits = root["hits"]?.jsonArray ?: return Result.Found(emptyList())
        return Result.Found(
            hits.mapNotNull { hit ->
                val obj = hit.jsonObject
                productOf(obj, obj["code"]?.jsonPrimitive?.contentOrNullSafe())
            },
        )
    }

    private fun productOf(product: JsonObject, code: String?): Food? {
        val name = product["product_name"]?.jsonPrimitive?.contentOrNullSafe()?.trim()
        if (name.isNullOrEmpty()) return null
        val nutriments = product["nutriments"]?.jsonObject
        val kcal = nutriments?.number("energy-kcal_100g")
            // Some entries carry only kilojoules, which is a unit not a different measurement.
            ?: nutriments?.number("energy-kj_100g")?.div(KJ_PER_KCAL)
            ?: return null
        val nutrients = Nutrients(
            kcal = kcal,
            proteinG = nutriments?.number("proteins_100g"),
            carbsG = nutriments?.number("carbohydrates_100g"),
            fatG = nutriments?.number("fat_100g"),
            fibreG = nutriments?.number("fiber_100g"),
            sugarG = nutriments?.number("sugars_100g"),
            saltG = nutriments?.number("salt_100g"),
        )
        // Crowdsourced, so it holds things nobody meant: a per-serving figure typed into a
        // per-hundred-grams field, a decimal point in the wrong place, an energy value with every
        // macro left at zero. A wrong number in somebody's diary is worse than a missing one,
        // because nothing about it looks wrong.
        if (!NutrientPlausibility.isBelievable(nutrients, nutriments?.number("alcohol_100g"))) {
            return null
        }
        return Food(
            name = name,
            brand = product["brands"]?.jsonPrimitive?.contentOrNullSafe()
                ?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            barcode = code?.takeIf { it.isNotBlank() },
            per100g = nutrients,
            servingGrams = product.number("serving_quantity")?.takeIf { it > 0 },
            origin = FoodOrigin.OPEN_FOOD_FACTS,
            // Stamped where the answer came from, so a cached tin can say how old its numbers
            // are and be asked again. A label changes and a cached product does not.
            fetchedAtUtcMillis = now(),
        )
    }

    private fun parse(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    /**
     * Numbers arrive as numbers on one endpoint and as strings on the other, and sometimes as
     * an empty string meaning nobody filled it in.
     */
    private fun JsonObject.number(key: String): Double? {
        val element: JsonElement = this[key] ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNullSafe()?.trim()?.toDoubleOrNull()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it != "null" }

    private fun encode(text: String): String =
        text.replace(" ", "+").filter { it.isLetterOrDigit() || it in "+-_.&" }

    companion object {
        const val PRODUCT_BASE = "https://world.openfoodfacts.org"

        /** Free text lives here. The v2 and v3 APIs do not do it, by design. */
        const val SEARCH_BASE = "https://search.openfoodfacts.org"

        /**
         * What the licence asks for, shown wherever a product from the database is.
         *
         * Not optional and not buried in an about screen: the data is somebody else's work,
         * given away on the condition that it is credited.
         */
        const val ATTRIBUTION = "Food data from Open Food Facts, shared under the Open Database Licence."

        private const val DEFAULT_PAGE_SIZE = 20
        private const val KJ_PER_KCAL = 4.184

        private const val PRODUCT_FIELDS =
            "product_name,brands,code,serving_quantity,serving_size,nutriments"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The identification the service asks every caller for.
         *
         * A missing or dishonest one is what gets an address blocked, and rightly: an operator
         * with no way to tell who is hammering them has no option but to block everybody.
         */
        fun userAgent(version: String): String =
            "WeightTrack/$version (https://github.com/SysAdminDoc/WeightTrack)"
    }
}
