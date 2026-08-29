package com.weighttrack.data.repo

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.testProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class ProgressPhotoRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: WeightTrackDatabase
    private lateinit var repository: ProgressPhotoRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, WeightTrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProgressPhotoRepository(context, database.progressPhotoDao(), testProfileRepository(database))
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, ProgressPhotoRepository.DIRECTORY_NAME).deleteRecursively()
    }

    private fun writtenCaptureFile(bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        repository.newCaptureFile().apply { writeBytes(bytes) }

    @Test
    fun `an exif date is read as the camera's own wall clock, not as UTC`() {
        // A photo taken just after midnight in a western zone belongs on the day the camera
        // printed on it. Reading the string as UTC would file it on the day before.
        val taken = parseExifDateTime("2026:03:14 00:30:00", ZoneId.of("America/New_York"))

        assertThat(taken).isEqualTo(Instant.parse("2026-03-14T04:30:00Z"))
        assertThat(taken!!.atZone(ZoneId.of("America/New_York")).toLocalDate().toString())
            .isEqualTo("2026-03-14")
    }

    @Test
    fun `an unset camera clock is reported as no date at all`() {
        val zone = ZoneId.of("UTC")
        assertThat(parseExifDateTime("0000:00:00 00:00:00", zone)).isNull()
        assertThat(parseExifDateTime("   ", zone)).isNull()
        assertThat(parseExifDateTime(null, zone)).isNull()
        assertThat(parseExifDateTime("2026-03-14 10:00:00", zone)).isNull()
    }

    @Test
    fun `a capture time that could not have happened is dropped`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")

        assertThat(plausibleCaptureTime(Instant.parse("2026-03-14T10:00:00Z"), now))
            .isEqualTo(Instant.parse("2026-03-14T10:00:00Z"))
        // A camera whose clock was never set, and one set to the wrong year.
        assertThat(plausibleCaptureTime(Instant.EPOCH, now)).isNull()
        assertThat(plausibleCaptureTime(Instant.parse("2030-01-01T00:00:00Z"), now)).isNull()
        assertThat(plausibleCaptureTime(null, now)).isNull()
    }

    @Test
    fun `an imported image is dated from its exif rather than the import`() = runTest {
        val zone = ZoneId.of("UTC")
        val source = File(context.cacheDir, "picked.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            ExifInterface(absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:03:14 09:15:00")
                saveAttributes()
            }
        }

        val takenAt = repository.takenAt(
            Uri.fromFile(source),
            zone = zone,
            now = Instant.parse("2026-08-29T12:00:00Z"),
        )

        assertThat(takenAt).isEqualTo(Instant.parse("2026-03-14T09:15:00Z"))
        source.delete()
    }

    @Test
    fun `an image with nothing to date it reports no capture time`() = runTest {
        val source = File(context.cacheDir, "plain.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertThat(repository.takenAt(Uri.fromFile(source))).isNull()
        source.delete()
    }

    @Test
    fun `a captured photo is recorded and listed`() = runTest {
        val file = writtenCaptureFile()
        val photo = repository.record(file, weightGrams = 82_500, timestamp = Instant.ofEpochMilli(1_000))

        assertThat(photo).isNotNull()
        assertThat(photo!!.weightGrams).isEqualTo(82_500)
        assertThat(repository.observeAll().first()).hasSize(1)
    }

    @Test
    fun `a capture that produced no bytes is refused rather than listed as a blank tile`() = runTest {
        // A cancelled camera leaves an empty file behind.
        val empty = repository.newCaptureFile().apply { createNewFile() }
        assertThat(repository.record(empty, weightGrams = null)).isNull()
        assertThat(repository.observeAll().first()).isEmpty()
        assertThat(empty.exists()).isFalse()
    }

    @Test
    fun `a file that never existed is refused`() = runTest {
        val missing = repository.newCaptureFile()
        assertThat(repository.record(missing, weightGrams = null)).isNull()
        assertThat(repository.observeAll().first()).isEmpty()
    }

    @Test
    fun `deleting a photo removes the image as well as the row`() = runTest {
        val photo = repository.record(writtenCaptureFile(), weightGrams = 80_000)!!
        val file = photo.file
        assertThat(file.exists()).isTrue()

        repository.delete(photo)

        assertThat(repository.observeAll().first()).isEmpty()
        // Leaving the image behind would fill storage with pictures nothing references.
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a row whose image has gone is not listed`() = runTest {
        val photo = repository.record(writtenCaptureFile(), weightGrams = 80_000)!!
        // Something outside the app removed the file, or a restore brought the row back
        // without it. Listing it would render an empty tile that cannot be opened.
        photo.file.delete()

        assertThat(repository.observeAll().first()).isEmpty()
    }

    @Test
    fun `listing resolves photo files away from the collector thread`() = runTest {
        val fileLookups = CopyOnWriteArrayList<Thread>()
        val checkingContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File {
                fileLookups += Thread.currentThread()
                return super.getFilesDir()
            }
        }
        val checkingRepository = ProgressPhotoRepository(
            checkingContext,
            database.progressPhotoDao(),
            testProfileRepository(database),
        )
        checkingRepository.record(
            checkingRepository.newCaptureFile().apply { writeBytes(byteArrayOf(1, 2, 3)) },
            weightGrams = 80_000,
        )
        fileLookups.clear()
        val collectorThread = Thread.currentThread()

        assertThat(checkingRepository.observeAll().first()).hasSize(1)

        assertThat(fileLookups).isNotEmpty()
        assertThat(fileLookups).doesNotContain(collectorThread)
    }

    @Test
    fun `photos are listed newest first`() = runTest {
        repository.record(writtenCaptureFile(), weightGrams = 1_000, timestamp = Instant.ofEpochMilli(1_000))
        repository.record(writtenCaptureFile(), weightGrams = 3_000, timestamp = Instant.ofEpochMilli(3_000))
        repository.record(writtenCaptureFile(), weightGrams = 2_000, timestamp = Instant.ofEpochMilli(2_000))

        assertThat(repository.observeAll().first().map { it.weightGrams })
            .containsExactly(3_000, 2_000, 1_000)
            .inOrder()
    }

    @Test
    fun `every capture gets its own file`() {
        val names = (1..5).map { repository.newCaptureFile().name }.toSet()
        assertThat(names).hasSize(5)
    }

    @Test
    fun `clearing removes every photo and its image`() = runTest {
        val first = repository.record(writtenCaptureFile(), weightGrams = 1_000)!!
        val second = repository.record(writtenCaptureFile(), weightGrams = 2_000)!!

        repository.deleteAll()

        assertThat(repository.observeAll().first()).isEmpty()
        assertThat(first.file.exists()).isFalse()
        assertThat(second.file.exists()).isFalse()
    }

    @Test
    fun `a photo taken with no weight logged still records`() = runTest {
        val photo = repository.record(writtenCaptureFile(), weightGrams = null)
        assertThat(photo).isNotNull()
        assertThat(photo!!.weightGrams).isNull()
    }
}
