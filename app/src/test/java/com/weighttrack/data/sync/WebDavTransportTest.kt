package com.weighttrack.data.sync

import com.google.common.truth.Truth.assertThat
import com.weighttrack.diagnostics.RuntimeLog
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The WebDAV transport against a server that answers.
 *
 * The ceiling on a reply, the mapping from a status code to something a person can act on, and
 * the directory made on the way past all live in the half of this class that a hand-written
 * exchange skips over. That half used to be held to nothing but a search of its own source text,
 * which passes just as happily when the call it is looking for has been moved somewhere it never
 * runs.
 */
class WebDavTransportTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var log: RuntimeLog

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        log = RuntimeLog(File(temporary.newFolder(), "runtime-log.txt"))
        log.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun target() = WebDavSyncTarget(
        baseUrl = server.url("/dav").toString(),
        username = "me",
        password = "secret",
        runtimeLog = log,
    ) { id, arguments -> "$id ${arguments.joinToString()}" }

    @Test
    fun `an ordinary file comes back, asked for the way a server expects`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"deviceId":"peer"}"""))

        val outcome = target().read("weighttrack-peer.json")

        assertThat(outcome).isEqualTo(SyncOutcome.Ok("""{"deviceId":"peer"}"""))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/dav/weighttrack-peer.json")
        // Nextcloud and ownCloud both answer 401 without this, and the person is told their
        // password is wrong when it is not.
        assertThat(request.getHeader("Authorization")).startsWith("Basic ")
    }

    @Test
    fun `a reply past the ceiling is refused, names the file, and throws nothing`() = runTest {
        // Past the bound while it is still arriving, which is the only way it can be caught: a
        // server is free to promise one length and send another.
        val enormous = Buffer()
        repeat(OVERSIZE_MEGABYTES) { enormous.write(ByteArray(BYTES_PER_MEGABYTE)) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(enormous))

        val outcome = target().read("weighttrack-peer.json")

        // Refused rather than unreachable: it will be the same size in an hour, so there is
        // nothing to gain by coming back. And the name is what turns this into something
        // somebody can go and look at.
        assertThat(outcome).isInstanceOf(SyncOutcome.Refused::class.java)
        assertThat((outcome as SyncOutcome.Refused).reason).contains("weighttrack-peer.json")
        assertThat(log.read()).contains("webdav_request_failed")
    }

    @Test
    fun `a password the server will not take is said plainly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertThat(target().read("weighttrack-peer.json"))
            .isInstanceOf(SyncOutcome.Refused::class.java)
    }

    @Test
    fun `a file that is not there is nothing to worry about`() = runTest {
        // A device that has not published yet, which is what every first sync looks like.
        server.enqueue(MockResponse().setResponseCode(404))

        assertThat(target().read("weighttrack-peer.json")).isEqualTo(SyncOutcome.Ok(null))
    }

    @Test
    fun `a server having a bad day is worth coming back to`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        assertThat(target().read("weighttrack-peer.json"))
            .isInstanceOf(SyncOutcome.Unreachable::class.java)
    }

    @Test
    fun `a server with no room left is refused rather than retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(507))

        assertThat(target().read("weighttrack-peer.json"))
            .isInstanceOf(SyncOutcome.Refused::class.java)
    }

    @Test
    fun `publishing makes the directory on the way past and sends the document whole`() = runTest {
        // 405 is how most servers say the directory is already there.
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(201))

        val outcome = target().write("weighttrack-me.json", """{"deviceId":"me"}""")

        assertThat(outcome).isEqualTo(SyncOutcome.Ok(Unit))
        val made = server.takeRequest()
        assertThat(made.method).isEqualTo("MKCOL")
        val put = server.takeRequest()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.path).isEqualTo("/dav/weighttrack-me.json")
        assertThat(put.body.readUtf8()).isEqualTo("""{"deviceId":"me"}""")
    }

    @Test
    fun `listing a directory reads the names out of a real reply`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(LISTING))

        val outcome = target().list()

        // The directory names itself in its own listing, which is what a real Nextcloud sends.
        assertThat((outcome as SyncOutcome.Ok).value)
            .containsAtLeast("weighttrack-me.json", "weighttrack-peer.json")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PROPFIND")
        // Without this a server is entitled to walk everything below the directory.
        assertThat(request.getHeader("Depth")).isEqualTo("1")
    }

    private companion object {
        /** One megabyte past what the app will read, so the bound is crossed and not touched. */
        const val OVERSIZE_MEGABYTES = 33
        const val BYTES_PER_MEGABYTE = 1024 * 1024

        val LISTING = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/</d:href></d:response>
              <d:response><d:href>/dav/weighttrack-me.json</d:href></d:response>
              <d:response><d:href>/dav/weighttrack-peer.json</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
    }
}
