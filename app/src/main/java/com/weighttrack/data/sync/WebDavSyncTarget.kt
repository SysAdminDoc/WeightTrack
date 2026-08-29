package com.weighttrack.data.sync

import com.weighttrack.core.sync.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * A directory on a WebDAV server: Nextcloud, ownCloud, or anything else that speaks it.
 *
 * The one place in this app with a networking library. The food lookups are two plain GETs and
 * are written against the platform client on purpose, but WebDAV needs PROPFIND and MKCOL, and
 * the platform client throws on any method it has not heard of. Reflecting into its private
 * fields to get around that is a trick that breaks on somebody else's Android version, and this
 * is somebody's own server and their own data.
 */
class WebDavSyncTarget(
    private val baseUrl: String,
    private val username: String,
    password: String,
) : SyncTarget {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val client = WebDavClient(
        transport = ::send,
        baseUrl = baseUrl,
        username = username,
        password = password,
    )

    override val describe: String = runCatching { URL(baseUrl).host }.getOrNull()
        ?.let { "$username at $it" } ?: baseUrl

    override suspend fun list(): SyncOutcome<List<String>> = when (val result = client.list()) {
        is WebDavClient.Result.Ok -> SyncOutcome.Ok(result.value)
        // Nothing there yet is what a first sync looks like, not a failure.
        WebDavClient.Result.Missing -> SyncOutcome.Ok(emptyList())
        else -> result.asFailure()
    }

    override suspend fun read(name: String): SyncOutcome<String?> =
        when (val result = client.read(name)) {
            is WebDavClient.Result.Ok -> SyncOutcome.Ok(result.value)
            WebDavClient.Result.Missing -> SyncOutcome.Ok(null)
            else -> result.asFailure()
        }

    override suspend fun write(name: String, content: String): SyncOutcome<Unit> {
        // Made on the way past. A server will not create the directory for a PUT, and the
        // alternative is telling somebody to go and make a folder in a web interface first.
        client.createDirectory()
        return when (val result = client.write(name, content)) {
            is WebDavClient.Result.Ok -> SyncOutcome.Ok(Unit)
            else -> result.asFailure()
        }
    }

    private fun WebDavClient.Result<*>.asFailure(): SyncOutcome<Nothing> = when (this) {
        WebDavClient.Result.NotAllowed ->
            SyncOutcome.Refused("The server would not accept that username and password.")
        WebDavClient.Result.Missing -> SyncOutcome.Refused("That address is not there.")
        is WebDavClient.Result.Failed -> when {
            // Nothing the person can do about these, and they usually pass.
            code == 0 -> SyncOutcome.Unreachable("Could not reach the server.")
            code == 507 -> SyncOutcome.Refused("The server has no room left.")
            code in 500..599 -> SyncOutcome.Unreachable("The server answered $code.")
            else -> SyncOutcome.Refused("The server answered $code.")
        }
        is WebDavClient.Result.Ok -> error("not a failure")
    }

    private suspend fun send(request: WebDavClient.Request): WebDavClient.Response =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = request.body?.toRequestBody(JSON)
                val built = Request.Builder()
                    .url(request.url)
                    .method(request.method, body)
                    .apply { request.headers.forEach { (key, value) -> header(key, value) } }
                    .build()
                http.newCall(built).execute().use { response ->
                    WebDavClient.Response(response.code, response.body.string())
                }
            }.getOrElse {
                // No signal, no such host, a certificate the phone will not accept. A zero means
                // the request never happened, which is worth trying again later.
                WebDavClient.Response(0, it.message.orEmpty())
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 45L
        val JSON = "application/json".toMediaType()
    }
}
