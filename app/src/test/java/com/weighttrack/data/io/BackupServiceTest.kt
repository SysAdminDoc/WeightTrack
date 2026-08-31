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
            DeletionRecorder(database, database.deletionDao(), database.syncDao()),
            database.weightEntryDao(),
        )
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
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

    @Test
    fun `restoring onto a new phone leaves the person on their own history`() = runTest {
        seedTwoProfiles()
        val file = write(serviceFor(source).exportedJson().getOrThrow())
        // What a real new phone looks like: the app makes a profile on first start, so a restore
        // never meets an empty database.
        val settings = testSettingsRepository()
        val profiles = ProfileRepository(
            target.profileDao(),
            settings,
            DeletionRecorder(target, target.deletionDao(), target.syncDao()),
            target.weightEntryDao(),
        )
        profiles.ensureDefault()

        serviceFor(target, settings).importJson(Uri.fromFile(file)).getOrThrow()

        // Two people restored, not three, and the person is looking at one of them rather than
        // at the empty profile the app made for itself a minute earlier.
        val restored = target.syncDao().profiles()
        assertThat(restored.map { it.syncId }).containsExactly("p-me", "p-them")
        assertThat(profiles.activeId()).isEqualTo(restored.single { it.syncId == "p-me" }.id)
    }

    @Test
    fun `restoring onto a phone in use adds to it and takes nothing away`() = runTest {
        seedTwoProfiles()
        val file = write(serviceFor(source).exportedJson().getOrThrow())
        val settings = testSettingsRepository()
        val profiles = ProfileRepository(
            target.profileDao(),
            settings,
            DeletionRecorder(target, target.deletionDao(), target.syncDao()),
            target.weightEntryDao(),
        )
        profiles.ensureDefault()
        val mine = profiles.observeAll().first().single().id
        target.syncDao().insertWeights(listOf(weight(mine, "w-mine", 70_000)))

        serviceFor(target, settings).importJson(Uri.fromFile(file)).getOrThrow()

        // Somebody who has been using the app is being merged into. Their profile is theirs and
        // the restore has no business tidying it away, however empty the backup thinks it is.
        assertThat(target.syncDao().profiles()).hasSize(3)
        assertThat(profiles.activeId()).isEqualTo(mine)
    }

    @Test
    fun `a phone whose only content is a photograph is not treated as untouched`() = runTest {
        seedTwoProfiles()
        val file = write(serviceFor(source).exportedJson().getOrThrow())
        val settings = testSettingsRepository()
        val profiles = ProfileRepository(
            target.profileDao(),
            settings,
            DeletionRecorder(target, target.deletionDao(), target.syncDao()),
            target.weightEntryDao(),
        )
        profiles.ensureDefault()
        val mine = profiles.observeAll().first().single().id
        // Photographs are not in the sync document, so nothing that reasons about a profile from
        // its snapshot can see them. A progress photo is content, and deleting the profile that
        // holds it takes the pictures with it and leaves the files behind on the phone.
        target.progressPhotoDao().insert(
            com.weighttrack.data.db.ProgressPhotoEntity(
                profileId = mine,
                timestampUtcMillis = now,
                localDate = "2026-08-29",
                fileName = "front.jpg",
                weightGrams = null,
                note = null,
            ),
        )

        serviceFor(target, settings).importJson(Uri.fromFile(file)).getOrThrow()

        assertThat(target.syncDao().profiles()).hasSize(3)
        assertThat(profiles.activeId()).isEqualTo(mine)
    }

    @Test
    fun `an older backup brings its height and its year of birth back`() = runTest {
        val settings = testSettingsRepository()
        val profiles = ProfileRepository(
            target.profileDao(),
            settings,
            DeletionRecorder(target, target.deletionDao(), target.syncDao()),
            target.weightEntryDao(),
        )
        profiles.ensureDefault()
        val file = write(VERSION_ONE)

        serviceFor(target, settings).importJson(Uri.fromFile(file)).getOrThrow()

        // Every backup taken before the demographics moved onto the profile carries them once,
        // beside the settings, where nothing reads them any more. Without this, restoring one
        // brought the weigh-ins and the goals back and left the BMI, the healthy range, the
        // body-fat estimate and the daily burn behind with nothing said about it.
        val restored = profiles.observeAll().first().single().demographics
        assertThat(restored.heightMm).isEqualTo(1_803)
        assertThat(restored.birthYear).isEqualTo(1988)
        assertThat(restored.sex).isEqualTo(com.weighttrack.core.model.Sex.FEMALE)
        assertThat(restored.activityLevel)
            .isEqualTo(com.weighttrack.core.model.ActivityLevel.ACTIVE)
    }

    @Test
    fun `a backup does not overwrite a body somebody has already given`() = runTest {
        val settings = testSettingsRepository()
        val profiles = ProfileRepository(
            target.profileDao(),
            settings,
            DeletionRecorder(target, target.deletionDao(), target.syncDao()),
            target.weightEntryDao(),
        )
        profiles.ensureDefault()
        val mine = profiles.observeAll().first().single().id
        profiles.setDemographics(
            mine,
            com.weighttrack.core.model.UserProfile(heightMm = 1_700, birthYear = 1990),
        )
        val file = write(VERSION_ONE)

        serviceFor(target, settings).importJson(Uri.fromFile(file)).getOrThrow()

        // The per-profile value is the better answer wherever it exists, and one figure from an
        // old file must not be handed to somebody it does not describe.
        assertThat(profiles.observeAll().first().single().demographics.heightMm).isEqualTo(1_700)
    }

    @Test
    fun `a version one restore is one commit like the rest`() {
        // No version-1 file can be made to break a constraint: nothing its four sections reach
        // has a unique index, so there is no failure to inject and the behaviour cannot be
        // driven from outside. What can be checked is that every write it makes is inside the
        // transaction, which is what stops a database error partway through leaving the
        // weigh-ins in and the diary out.
        val source = File("src/main/java/com/weighttrack/data/io/BackupService.kt").readText()
        val open = source.indexOf("database.withTransaction {")
        assertThat(open).isGreaterThan(-1)
        val commit = source.substring(open, closingBraceAfter(source, open))

        assertThat(commit).contains("weightRepository.upsertAll")
        assertThat(commit).contains("measurementRepository.upsertAll")
        assertThat(commit).contains("goalRepository.setGoal")
        assertThat(commit).contains("syncStore.apply")
        // The active profile is resolved before the transaction. It comes off a flow, and a flow
        // read inside a write transaction waits on the connection that transaction holds.
        assertThat(source.indexOf("profileRepository.activeId()")).isLessThan(open)
        assertThat(commit).doesNotContain("activeId()")
    }

    private fun closingBraceAfter(source: String, open: Int): Int {
        var depth = 0
        var index = source.indexOf('{', open)
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        error("the transaction is never closed")
    }

    @Test
    fun `a version one file still restores`() = runTest {
        val settings = testSettingsRepository()
        val profiles = ProfileRepository(
            target.profileDao(),
            settings,
            DeletionRecorder(target, target.deletionDao(), target.syncDao()),
            target.weightEntryDao(),
        )
        profiles.ensureDefault()
        val file = write(VERSION_ONE)

        val summary = serviceFor(target, settings).importJson(Uri.fromFile(file)).getOrThrow()

        assertThat(summary.imported).isEqualTo(1)
        assertThat(target.syncDao().weights()).hasSize(1)
        assertThat(target.syncDao().measurements()).hasSize(1)
        assertThat(target.syncDao().goals()).hasSize(1)
        assertThat(settings.settings.first().weightUnit).isEqualTo(WeightUnit.LB)
    }

    private fun write(text: String): File =
        temporary.newFile("weighttrack.json").apply { writeText(text) }

    private companion object {
        /** What 0.4.0 and earlier wrote. */
        val VERSION_ONE = """
            {
              "app": "WeightTrack",
              "formatVersion": 1,
              "exportedAtUtcMillis": 1750000000000,
              "entries": [
                {
                  "timestampUtcMillis": 1750000000000,
                  "zoneOffsetSeconds": 0,
                  "localDate": "2026-06-15",
                  "grams": 80000,
                  "clientRecordId": "old-1"
                }
              ],
              "measurements": [
                {
                  "timestampUtcMillis": 1750000000000,
                  "localDate": "2026-06-15",
                  "type": "WAIST",
                  "valueMm": 900
                }
              ],
              "goals": [
                {
                  "direction": "LOSE",
                  "startGrams": 84000,
                  "targetGrams": 76000,
                  "startDate": "2026-06-01",
                  "milestoneStepGrams": 2000,
                  "active": true
                }
              ],
              "settings": {
                "weightUnit": "LB",
                "lengthUnit": "IN",
                "themeMode": "AMOLED",
                "heightMm": 1803,
                "sex": "FEMALE",
                "birthYear": 1988,
                "activityLevel": "ACTIVE",
                "trendWindowDays": 21,
                "milestoneStepGrams": 2500
              }
            }
        """.trimIndent()
    }
}
