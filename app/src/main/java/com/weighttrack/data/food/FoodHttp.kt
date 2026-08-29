package com.weighttrack.data.food

import com.weighttrack.BuildConfig
import com.weighttrack.core.nutrition.OpenFoodFactsClient
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

/** Builds the client with this build's identification, which the service asks every caller for. */
@Singleton
class OpenFoodFactsFactory @Inject constructor(private val http: FoodHttp) {
    fun create(): OpenFoodFactsClient = OpenFoodFactsClient(
        fetch = { url, agent -> http.get(url, agent) },
        userAgent = OpenFoodFactsClient.userAgent(BuildConfig.VERSION_NAME),
    )
}
