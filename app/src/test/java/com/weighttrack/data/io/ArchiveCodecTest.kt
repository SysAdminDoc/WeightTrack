package com.weighttrack.data.io

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * The archive format, exercised as an attacker would.
 *
 * Every test here is about a file the app was handed rather than one it wrote. The happy path is
 * one test; the rest are the ways a file can lie about itself.
 */
class ArchiveCodecTest {

    private val password = "correct horse battery staple".toCharArray()

    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ArchiveCodec.write(
                out,
                password,
                entries.map { (name, bytes) ->
                    ArchiveEntry(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
                },
            )
        }.toByteArray()

    private fun unpack(
        bytes: ByteArray,
        with: CharArray = password,
        limits: ArchiveLimits = ArchiveLimits(),
    ): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArrayOutputStream>()
        ArchiveCodec.read(ByteArrayInputStream(bytes), with, limits) { name, _ ->
            ByteArrayOutputStream().also { out[name] = it }
        }
        return out.mapValues { it.value.toByteArray() }
    }

    @Test
    fun `an archive round trips its entries byte for byte`() {
        // Bigger than one chunk on purpose: everything interesting about the framing happens at
        // the boundary between chunks, and a payload that fits in one never reaches it.
        val photo = Random(7).nextBytes(200_000)
        val json = """{"app":"WeightTrack"}""".toByteArray()

        val unpacked = unpack(
            archive(
                listOf(
                    ArchiveCodec.BACKUP_ENTRY to json,
                    "${ArchiveCodec.PHOTO_PREFIX}photo-1.jpg" to photo,
                ),
            ),
        )

        assertThat(unpacked.keys)
            .containsExactly(ArchiveCodec.BACKUP_ENTRY, "photos/photo-1.jpg")
        assertThat(unpacked.getValue(ArchiveCodec.BACKUP_ENTRY)).isEqualTo(json)
        assertThat(unpacked.getValue("photos/photo-1.jpg")).isEqualTo(photo)
    }

    @Test
    fun `an empty archive is still a readable file`() {
        assertThat(unpack(archive(emptyList()))).isEmpty()
    }

    @Test
    fun `the wrong password opens nothing and is told apart from damage`() {
        val bytes = archive(listOf(ArchiveCodec.BACKUP_ENTRY to "hello".toByteArray()))

        val thrown = assertThrows(ArchiveException::class.java) {
            unpack(bytes, with = "wrong".toCharArray())
        }

        assertThat(thrown.problem).isEqualTo(ArchiveProblem.WRONG_PASSWORD)
    }

    @Test
    fun `a changed byte anywhere in the body is refused`() {
        val bytes = archive(listOf(ArchiveCodec.BACKUP_ENTRY to Random(1).nextBytes(4_000)))
        // Past the header, so the slot still opens and the failure is the body's.
        val position = bytes.size - 200
        val tampered = bytes.copyOf().also { it[position] = (it[position] + 1).toByte() }

        val thrown = assertThrows(ArchiveException::class.java) { unpack(tampered) }

        assertThat(thrown.problem).isEqualTo(ArchiveProblem.DAMAGED)
    }

    @Test
    fun `a changed iteration count in the header is refused rather than silently rederived`() {
        val bytes = archive(listOf(ArchiveCodec.BACKUP_ENTRY to "hello".toByteArray()))
        // The four iteration bytes sit after the magic, the version, the slot count and the
        // key-derivation id.
        val at = "WTARCH\n".length + 3
        val tampered = bytes.copyOf().also { it[at + 3] = (it[at + 3] + 1).toByte() }

        val thrown = assertThrows(ArchiveException::class.java) { unpack(tampered) }

        // The wrapped key is bound to the parameters it was derived under, so changing them
        // fails to open the slot rather than deriving some other key and reading rubbish.
        assertThat(thrown.problem).isEqualTo(ArchiveProblem.WRONG_PASSWORD)
    }

    @Test
    fun `a truncated archive is refused rather than read as far as it goes`() {
        val bytes = archive(listOf(ArchiveCodec.BACKUP_ENTRY to Random(2).nextBytes(200_000)))
        val cut = bytes.copyOf(bytes.size - 1_000)

        val thrown = assertThrows(ArchiveException::class.java) { unpack(cut) }

        assertThat(thrown.problem).isEqualTo(ArchiveProblem.DAMAGED)
    }

    @Test
    fun `a file that is not an archive is named as one`() {
        val thrown = assertThrows(ArchiveException::class.java) {
            unpack("this is a text file, not an archive at all".toByteArray())
        }

        assertThat(thrown.problem).isEqualTo(ArchiveProblem.NOT_AN_ARCHIVE)
    }

    @Test
    fun `a newer format version is refused`() {
        val bytes = archive(listOf(ArchiveCodec.BACKUP_ENTRY to "hello".toByteArray()))
        val newer = bytes.copyOf().also { it["WTARCH\n".length] = 99 }

        val thrown = assertThrows(ArchiveException::class.java) { unpack(newer) }

        assertThat(thrown.problem).isEqualTo(ArchiveProblem.UNSUPPORTED_VERSION)
    }

    @Test
    fun `every name that could escape the folder is refused`() {
        val escapes = listOf(
            "../databases/weighttrack.db",
            "photos/../../secret",
            "photos/..",
            "/etc/passwd",
            "photos//nested",
            "photos/sub/dir.jpg",
            "photos/a\\..\\b.jpg",
            "backup.json.bak",
            "photos/",
            "photos/ .jpg",
            "photos/\u0000.jpg",
            "photos/\u202Egpj.exe",
        )

        escapes.forEach { name ->
            assertThat(ArchiveCodec.isSafeName(name)).isFalse()
        }
        assertThat(ArchiveCodec.isSafeName(ArchiveCodec.BACKUP_ENTRY)).isTrue()
        assertThat(ArchiveCodec.isSafeName("photos/photo-2026-08-31_1.jpg")).isTrue()
    }

    @Test
    fun `an entry named outside the archive is refused before anything is written`() {
        // Written by hand rather than through the public writer, because the writer would never
        // produce one: this is the file somebody else made.
        val bytes = ByteArrayOutputStream().also { out ->
            ArchiveCodec.write(
                out,
                password,
                listOf(
                    ArchiveEntry("../escaped.txt", 3) { ByteArrayInputStream("bad".toByteArray()) },
                ),
            )
        }.toByteArray()

        var written = false
        val thrown = assertThrows(ArchiveException::class.java) {
            ArchiveCodec.read(ByteArrayInputStream(bytes), password) { _, _ ->
                written = true
                ByteArrayOutputStream()
            }
        }

        assertThat(thrown.problem).isEqualTo(ArchiveProblem.BAD_NAME)
        assertThat(written).isFalse()
    }

    @Test
    fun `an entry claiming more than the limit allows is refused before it is read`() {
        val bytes = archive(listOf(ArchiveCodec.BACKUP_ENTRY to Random(3).nextBytes(5_000)))

        var opened = 0
        val thrown = assertThrows(ArchiveException::class.java) {
            ArchiveCodec.read(
                ByteArrayInputStream(bytes),
                password,
                ArchiveLimits(maxEntryBytes = 100),
            ) { _, _ ->
                opened += 1
                ByteArrayOutputStream()
            }
        }

        assertThat(thrown.problem).isEqualTo(ArchiveProblem.TOO_LARGE)
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun `a total larger than the limit is refused partway rather than unpacked`() {
        val bytes = archive(
            listOf(
                ArchiveCodec.BACKUP_ENTRY to Random(4).nextBytes(4_000),
                "photos/one.jpg" to Random(5).nextBytes(4_000),
                "photos/two.jpg" to Random(6).nextBytes(4_000),
            ),
        )

        val thrown = assertThrows(ArchiveException::class.java) {
            ArchiveCodec.read(
                ByteArrayInputStream(bytes),
                password,
                ArchiveLimits(maxTotalBytes = 6_000),
            ) { _, _ -> ByteArrayOutputStream() }
        }

        assertThat(thrown.problem).isEqualTo(ArchiveProblem.TOO_LARGE)
    }

    @Test
    fun `more entries than the limit allows is refused`() {
        val bytes = archive((1..5).map { "photos/photo-$it.jpg" to byteArrayOf(it.toByte()) })

        val thrown = assertThrows(ArchiveException::class.java) {
            unpack(bytes, limits = ArchiveLimits(maxEntries = 2))
        }

        assertThat(thrown.problem).isEqualTo(ArchiveProblem.TOO_LARGE)
    }

    @Test
    fun `two archives of the same thing under the same password are different files`() {
        val payload = "the same bytes every time".toByteArray()

        val first = archive(listOf(ArchiveCodec.BACKUP_ENTRY to payload))
        val second = archive(listOf(ArchiveCodec.BACKUP_ENTRY to payload))

        // A fresh salt, master key and nonce every time. Identical files would tell anybody
        // holding both that nothing changed between them.
        assertThat(first).isNotEqualTo(second)
        // Compared by content: two byte arrays holding the same bytes are not equal to each
        // other, so a map comparison would pass whatever either file held.
        assertThat(unpack(first).getValue(ArchiveCodec.BACKUP_ENTRY)).isEqualTo(payload)
        assertThat(unpack(second).getValue(ArchiveCodec.BACKUP_ENTRY)).isEqualTo(payload)
    }

    @Test
    fun `a payload is never legible in the file`() {
        val secret = "Matt weighed 82.4 kg on Tuesday"
        val bytes = archive(listOf(ArchiveCodec.BACKUP_ENTRY to secret.toByteArray()))

        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain(secret)
    }

    @Test
    fun `a caller that refuses an entry stops the read`() {
        val bytes = archive(listOf(ArchiveCodec.BACKUP_ENTRY to "hello".toByteArray()))

        assertThrows(IllegalStateException::class.java) {
            ArchiveCodec.read(ByteArrayInputStream(bytes), password) { _, _ ->
                error("not writing that")
            }
        }
    }
}
