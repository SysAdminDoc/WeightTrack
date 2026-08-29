package com.weighttrack.data.food

import com.weighttrack.BuildConfig
import com.weighttrack.core.nutrition.OpenFoodFactsClient
import com.weighttrack.core.nutrition.UsdaFoodDataClient
import com.weighttrack.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place this app talks to the internet.
 *
 * Written against the platform's own client rather than a library. There are two endpoints, both
 * plain reads, and a networking dependency would be a large thing to carry, and to keep patched,
 * for that.
 *
 * Nothing about the person is sent: no identifier, no readings, only the barcode or the words
 * typed into the search box. The app has no account and no analytics, and a food lookup is not
 * the place to start.
 */
@Singleton
class FoodHttp @Inject constructor() {

    suspend fun get(url: String, userAgent: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            try {
                // Anything but a plain success is nothing to read. A rate-limit answer in
                // particular must not be parsed as a product that does not exist.
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
    }
}

/**
 * The food databases, built with this build's identification.
 *
 * One instance each, held for the life of the app, because the rate limiters inside them are the
 * whole point: a fresh client per search would forget how many requests had already gone out.
 */
@Singleton
class FoodClients @Inject constructor(
    private val http: FoodHttp,
    private val settingsRepository: SettingsRepository,
) {
    private val agent = OpenFoodFactsClient.userAgent(BuildConfig.VERSION_NAME)

    private val off by lazy {
        OpenFoodFactsClient(fetch = { url, ua -> http.get(url, ua) }, userAgent = agent)
    }

    private val fdc by lazy {
        UsdaFoodDataClient(
            fetch = { url, ua -> http.get(url, ua) },
            userAgent = agent,
            // Read each time rather than captured: somebody pasting a key expects the next
            // search to use it, not the one after a restart.
            apiKey = { runBlocking { settingsRepository.settings.first().usdaApiKey } },
        )
    }

    fun openFoodFacts(): OpenFoodFactsClient = off

    fun usda(): UsdaFoodDataClient = fdc
}
