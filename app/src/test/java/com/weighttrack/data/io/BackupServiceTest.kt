package com.weighttrack.data.io

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.WeightEntryEntity
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.MeasurementRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.sync.SyncStore
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.core.model.WeightUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The service that actually writes the file, not just the format underneath it.
 *
 * [BackupRoundTripTest] proves the document survives the codec and the store. This proves the
 * export puts it in the file at all and the restore reads it, which is the wiring that a
 * complete format is useless without.
 */
@RunWith(RobolectricTestRunner::class)
class BackupServiceTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var source: WeightTrackDatabase
    private lateinit var target: WeightTrackDatabase

    private val now = 1_800_000_000_000L

    private fun database(): WeightTrackDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        WeightTrackDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Before
    fun setUp() {
        source = database()
        target = database()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun serviceFor(
        database: WeightTrackDatabase,
        settings: SettingsRepository = testSettingsRepository(),
    ): BackupService {
        val profiles = ProfileRepository(
            database.profileDao(),
            settings,
            DeletionRecorder(database.deletionDao(), database.syncDao()),
            database.weightEntryDao(),
        )
        val deletions = DeletionRecorder(database.deletionDao(), database.syncDao())
        return BackupService(
            syncStore = SyncStore(database.syncDao(), database.deletionDao()),
            context = ApplicationProvider.getApplicationContext(),
            weightRepository = WeightRepository(database.weightEntryDao(), profiles, deletions),
            measurementRepository = MeasurementRepository(
                database.measurementDao(),
                profiles,
                deletions,
            ),
            goalRepository = GoalRepository(database.goalDao(), profiles, deletions),
            settingsRepository = settings,
        )
    }

    /** Two people, each with a reading, so an export that only sees one is visible. */
    private suspend fun seedTwoProfiles() {
        val dao = source.syncDao()
        val me = dao.insertProfile(
            ProfileEntity(
                name = "Me",
                position = 0,
                createdAtUtcMillis = now - 100_000,
                syncId = "p-me",
                updatedAtUtcMillis = now - 10_000,
            ),
        )
        val them = dao.insertProfile(
            ProfileEntity(
                name = "Them",
                position = 1,
                createdAtUtcMillis = now - 90_000,
                syncId = "p-them",
                updatedAtUtcMillis = now - 10_000,
            ),
        )
        dao.insertWeights(listOf(weight(me, "w-me", 80_000), weight(them, "w-them", 61_250)))
    }

    private fun weight(profileId: Long, recordId: String, grams: Int) = WeightEntryEntity(
        profileId = profileId,
        timestampUtcMillis = now - 50_000,
        zoneOffsetSeconds = 0,
        localDate = "2026-08-29",
        grams = grams,
        bodyFatPercent = null,
        note = null,
        tags = "",
        source = "MANUAL",
        clientRecordId = recordId,
        healthConnectId = null,
        updatedAtUtcMillis = now - 10_000,
    )

    @Test
    fun `the export carries every profile, not just the open one`() = runTest {
        seedTwoProfiles()

        val text = serviceFor(source).exportedJson().getOrThrow()
        val backup = checkNotNull(BackupCodec.decode(text))

        val document = checkNotNull(backup.document)
        assertThat(document.profiles.map { it.syncId }).containsExactly("p-me", "p-them")
        assertThat(document.weights.map { it.syncId }).containsExactly("w-me", "w-them")
        assertThat(document.settings).isNotNull()
        assertThat(backup.formatVersion).isEqualTo(2)
    }

    @Test
    fun `a restore puts both people back on a phone that has neither`() = runTest {
        seedTwoProfiles()
        val settings = testSettingsRepository()
        settings.setWeightUnit(WeightUnit.LB)
        val file = write(serviceFor(source, settings).exportedJson().getOrThrow())

        val summary = serviceFor(target).importJson(Uri.fromFile(file)).getOrThrow()

        assertThat(summary.imported).isEqualTo(2)
        val dao = target.syncDao()
        assertThat(dao.profiles().map { it.syncId }).containsExactly("p-me", "p-them")
        val nameOf = dao.profiles().associate { it.id to it.syncId }
        assertThat(dao.weights().associate { it.clientRecordId to nameOf[it.profileId] })
            .containsExactly("w-me", "p-me", "w-them", "p-them")
    }

    @Test
    fun `a restore brings the units and the rest of the settings with it`() = runTest {
        seedTwoProfiles()
        val exporting = testSettingsRepository()
        exporting.setWeightUnit(WeightUnit.LB)
        exporting.setTrendWindowDays(21)
        val file = write(serviceFor(source, exporting).exportedJson().getOrThrow())

        val importing = testSettingsRepository()
        serviceFor(target, importing).importJson(Uri.fromFile(file)).getOrThrow()

        val restored = importing.settings.first()
        assertThat(restored.weightUnit).isEqualTo(WeightUnit.LB)
        assertThat(restored.trendWindowDays).isEqualTo(21)
    }

    private fun write(text: String): File =
        temporary.newFile("weighttrack.json").apply { writeText(text) }
}
