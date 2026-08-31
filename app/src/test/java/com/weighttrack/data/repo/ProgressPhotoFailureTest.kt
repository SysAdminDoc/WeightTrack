package com.weighttrack.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.testProfileRepository
import com.weighttrack.diagnostics.RuntimeLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File

/**
 * A progress photo that does not appear, and why.
 *
 * Capture, copy, decode and database failures all collapsed into null. From the screen that is a
 * picture that simply did not turn up, with nothing to read and nothing to do about it: a gallery
 * grant that had already lapsed, a file that is not a picture, a phone with no room left and a
 * refused write all looked exactly the same, and none of them looked like anything.
 */
// Robolectric draws nothing in its default graphics mode: BitmapFactory fabricates an answer
// for whatever it is given, so the check for "this is not a picture" would pass for anything.
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ProgressPhotoFailureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: WeightTrackDatabase
    private lateinit var log: RuntimeLog
    private lateinit var repository: ProgressPhotoRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, WeightTrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        log = RuntimeLog(File(context.cacheDir, "photo-log.txt"))
        log.clear()
        repository = ProgressPhotoRepository(
            context,
            database.progressPhotoDao(),
            testProfileRepository(database),
            log,
        )
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, ProgressPhotoRepository.DIRECTORY_NAME).deleteRecursively()
    }

    private fun files(): List<File> =
        File(context.filesDir, ProgressPhotoRepository.DIRECTORY_NAME).listFiles().orEmpty().toList()

    private fun offer(uri: Uri, bytes: ByteArray?) {
        val resolver = shadowOf(context.contentResolver)
        if (bytes == null) return
        resolver.registerInputStream(uri, ByteArrayInputStream(bytes))
    }

    @Test
    fun `a picture nothing can open says so and keeps nothing`() = runTest {
        // What a lapsed gallery grant looks like: the app is handed an address and there is
        // nothing behind it.
        val outcome = repository.importFrom(Uri.parse("content://gone/1"), weightGrams = null)

        assertThat(outcome).isEqualTo(PhotoOutcome.Failed(PhotoOutcome.Problem.UNREADABLE))
        assertThat(files()).isEmpty()
        assertThat(repository.observeAll().first()).isEmpty()
        assertThat(log.read()).contains("photo_failed")
    }

    @Test
    fun `a file that is not a picture says so and keeps nothing`() = runTest {
        val uri = Uri.parse("content://gallery/not-a-picture")
        offer(uri, "this is a text file, not a photograph".toByteArray())

        val outcome = repository.importFrom(uri, weightGrams = null)

        // A file picker hands over anything at all. A row pointing at something that cannot be
        // drawn is a permanent blank square in the grid with no way to work out why.
        assertThat(outcome).isEqualTo(PhotoOutcome.Failed(PhotoOutcome.Problem.NOT_AN_IMAGE))
        assertThat(files()).isEmpty()
        assertThat(repository.observeAll().first()).isEmpty()
    }

    @Test
    fun `a camera that left nothing behind says so`() = runTest {
        val promised = repository.newCaptureFile()

        val outcome = repository.record(promised, weightGrams = null)

        assertThat(outcome).isEqualTo(PhotoOutcome.Failed(PhotoOutcome.Problem.GONE))
        assertThat(files()).isEmpty()
    }

    @Test
    fun `a database that will not have it keeps no file either`() = runTest {
        val refusing = ProgressPhotoRepository(
            context,
            RefusingPhotoDao(database.progressPhotoDao()),
            testProfileRepository(database),
            log,
        )
        val file = refusing.newCaptureFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val outcome = refusing.record(file, weightGrams = null)

        // An image nothing points at is an orphan: nobody will ever find it, see it or clear it.
        assertThat(outcome).isEqualTo(PhotoOutcome.Failed(PhotoOutcome.Problem.NOT_SAVED))
        assertThat(files()).isEmpty()
        assertThat(log.read()).contains("photo_failed")
    }

    @Test
    fun `a phone with no room left says that, not that the file is wrong`() = runTest {
        val uri = Uri.parse("content://gallery/no-room")
        // What a full phone actually throws part way through a copy.
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            object : java.io.InputStream() {
                override fun read(): Int =
                    throw java.io.IOException("write failed: ENOSPC (No space left on device)")
            },
        )

        val outcome = repository.importFrom(uri, weightGrams = null)

        // "Free some space" and "that file is not a picture" are different problems with
        // different answers, and telling somebody the wrong one sends them looking in the wrong
        // place. Half a copy is not left behind either.
        assertThat(outcome).isEqualTo(PhotoOutcome.Failed(PhotoOutcome.Problem.NO_ROOM))
        assertThat(files()).isEmpty()
        assertThat(repository.observeAll().first()).isEmpty()
        assertThat(log.read()).contains("photo_failed")
    }

    @Test
    fun `an image that goes after the row is written leaves no row behind`() = runTest {
        val directory = File(context.filesDir, ProgressPhotoRepository.DIRECTORY_NAME)
        val vanishing = ProgressPhotoRepository(
            context,
            VanishingPhotoDao(database.progressPhotoDao(), directory),
            testProfileRepository(database),
            log,
        )
        val file = vanishing.newCaptureFile().apply { writeBytes(onePixelJpeg()) }

        val outcome = vanishing.record(file, weightGrams = null)

        // Reporting a failure and keeping the row means the retry produces two pictures, one of
        // which is a permanent blank tile pointing at nothing.
        assertThat(outcome).isEqualTo(PhotoOutcome.Failed(PhotoOutcome.Problem.NOT_SAVED))
        assertThat(vanishing.observeAll().first()).isEmpty()
        assertThat(database.progressPhotoDao().all()).isEmpty()
        assertThat(files()).isEmpty()
    }

    @Test
    fun `trying again after a failure keeps one picture, not two`() = runTest {
        val uri = Uri.parse("content://gallery/broken")
        offer(uri, "not a photograph".toByteArray())
        repository.importFrom(uri, weightGrams = null)

        val good = Uri.parse("content://gallery/good")
        offer(good, onePixelJpeg())
        val second = repository.importFrom(good, weightGrams = null)

        assertThat(second).isInstanceOf(PhotoOutcome.Saved::class.java)
        assertThat(repository.observeAll().first()).hasSize(1)
        assertThat(files()).hasSize(1)
    }

    /** The smallest thing Android will decode as an image. */
    private fun onePixelJpeg(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(
            1,
            1,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /** A row that commits, with the image gone by the time anything reads it back. */
    private class VanishingPhotoDao(
        private val real: com.weighttrack.data.db.ProgressPhotoDao,
        private val directory: File,
    ) : com.weighttrack.data.db.ProgressPhotoDao by real {
        override suspend fun insert(
            photo: com.weighttrack.data.db.ProgressPhotoEntity,
        ): Long {
            val id = real.insert(photo)
            File(directory, photo.fileName).delete()
            return id
        }
    }

    /** A database that refuses the row, which is what a full or damaged one does. */
    private class RefusingPhotoDao(
        private val real: com.weighttrack.data.db.ProgressPhotoDao,
    ) : com.weighttrack.data.db.ProgressPhotoDao by real {
        override suspend fun insert(
            photo: com.weighttrack.data.db.ProgressPhotoEntity,
        ): Long = error("the table would not take it")
    }
}
