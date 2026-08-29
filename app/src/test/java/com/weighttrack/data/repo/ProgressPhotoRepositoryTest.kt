package com.weighttrack.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

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
        repository = ProgressPhotoRepository(context, database.progressPhotoDao())
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, ProgressPhotoRepository.DIRECTORY_NAME).deleteRecursively()
    }

    private fun writtenCaptureFile(bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        repository.newCaptureFile().apply { writeBytes(bytes) }

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
