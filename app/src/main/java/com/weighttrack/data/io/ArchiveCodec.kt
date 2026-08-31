package com.weighttrack.data.io

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Why an archive could not be read.
 *
 * Separate values because they mean different things to the person holding the file. A wrong
 * password is something they can fix; a damaged file is not, and telling them to try another
 * password would waste their afternoon.
 */
enum class ArchiveProblem {
    /** The file does not begin the way one of ours does. */
    NOT_AN_ARCHIVE,

    /** Written by a newer version of the app than this one. */
    UNSUPPORTED_VERSION,

    /** No slot opened with that password. */
    WRONG_PASSWORD,

    /** It opened and then did not add up: a changed byte, a truncated tail, a reordered chunk. */
    DAMAGED,

    /** It claims more than this will unpack. */
    TOO_LARGE,

    /** A name in it is not one this will write to. */
    BAD_NAME,
}

class ArchiveException(val problem: ArchiveProblem) : Exception(problem.name)

/**
 * One thing in an archive: a name, a length, and a way to open the bytes.
 *
 * [open] is called more than once, because the digest is written in front of the payload and
 * both passes read the same source.
 */
class ArchiveEntry(
    val name: String,
    val sizeBytes: Long,
    val open: () -> InputStream,
)

/**
 * What an archive is allowed to claim before this refuses to unpack it.
 *
 * A file somebody was handed is a file somebody else wrote. Every count and length in it is a
 * claim, and the only honest limit is the one applied while reading rather than one read out of
 * the file's own header.
 */
data class ArchiveLimits(
    val maxEntries: Int = 20_000,
    val maxNameBytes: Int = 255,
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
)

/**
 * A password-protected archive: the backup and the progress photos in one file.
 *
 * JSON and CSV carry rows. They cannot carry a photograph, so moving to a new phone has always
 * meant leaving the pictures behind. This is the file that does not.
 *
 * The shape follows Aegis: a plaintext header of key-derivation parameters and one or more slots,
 * each holding the same random master key wrapped under a key derived from something the person
 * knows, and a body encrypted once under that master key. Slots are why a future release can add
 * a device-keystore slot without re-encrypting anybody's archive.
 *
 * The body is chunked AES-256-GCM in the manner of SeedVault, rather than one enormous
 * ciphertext. Each chunk names its own position and whether it is the last, inside the tag, so a
 * file that has been truncated, reordered or spliced fails on the chunk where it was done rather
 * than after the whole thing has been unpacked.
 *
 * The key derivation is PBKDF2-HMAC-SHA256 at 600,000 iterations, which is what OWASP's password
 * storage guidance gives for that function. Argon2id and scrypt are both better, and neither is
 * on Android's platform providers: reaching either means carrying a whole crypto provider for
 * one function. The algorithm and its cost live in the header per slot, so moving is a matter of
 * writing new archives differently, and old ones keep opening.
 */
object ArchiveCodec {

    const val FORMAT_VERSION = 1

    /** The suffix that says what a file is, since nothing else about it is readable. */
    const val EXTENSION = "wtarchive"

    /** Where the structured export lives inside an archive. */
    const val BACKUP_ENTRY = "backup.json"

    /** The folder progress photos live under. Names are checked against this exactly. */
    const val PHOTO_PREFIX = "photos/"

    private val MAGIC = "WTARCH\n".toByteArray(Charsets.US_ASCII)

    private const val KDF_PBKDF2_SHA256 = 1
    private const val KDF_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val MASTER_KEY_BYTES = 32
    private const val WRAPPED_KEY_BYTES = MASTER_KEY_BYTES + TAG_BYTES

    /** Plaintext bytes per chunk. Big enough that the tags cost nothing, small enough to stream. */
    private const val CHUNK_BYTES = 64 * 1024

    private const val MAX_CIPHER_CHUNK = CHUNK_BYTES + TAG_BYTES

    private const val MARK_ENTRY: Byte = 1
    private const val MARK_END: Byte = 0

    private const val FLAG_MORE: Byte = 0
    private const val FLAG_LAST: Byte = 1

    private val random = SecureRandom()

