package com.weighttrack.core.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WebDavClientTest {

    private val sent = mutableListOf<WebDavClient.Request>()

    private fun client(
        baseUrl: String = "https://cloud.example.com/remote.php/dav/files/me/WeightTrack",
        reply: (WebDavClient.Request) -> WebDavClient.Response,
    ) = WebDavClient(
        transport = { request -> sent += request; reply(request) },
        baseUrl = baseUrl,
        username = "me",
        password = "hunter2",
    )

    private val nextcloudReply = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
          <d:response>
            <d:href>/remote.php/dav/files/me/WeightTrack/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/WeightTrack/weighttrack-aaa.json</d:href>
            <d:propstat><d:prop><d:resourcetype/></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/WeightTrack/weighttrack-bbb.json</d:href>
            <d:propstat><d:prop><d:resourcetype/></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `a real Nextcloud listing is understood`() = runTest {
        val result = client { WebDavClient.Response(207, nextcloudReply) }.list()

        assertThat(result).isInstanceOf(WebDavClient.Result.Ok::class.java)
        val names = (result as WebDavClient.Result.Ok).value
        assertThat(names).containsAtLeast("weighttrack-aaa.json", "weighttrack-bbb.json")
    }

    @Test
    fun `listing asks for one level only`() = runTest {
        // Without this a server may walk everything below the directory, which on somebody's
        // Nextcloud is an enormous reply for no reason.
        client { WebDavClient.Response(207, nextcloudReply) }.list()

        assertThat(sent.single().headers["Depth"]).isEqualTo("1")
        assertThat(sent.single().method).isEqualTo("PROPFIND")
        // An empty body means every property the server has, which is a lot of them.
        assertThat(sent.single().body).contains("resourcetype")
    }

    @Test
    fun `a wrong password is told apart from a server that is down`() = runTest {
        assertThat(client { WebDavClient.Response(401, "") }.list())
            .isEqualTo(WebDavClient.Result.NotAllowed)
        assertThat(client { WebDavClient.Response(403, "") }.list())
            .isEqualTo(WebDavClient.Result.NotAllowed)
        assertThat(client { WebDavClient.Response(500, "boom") }.list())
            .isInstanceOf(WebDavClient.Result.Failed::class.java)
    }

    @Test
    fun `a directory that is not there yet is not an error`() = runTest {
        // The first sync, before anything has been written. Normal, not a failure.
        assertThat(client { WebDavClient.Response(404, "") }.list())
            .isEqualTo(WebDavClient.Result.Missing)
        assertThat(client { WebDavClient.Response(404, "") }.read("weighttrack-aaa.json"))
            .isEqualTo(WebDavClient.Result.Missing)
    }

    @Test
    fun `making a directory that already exists is fine`() = runTest {
        // Servers disagree about how to say this. Nextcloud answers 405.
        assertThat(client { WebDavClient.Response(405, "") }.createDirectory())
            .isEqualTo(WebDavClient.Result.Ok(Unit))
        assertThat(client { WebDavClient.Response(201, "") }.createDirectory())
            .isEqualTo(WebDavClient.Result.Ok(Unit))
    }

    @Test
    fun `a missing slash in the address does not join two path segments together`() = runTest {
        client(baseUrl = "https://cloud.example.com/dav/WeightTrack") { WebDavClient.Response(200, "") }
            .read("weighttrack-aaa.json")

        assertThat(sent.single().url)
            .isEqualTo("https://cloud.example.com/dav/WeightTrack/weighttrack-aaa.json")
    }

    @Test
    fun `an address that already ends in a slash does not get a second one`() = runTest {
        client(baseUrl = "https://cloud.example.com/dav/WeightTrack/") { WebDavClient.Response(200, "") }
            .read("weighttrack-aaa.json")

        assertThat(sent.single().url)
            .isEqualTo("https://cloud.example.com/dav/WeightTrack/weighttrack-aaa.json")
    }

    @Test
    fun `every request carries the credentials`() = runTest {
        val c = client { WebDavClient.Response(200, "") }
        c.list()
        c.read("weighttrack-aaa.json")
        c.write("weighttrack-aaa.json", "{}")

        assertThat(sent).hasSize(3)
        for (request in sent) {
            assertThat(request.headers["Authorization"]).isEqualTo("Basic bWU6aHVudGVyMg==")
        }
    }

    @Test
    fun `basic auth matches the published example`() {
        // From the HTTP authentication specification, so a typo in the alphabet is caught.
        assertThat(WebDavClient.basicAuth("Aladdin", "open sesame"))
            .isEqualTo("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
    }

    @Test
    fun `base64 pads correctly at every remainder`() {
        assertThat(WebDavClient.base64("a")).isEqualTo("YQ==")
        assertThat(WebDavClient.base64("ab")).isEqualTo("YWI=")
        assertThat(WebDavClient.base64("abc")).isEqualTo("YWJj")
        assertThat(WebDavClient.base64("abcd")).isEqualTo("YWJjZA==")
        assertThat(WebDavClient.base64("")).isEqualTo("")
    }

    @Test
    fun `a password with an accent in it still authenticates`() {
        // Encoded as bytes, not as characters. Getting this wrong locks somebody out of their
        // own server with no explanation.
        assertThat(WebDavClient.base64("é")).isEqualTo("w6k=")
    }

    @Test
    fun `a file name from the server is not pasted into a url unchecked`() {
        assertThat(WebDavClient.encode("weighttrack-aaa.json")).isEqualTo("weighttrack-aaa.json")
        assertThat(WebDavClient.encode("a b")).isEqualTo("a%20b")
        assertThat(WebDavClient.encode("a/../b")).isEqualTo("a%2F..%2Fb")
        assertThat(WebDavClient.encode("é")).isEqualTo("%C3%A9")
    }

    @Test
    fun `an escaped name from a listing is read back as it was`() {
        for (name in listOf("weighttrack-aaa.json", "a b", "é", "a%b")) {
            assertThat(WebDavClient.decode(WebDavClient.encode(name))).isEqualTo(name)
        }
    }

    @Test
    fun `a listing with no files in it is empty rather than broken`() = runTest {
        val onlyTheDirectory = """
            <d:multistatus xmlns:d="DAV:"><d:response>
            <d:href>/dav/WeightTrack/</d:href></d:response></d:multistatus>
        """.trimIndent()

        val result = client { WebDavClient.Response(207, onlyTheDirectory) }.list()

        val names = (result as WebDavClient.Result.Ok).value
        // The directory names itself in its own listing. Nothing that looks like a device file.
        assertThat(names.none { SyncDocument.deviceIdOf(it) != null }).isTrue()
    }

    @Test
    fun `a server that uses a different namespace prefix still parses`() {
        // Namespace prefixes are the server's choice. Several use lp1 or D rather than d.
        val body = """
            <D:multistatus xmlns:D="DAV:"><D:response>
            <D:href>/dav/WeightTrack/weighttrack-zzz.json</D:href></D:response></D:multistatus>
        """.trimIndent()

        assertThat(WebDavClient.parseNames(body)).contains("weighttrack-zzz.json")
    }
}
