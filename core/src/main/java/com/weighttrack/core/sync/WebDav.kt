package com.weighttrack.core.sync

/**
 * Just enough WebDAV to keep a folder of sync files on a Nextcloud or ownCloud server.
 *
 * Three verbs. List what is in a directory, read a file, write a file. No library: WebDAV is
 * ordinary HTTP with one extra method and a lump of XML in the reply, and carrying a dependency
 * for that would be a much larger thing to keep patched than the thirty lines it saves.
 *
 * The transport is handed in, so everything awkward about this protocol can be tested without a
 * server: the parsing of a real Nextcloud reply, the escaping, the directory that does not exist
 * yet, the password that is wrong.
 */
class WebDavClient(
    private val transport: Transport,
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {

    /** What a request needs to do, kept small enough that the app can supply it in one function. */
    fun interface Transport {
        /**
         * Runs one request and hands back the status and body.
         *
         * Never throws for a failed request. A network that is not there is an ordinary outcome
         * of trying to sync and not an exceptional one.
         */
        suspend fun send(request: Request): Response
    }

    data class Request(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String? = null,
    )

    data class Response(val code: Int, val body: String)

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>

        /** The username or password is wrong. Worth telling somebody about rather than retrying. */
        data object NotAllowed : Result<Nothing>

        /** Nothing there yet, which on a first sync is normal rather than a failure. */
        data object Missing : Result<Nothing>

        data class Failed(val code: Int, val detail: String) : Result<Nothing>
    }

    /** The names of the files directly inside the sync directory. */
    suspend fun list(): Result<List<String>> {
        val response = transport.send(
            Request(
                method = "PROPFIND",
                url = directory,
                // One level. Without this header a server is entitled to walk the whole tree
                // below the directory, which on somebody's Nextcloud could be an enormous reply.
                headers = headers + mapOf("Depth" to "1", "Content-Type" to "application/xml"),
                body = PROPFIND_BODY,
            ),
        )
        return when {
            response.code == 401 || response.code == 403 -> Result.NotAllowed
            response.code == 404 -> Result.Missing
            response.code !in 200..299 -> Result.Failed(response.code, response.body)
            else -> Result.Ok(parseNames(response.body))
        }
    }

    suspend fun read(name: String): Result<String> {
        val response = transport.send(
            Request(method = "GET", url = directory + encode(name), headers = headers),
        )
        return when {
            response.code == 401 || response.code == 403 -> Result.NotAllowed
            response.code == 404 -> Result.Missing
            response.code !in 200..299 -> Result.Failed(response.code, response.body)
            else -> Result.Ok(response.body)
        }
    }

    suspend fun write(name: String, content: String): Result<Unit> {
        val response = transport.send(
            Request(
                method = "PUT",
                url = directory + encode(name),
                headers = headers + mapOf("Content-Type" to "application/json"),
                body = content,
            ),
        )
        return when {
            response.code == 401 || response.code == 403 -> Result.NotAllowed
            response.code !in 200..299 -> Result.Failed(response.code, response.body)
            else -> Result.Ok(Unit)
        }
    }

    /** Makes the sync directory. Harmless when it is already there, which most servers say with 405. */
    suspend fun createDirectory(): Result<Unit> {
        val response = transport.send(Request(method = "MKCOL", url = directory, headers = headers))
        return when {
            response.code == 401 || response.code == 403 -> Result.NotAllowed
            // Already exists. Not a problem, and every server words it differently.
            response.code == 405 || response.code == 301 -> Result.Ok(Unit)
            response.code !in 200..299 -> Result.Failed(response.code, response.body)
            else -> Result.Ok(Unit)
        }
    }

    private val directory: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val headers: Map<String, String>
        get() = mapOf("Authorization" to basicAuth(username, password))

    companion object {
        /**
         * Only the properties that are needed.
         *
         * An empty PROPFIND body means "every property you have", and a Nextcloud with a large
         * directory will happily send megabytes of them.
         */
        const val PROPFIND_BODY =
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""

        fun basicAuth(username: String, password: String): String =
            "Basic " + base64("$username:$password")

        /**
         * The file names inside a PROPFIND reply.
         *
         * Parsed with a regular expression rather than an XML parser, which is usually the wrong
         * choice and is defensible here: the shape is fixed, the only thing wanted is the last
         * segment of each href, and the alternative is dragging a parser into a module that has
         * no other use for one. Namespace prefixes vary between servers, hence the loose match on
         * the tag name.
         */
        fun parseNames(body: String): List<String> =
            HREF.findAll(body)
                .map { it.groupValues[1].trim() }
                .map { href -> href.trimEnd('/').substringAfterLast('/') }
                .map { decode(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()

        private val HREF = Regex("<[^>]*href>([^<]*)</[^>]*href>", RegexOption.IGNORE_CASE)

        /**
         * Percent-encodes a file name for a URL.
         *
         * Device identifiers are letters and digits, so in practice nothing needs encoding, but a
         * name that arrived from a server is not something to paste into a URL unchecked.
         */
        fun encode(name: String): String = buildString {
            for (byte in name.encodeToByteArray()) {
                val value = byte.toInt() and 0xFF
                // Compared as a number, not as a character. The second byte of an accented
                // letter is 0xC3, and Char.isLetterOrDigit says that on its own is a letter, so
                // a name with an accent in it would go into the URL unescaped and half decoded.
                if (value in ZERO..NINE || value in UPPER_A..UPPER_Z || value in LOWER_A..LOWER_Z ||
                    value == DASH || value == UNDERSCORE || value == DOT || value == TILDE
                ) {
                    append(value.toChar())
                } else {
                    append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
                }
            }
        }

        private const val ZERO = 0x30
        private const val NINE = 0x39
        private const val UPPER_A = 0x41
        private const val UPPER_Z = 0x5A
        private const val LOWER_A = 0x61
        private const val LOWER_Z = 0x7A
        private const val DASH = 0x2D
        private const val UNDERSCORE = 0x5F
        private const val DOT = 0x2E
        private const val TILDE = 0x7E

        fun decode(name: String): String {
            if (!name.contains('%')) return name
            val bytes = ArrayList<Byte>(name.length)
            var index = 0
            while (index < name.length) {
                val char = name[index]
                if (char == '%' && index + 2 < name.length) {
                    val value = name.substring(index + 1, index + 3).toIntOrNull(16)
                    if (value != null) {
                        bytes.add(value.toByte())
                        index += 3
                        continue
                    }
                }
                for (byte in char.toString().encodeToByteArray()) bytes.add(byte)
                index++
            }
            return bytes.toByteArray().decodeToString()
        }

        private const val HEX = "0123456789ABCDEF"

        /** Base64, written out because this module deliberately has no Android in it. */
        internal fun base64(text: String): String {
            val bytes = text.encodeToByteArray()
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            return buildString {
                var index = 0
                while (index < bytes.size) {
                    val one = bytes[index].toInt() and 0xFF
                    val two = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else -1
                    val three = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else -1
                    append(alphabet[one shr 2])
                    append(alphabet[((one and 0x03) shl 4) or (if (two >= 0) two shr 4 else 0)])
                    append(if (two >= 0) alphabet[((two and 0x0F) shl 2) or (if (three >= 0) three shr 6 else 0)] else '=')
                    append(if (three >= 0) alphabet[three and 0x3F] else '=')
                    index += 3
                }
            }
        }
    }
}