    /**
     * Writes an archive.
     *
     * The password is a [CharArray] rather than a String all the way through, because a String
     * cannot be cleared and lives in the heap until something happens to collect it.
     */
    fun write(destination: OutputStream, password: CharArray, entries: List<ArchiveEntry>) {
        val master = ByteArray(MASTER_KEY_BYTES).also(random::nextBytes)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val slotContext = slotContext(FORMAT_VERSION, slotIndex = 0, KDF_ITERATIONS, salt)
        val wrapped = Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(deriveKey(password, salt), "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            updateAAD(slotContext)
            doFinal(master)
        }

        val header = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                write(MAGIC)
                writeByte(FORMAT_VERSION)
                writeByte(1)
                writeByte(KDF_PBKDF2_SHA256)
                writeInt(KDF_ITERATIONS)
                writeByte(salt.size)
                write(salt)
                writeByte(nonce.size)
                write(nonce)
                writeShort(wrapped.size)
                write(wrapped)
                flush()
            }
        }.toByteArray()
        destination.write(header)

        val headerHash = sha256(header)
        val body = ChunkedEncryptingStream(destination, master, headerHash)
        DataOutputStream(body).use { out ->
            entries.forEach { entry ->
                val name = entry.name.toByteArray(Charsets.UTF_8)
                out.writeByte(MARK_ENTRY.toInt())
                out.writeShort(name.size)
                out.write(name)
                out.writeLong(entry.sizeBytes)
                // The digest goes in front of the bytes so a restore can check what it read
                // against what the writer meant, without holding the whole entry to compare.
                out.write(digestOf(entry))
                entry.open().use { source ->
                    val written = source.copyTo(out)
                    check(written == entry.sizeBytes) {
                        "archive entry ${entry.name} changed size while being written"
                    }
                }
            }
            out.writeByte(MARK_END.toInt())
        }
        destination.flush()
    }

    /**
     * Reads an archive, handing each entry's bytes to whatever the caller wants them written to.
     *
     * [receive] is asked for a destination per entry and may refuse by throwing; nothing is
     * written before the password has opened a slot, and a caller that wants the whole thing or
     * nothing writes somewhere temporary and moves it afterwards.
     *
     * Returns the names it unpacked, in the order they appeared.
     */
    fun read(
        source: InputStream,
        password: CharArray,
        limits: ArchiveLimits = ArchiveLimits(),
        receive: (name: String, sizeBytes: Long) -> OutputStream,
    ): List<String> {
        val head = DataInputStream(source)
        val header = ByteArrayOutputStream()

        val magic = ByteArray(MAGIC.size)
        if (runCatching { head.readFully(magic) }.isFailure) {
            throw ArchiveException(ArchiveProblem.NOT_AN_ARCHIVE)
        }
        if (!magic.contentEquals(MAGIC)) throw ArchiveException(ArchiveProblem.NOT_AN_ARCHIVE)
        header.write(magic)

        val version = head.readUnsignedByteOrFail()
        header.write(version)
        if (version > FORMAT_VERSION) throw ArchiveException(ArchiveProblem.UNSUPPORTED_VERSION)
        if (version < 1) throw ArchiveException(ArchiveProblem.NOT_AN_ARCHIVE)

        val slotCount = head.readUnsignedByteOrFail()
        header.write(slotCount)
        if (slotCount !in 1..MAX_SLOTS) throw ArchiveException(ArchiveProblem.NOT_AN_ARCHIVE)

        var master: ByteArray? = null
        repeat(slotCount) { index ->
            val kdfId = head.readUnsignedByteOrFail()
            val iterations = head.readIntOrFail()
            val salt = head.readSizedOrFail(head.readUnsignedByteOrFail())
            val nonce = head.readSizedOrFail(head.readUnsignedByteOrFail())
            val wrappedLength = head.readUnsignedShortOrFail()
            val wrapped = head.readSizedOrFail(wrappedLength)

            header.apply {
                write(kdfId)
                DataOutputStream(this).writeInt(iterations)
                write(salt.size)
                write(salt)
                write(nonce.size)
                write(nonce)
                DataOutputStream(this).writeShort(wrappedLength)
                write(wrapped)
            }

            if (master != null) return@repeat
            if (kdfId != KDF_PBKDF2_SHA256) return@repeat
            // A slot naming an absurd cost would otherwise spend minutes deriving a key for a
            // file that was made to do exactly that.
            if (iterations !in 1..MAX_ITERATIONS) return@repeat
            if (salt.size !in MIN_SALT_BYTES..MAX_SALT_BYTES) return@repeat
            if (nonce.size != NONCE_BYTES) return@repeat
            if (wrapped.size != WRAPPED_KEY_BYTES) return@repeat
            master = runCatching {
                Cipher.getInstance(TRANSFORMATION).run {
                    init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(deriveKey(password, salt, iterations), "AES"),
                        GCMParameterSpec(TAG_BITS, nonce),
                    )
                    updateAAD(slotContext(version, index, iterations, salt))
                    doFinal(wrapped)
                }
            }.getOrNull()
        }
        val key = master ?: throw ArchiveException(ArchiveProblem.WRONG_PASSWORD)

        val body = DataInputStream(
            ChunkedDecryptingStream(source, key, sha256(header.toByteArray())),
        )
        val names = mutableListOf<String>()
        var total = 0L
        while (true) {
            val mark = body.readByteOrDamaged()
            if (mark == MARK_END) break
            if (mark != MARK_ENTRY) throw ArchiveException(ArchiveProblem.DAMAGED)
            if (names.size >= limits.maxEntries) throw ArchiveException(ArchiveProblem.TOO_LARGE)

            val nameLength = body.readUnsignedShortOrDamaged()
            if (nameLength == 0 || nameLength > limits.maxNameBytes) {
                throw ArchiveException(ArchiveProblem.BAD_NAME)
            }
            val name = body.readSizedOrDamaged(nameLength).toString(Charsets.UTF_8)
            if (!isSafeName(name)) throw ArchiveException(ArchiveProblem.BAD_NAME)

            val size = body.readLongOrDamaged()
            if (size < 0 || size > limits.maxEntryBytes) {
                throw ArchiveException(ArchiveProblem.TOO_LARGE)
            }
            total += size
            if (total > limits.maxTotalBytes) throw ArchiveException(ArchiveProblem.TOO_LARGE)
            val expected = body.readSizedOrDamaged(DIGEST_BYTES)

            val digest = MessageDigest.getInstance("SHA-256")
            receive(name, size).use { sink ->
                var remaining = size
                val buffer = ByteArray(COPY_BUFFER)
                while (remaining > 0) {
                    val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = body.read(buffer, 0, wanted)
                    if (read <= 0) throw ArchiveException(ArchiveProblem.DAMAGED)
                    digest.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
                sink.flush()
            }
            // Belt as well as braces. The chunk tags already prove nothing was altered, but the
            // digest proves the writer and the reader agree about where this entry ended, which
            // a framing bug of our own would break without any tag noticing.
            if (!MessageDigest.isEqual(digest.digest(), expected)) {
                throw ArchiveException(ArchiveProblem.DAMAGED)
            }
            names += name
        }
        return names
    }

    /**
     * Whether a name is one this will write to.
     *
     * Deliberately a small allowed set rather than a list of things to reject. An archive is a
     * file somebody was sent, and its names decide where bytes land: `../../databases/x` and an
     * absolute path both have to be impossible, and so does every spelling of them.
     */
    fun isSafeName(name: String): Boolean {
        if (name == BACKUP_ENTRY) return true
        if (!name.startsWith(PHOTO_PREFIX)) return false
        val leaf = name.removePrefix(PHOTO_PREFIX)
        if (leaf.isEmpty() || leaf.length > MAX_LEAF_LENGTH) return false
        if (leaf == "." || leaf == "..") return false
        // ASCII only. isLetterOrDigit is Unicode-aware and would admit right-to-left marks
        // and lookalike characters into a name that decides where bytes land on disk.
        return leaf.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' ||
                it == '.' || it == '-' || it == '_'
        }
    }

    private fun digestOf(entry: ArchiveEntry): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        entry.open().use { source ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = KDF_ITERATIONS,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * What a slot's wrapped key is bound to.
     *
     * Everything that decides how the key was derived. Changing the iteration count or the salt
     * in a file therefore breaks the tag rather than quietly producing a different key and a
     * "wrong password" nobody can explain.
     */
    private fun slotContext(
        version: Int,
        slotIndex: Int,
        iterations: Int,
        salt: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply {
            write(MAGIC)
            writeByte(version)
            writeByte(slotIndex)
            writeByte(KDF_PBKDF2_SHA256)
            writeInt(iterations)
            write(salt)
            flush()
        }
    }.toByteArray()

    private fun chunkContext(headerHash: ByteArray, index: Long, flag: Byte): ByteArray =
        ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                write(headerHash)
                writeLong(index)
                writeByte(flag.toInt())
                flush()
            }
        }.toByteArray()

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Buffers plaintext into fixed chunks and seals each one.
     *
     * Every chunk carries its own index and whether it is the last, inside the tag. A file cut
     * short, or one whose chunks have been swapped, fails at the chunk rather than after the
     * whole archive has been unpacked and written somewhere.
     */
    private class ChunkedEncryptingStream(
        private val destination: OutputStream,
        private val key: ByteArray,
        private val headerHash: ByteArray,
    ) : OutputStream() {

        private val buffer = ByteArray(CHUNK_BYTES)
        private var filled = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val room = buffer.size - filled
                val take = minOf(room, remaining)
                System.arraycopy(b, offset, buffer, filled, take)
                filled += take
                offset += take
                remaining -= take
                if (filled == buffer.size) seal(FLAG_MORE)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            // Always at least one chunk, and always one marked last, so an empty archive is
            // still a file that can be told apart from a truncated one.
            seal(FLAG_LAST)
            destination.flush()
        }

        private fun seal(flag: Byte) {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(chunkContext(headerHash, index, flag))
            val sealed = cipher.doFinal(buffer, 0, filled)
            DataOutputStream(destination).apply {
                writeByte(flag.toInt())
                writeInt(sealed.size)
                write(nonce)
                write(sealed)
                flush()
            }
            filled = 0
            index += 1
        }
    }

    /** The other half: reads chunk frames and hands back plaintext. */
    private class ChunkedDecryptingStream(
        source: InputStream,
        private val key: ByteArray,
        private val headerHash: ByteArray,
    ) : InputStream() {

        private val input = DataInputStream(source)
        private var plain = ByteArray(0)
        private var offset = 0
        private var index = 0L
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (offset >= plain.size && !fill()) return -1
            val take = minOf(len, plain.size - offset)
            System.arraycopy(plain, offset, b, off, take)
            offset += take
            return take
        }

        private fun fill(): Boolean {
            while (true) {
                if (finished) return false
                val flag = runCatching { input.readByte() }.getOrElse {
                    // The stream ran out without a chunk saying it was the last one.
                    throw ArchiveException(ArchiveProblem.DAMAGED)
                }
                if (flag != FLAG_MORE && flag != FLAG_LAST) {
                    throw ArchiveException(ArchiveProblem.DAMAGED)
                }
                val length = input.readIntOrDamaged()
                if (length < TAG_BYTES || length > MAX_CIPHER_CHUNK) {
                    throw ArchiveException(ArchiveProblem.DAMAGED)
                }
                val nonce = input.readSizedOrDamaged(NONCE_BYTES)
                val sealed = input.readSizedOrDamaged(length)
                plain = try {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        GCMParameterSpec(TAG_BITS, nonce),
                    )
                    cipher.updateAAD(chunkContext(headerHash, index, flag))
                    cipher.doFinal(sealed)
                } catch (_: AEADBadTagException) {
                    throw ArchiveException(ArchiveProblem.DAMAGED)
                } catch (_: javax.crypto.BadPaddingException) {
                    throw ArchiveException(ArchiveProblem.DAMAGED)
                } catch (_: javax.crypto.IllegalBlockSizeException) {
                    throw ArchiveException(ArchiveProblem.DAMAGED)
                }
                offset = 0
                index += 1
                if (flag == FLAG_LAST) finished = true
                if (plain.isNotEmpty()) return true
                if (finished) return false
            }
        }
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val COPY_BUFFER = 64 * 1024
    private const val DIGEST_BYTES = 32
    private const val MAX_SLOTS = 8
    private const val MAX_ITERATIONS = 10_000_000
    private const val MIN_SALT_BYTES = 8
    private const val MAX_SALT_BYTES = 64
    private const val MAX_LEAF_LENGTH = 200
}

