package com.weighttrack.data.sync

import com.weighttrack.core.sync.WebDavClient
import com.weighttrack.diagnostics.LogArea
import com.weighttrack.diagnostics.LogEvent
import com.weighttrack.diagnostics.RuntimeLog
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
    private val runtimeLog: RuntimeLog,
    /**
     * The bare exchange, below everything this class does about a failure.
     *
     * Left open so the paths that matter, and that nobody can reproduce on demand, can be driven
     * without a server. Deliberately underneath the error handling rather than replacing it: a
     * seam above would let a test pass while the real recovery was broken.
     */
    private val exchange: (suspend (WebDavClient.Request) -> WebDavClient.Response)? = null,
    private val say: (Int, Array<out Any>) -> String,
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
            SyncOutcome.Refused(say(com.weighttrack.R.string.sync_wrong_password, emptyArray()))
        WebDavClient.Result.Missing ->
            SyncOutcome.Refused(say(com.weighttrack.R.string.sync_address_missing, emptyArray()))
        is WebDavClient.Result.Failed -> runtimeLog.write(
            LogArea.SYNC,
            LogEvent.WEBDAV_REQUEST_FAILED,
            code = code,
        ).let {
            when {
            // Nothing the person can do about these, and they usually pass.
            code == 0 ->
                SyncOutcome.Unreachable(say(com.weighttrack.R.string.sync_server_unreachable, emptyArray()))
            code == 507 ->
                SyncOutcome.Refused(say(com.weighttrack.R.string.sync_server_full, emptyArray()))
            code in 500..599 ->
                SyncOutcome.Unreachable(say(com.weighttrack.R.string.sync_server_answered, arrayOf(code)))
            else ->
                SyncOutcome.Refused(say(com.weighttrack.R.string.sync_server_answered, arrayOf(code)))
            }
        }
        is WebDavClient.Result.Ok -> error("not a failure")
    }

    private suspend fun send(request: WebDavClient.Request): WebDavClient.Response =
        withContext(Dispatchers.IO) {
            runCatching {
                exchange?.let { return@runCatching it(request) }
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
                runtimeLog.write(LogArea.SYNC, LogEvent.WEBDAV_TRANSPORT_FAILED, cause = it)
                WebDavClient.Response(0, it.message.orEmpty())
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 45L
        val JSON = "application/json".toMediaType()
    }
}
