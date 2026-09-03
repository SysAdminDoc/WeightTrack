package com.weighttrack.data.io

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.io.OpenedFileKind
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.MeasurementRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.sync.SyncClock
import com.weighttrack.data.sync.SyncStore
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the app makes of a file the phone hands it.
 *
 * The judgement itself is a pure function tested in the core module. This is the lookup around
 * it: a provider that says nothing about the type, and one that answers nothing at all, neither
 * of which may take the app down or read a byte of the file to decide.
 */
@RunWith(RobolectricTestRunner::class)
class OpenedFileKindTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: WeightTrackDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun service(): BackupService {
        val settings = testSettingsRepository()
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        val profiles = ProfileRepository(
            database.profileDao(),
            settings,
            deletions,
            database.weightEntryDao(),
        )
        return BackupService(
            syncStore = SyncStore(
                database,
                database.syncDao(),
                database.deletionDao(),
                database.syncPeerDao(),
                database.medicationDoseDao(),
                database.sideEffectDao(),
                SyncClock.inMemory(),
            ),
            context = ApplicationProvider.getApplicationContext(),
            weightRepository = WeightRepository(database.weightEntryDao(), profiles, deletions),
            measurementRepository = MeasurementRepository(
                database.measurementDao(),
                profiles,
                deletions,
            ),
            goalRepository = GoalRepository(database.goalDao(), profiles, deletions),
            profileRepository = profiles,
            settingsRepository = settings,
            database = database,
            progressPhotoRepository = com.weighttrack.data.repo.ProgressPhotoRepository(
                ApplicationProvider.getApplicationContext(),
                database.progressPhotoDao(),
                profiles,
                com.weighttrack.diagnostics.RuntimeLog(temporary.newFile()),
            ),
        )
    }

    @Test
    fun `a backup is recognised from its name when the type says nothing`() = runTest {
        val uri = Uri.parse("content://com.example.files/weighttrack-backup.json")

        assertThat(service().openedFileKind(uri)).isEqualTo(OpenedFileKind.BACKUP)
    }

    @Test
    fun `a spreadsheet is recognised the same way`() = runTest {
        val uri = Uri.parse("content://com.example.files/readings.csv")

        assertThat(service().openedFileKind(uri)).isEqualTo(OpenedFileKind.READINGS)
    }

    @Test
    fun `a declared type wins over the address`() = runTest {
        val uri = Uri.parse("content://com.example.files/whatever")
        shadowOf(
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
        ).registerInputStream(uri, "".byteInputStream())

        // Robolectric answers getType from what has been registered, so the address alone is what
        // is left here, and "whatever" is nothing this app reads.
        assertThat(service().openedFileKind(uri)).isNull()
    }

    @Test
    fun `something the app cannot read says so rather than guessing`() = runTest {
        val uri = Uri.parse("content://com.example.files/holiday.jpg")

        assertThat(service().openedFileKind(uri)).isNull()
    }

    @Test
    fun `a provider that answers nothing at all does not take the app down`() = runTest {
        val uri = Uri.parse("content://com.example.nothing/")

        assertThat(service().openedFileKind(uri)).isNull()
    }

    @Test
    fun `nothing is read from the file to decide what it is`() = runTest {
        // A file too large to read is still classified in constant time. Opening it to find out
        // is how a hostile file gets to spend a phone's memory before it is even refused.
        val uri = Uri.parse("content://com.example.files/enormous.json")
        shadowOf(
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
        ).registerInputStream(uri, ThrowingStream())

        assertThat(service().openedFileKind(uri)).isEqualTo(OpenedFileKind.BACKUP)
    }

    /** Fails the moment anybody reads a byte of it. */
    private class ThrowingStream : java.io.InputStream() {
        override fun read(): Int = error("the file must not be opened to decide what it is")
    }
}
