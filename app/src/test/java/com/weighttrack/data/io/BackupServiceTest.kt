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
            syncStore = SyncStore(database, database.syncDao(), database.deletionDao()),
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

    @Test
    fun `the preview reports what is in the file and writes nothing`() = runTest {
        seedTwoProfiles()
        val file = write(serviceFor(source).exportedJson().getOrThrow())

        val preview = serviceFor(target).previewJson(Uri.fromFile(file)).getOrThrow()

        assertThat(preview.formatVersion).isEqualTo(2)
        assertThat(preview.profiles).isEqualTo(2)
        assertThat(preview.weights).isEqualTo(2)
        assertThat(target.syncDao().profiles()).isEmpty()
        assertThat(target.syncDao().weights()).isEmpty()
    }

    @Test
    fun `a file that is not a backup is refused and changes nothing`() = runTest {
        val file = write("date,weight_kg\n2026-08-29,80.0\n")

        val result = serviceFor(target).importJson(Uri.fromFile(file))

        assertThat(result.isFailure).isTrue()
        assertThat(target.syncDao().weights()).isEmpty()
        assertThat(target.syncDao().profiles()).isEmpty()
    }

    @Test
    fun `a truncated backup is refused and changes nothing`() = runTest {
        seedTwoProfiles()
        val whole = serviceFor(source).exportedJson().getOrThrow()
        val file = write(whole.take(whole.length / 2))

        val result = serviceFor(target).importJson(Uri.fromFile(file))

        assertThat(result.isFailure).isTrue()
        assertThat(target.syncDao().weights()).isEmpty()
        assertThat(target.syncDao().profiles()).isEmpty()
    }

    @Test
    fun `a backup from a newer version is refused rather than half understood`() = runTest {
        seedTwoProfiles()
        val whole = serviceFor(source).exportedJson().getOrThrow()
        val fromTheFuture = whole.replace(
            "\"formatVersion\": ${BackupCodec.FORMAT_VERSION}",
            "\"formatVersion\": ${BackupCodec.FORMAT_VERSION + 1}",
        )
        val file = write(fromTheFuture)

        val result = serviceFor(target).importJson(Uri.fromFile(file))

        // Reading the parts it recognises and dropping the rest would look like it had worked.
        assertThat(result.isFailure).isTrue()
        assertThat(target.syncDao().profiles()).isEmpty()
        assertThat(serviceFor(target).previewJson(Uri.fromFile(file)).isFailure).isTrue()
    }

    @Test
    fun `a file too large to be a backup is refused before it is all read`() = runTest {
        seedTwoProfiles()
        // A perfectly valid backup, padded past the ceiling. Anything malformed would be refused
        // by the parser anyway and would prove nothing about the ceiling.
        val whole = serviceFor(source).exportedJson().getOrThrow()
        val padded = whole.replace(
            BackupCodec.PHOTOS_NOT_INCLUDED,
            "x".repeat((BackupService.MAX_BACKUP_BYTES + 1024).toInt()),
        )
        val file = write(padded)

        val result = serviceFor(target).importJson(Uri.fromFile(file))

        assertThat(file.length()).isGreaterThan(BackupService.MAX_BACKUP_BYTES)
        assertThat(result.isFailure).isTrue()
        assertThat(target.syncDao().profiles()).isEmpty()
        assertThat(target.syncDao().weights()).isEmpty()
    }

    @Test
    fun `a restore that fails partway through changes no row at all`() = runTest {
        seedTwoProfiles()
        val whole = serviceFor(source).exportedJson().getOrThrow()
        // Two weigh-ins with one name inside one profile. The table refuses the second, and the
        // profiles have already been written by the time it does.
        val broken = whole.replace("\"syncId\": \"w-them\"", "\"syncId\": \"w-me\"")
            .replace("\"clientRecordId\": \"w-them\"", "\"clientRecordId\": \"w-me\"")
            .replace("\"profileSyncId\": \"p-them\"", "\"profileSyncId\": \"p-me\"")
        val file = write(broken)

        val result = serviceFor(target).importJson(Uri.fromFile(file))

        assertThat(result.isFailure).isTrue()
        assertThat(target.syncDao().profiles()).isEmpty()
        assertThat(target.syncDao().weights()).isEmpty()
    }

    private fun write(text: String): File =
        temporary.newFile("weighttrack.json").apply { writeText(text) }
}