private fun DataInputStream.readUnsignedByteOrFail(): Int =
    runCatching { readUnsignedByte() }
        .getOrElse { throw ArchiveException(ArchiveProblem.NOT_AN_ARCHIVE) }

private fun DataInputStream.readIntOrFail(): Int =
    runCatching { readInt() }.getOrElse { throw ArchiveException(ArchiveProblem.NOT_AN_ARCHIVE) }

private fun DataInputStream.readUnsignedShortOrFail(): Int =
    runCatching { readUnsignedShort() }
        .getOrElse { throw ArchiveException(ArchiveProblem.NOT_AN_ARCHIVE) }

private fun DataInputStream.readSizedOrFail(length: Int): ByteArray {
    if (length < 0) throw ArchiveException(ArchiveProblem.NOT_AN_ARCHIVE)
    val bytes = ByteArray(length)
    try {
        readFully(bytes)
    } catch (_: EOFException) {
        throw ArchiveException(ArchiveProblem.NOT_AN_ARCHIVE)
    }
    return bytes
}

private fun DataInputStream.readByteOrDamaged(): Byte =
    runCatching { readByte() }.getOrElse { throw ArchiveException(ArchiveProblem.DAMAGED) }

private fun DataInputStream.readIntOrDamaged(): Int =
    runCatching { readInt() }.getOrElse { throw ArchiveException(ArchiveProblem.DAMAGED) }

private fun DataInputStream.readLongOrDamaged(): Long =
    runCatching { readLong() }.getOrElse { throw ArchiveException(ArchiveProblem.DAMAGED) }

private fun DataInputStream.readUnsignedShortOrDamaged(): Int =
    runCatching { readUnsignedShort() }.getOrElse { throw ArchiveException(ArchiveProblem.DAMAGED) }

private fun DataInputStream.readSizedOrDamaged(length: Int): ByteArray {
    if (length < 0) throw ArchiveException(ArchiveProblem.DAMAGED)
    val bytes = ByteArray(length)
    try {
        readFully(bytes)
    } catch (_: EOFException) {
        throw ArchiveException(ArchiveProblem.DAMAGED)
    }
    return bytes
}
