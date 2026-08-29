package com.weighttrack.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.health.connect.client.units.Mass
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.diagnostics.RuntimeLog
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Inheriting a history that somebody's scale app has been keeping for years.
 *
 * Health Connect answers a read one page at a time. The app used to take the first page as the
 * whole answer, report a confident count and leave the rest behind, which is the worst shape a
 * bug can take: the screen says it worked.
 */
@RunWith(RobolectricTestRunner::class)
class HealthConnectImportTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository

    @get:org.junit.Rule
    val temporary = org.junit.rules.TemporaryFolder()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = testSettingsRepository()
        val deletions = DeletionRecorder(database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(database.profileDao(), settings, deletions, database.weightEntryDao())
        weights = WeightRepository(database.weightEntryDao(), profiles, deletions)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * One reading an hour going backwards.
     *
     * Hours rather than days so that thousands of them still land inside the five-year window the
     * sync asks for. Spacing them a day apart put the oldest beyond it, which looked exactly like
     * a paging bug and was not one.
     */
    private fun weightRecord(index: Int): WeightRecord {
        val time = START.minus(index.toLong(), ChronoUnit.HOURS)
        return WeightRecord(
            time = time,
            zoneOffset = null,
            weight = Mass.kilograms(80.0 + (index % 40) * 0.05),
            metadata = Metadata.manualEntry(clientRecordId = "scale-$index"),
        )
    }

    private suspend fun syncWith(records: List<WeightRecord>): HealthConnectSyncResult {
        val permissions = FakePermissionController()
        permissions.grantPermissions(
            setOf(
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getWritePermission(WeightRecord::class),
                HealthPermission.getReadPermission(
                    androidx.health.connect.client.records.BodyFatRecord::class,
                ),
                HealthPermission.getWritePermission(
                    androidx.health.connect.client.records.BodyFatRecord::class,
                ),
                HealthPermission.getReadPermission(
                    androidx.health.connect.client.records.HeightRecord::class,
                ),
            ),
        )
        val fake = FakeHealthConnectClient(permissionController = permissions)
        fake.insertRecords(records)
        profiles.ensureDefault()
        val sync = HealthConnectSync(
            context = ApplicationProvider.getApplicationContext(),
            weightRepository = weights,
            settingsRepository = settings,
            profileRepository = profiles,
            runtimeLog = RuntimeLog(File(temporary.root, "log.txt")),
            clientSource = { fake },
        )
        return sync.sync().getOrThrow()
    }

    @Test
    fun `two and a half thousand readings all arrive`() = runTest {
        // Comfortably over one page, so a read that stops at the first would leave 1,500 behind
        // and say nothing about it.
        val everything = (0 until 2_500).map(::weightRecord)

        val result = syncWith(everything)

        assertThat(result.imported).isEqualTo(2_500)
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(2_500)
    }

    @Test
    fun `a history that fits in one page still arrives`() = runTest {
        val result = syncWith((0 until 12).map(::weightRecord))

        assertThat(result.imported).isEqualTo(12)
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(12)
    }

    @Test
    fun `an empty Health Connect imports nothing and does not complain`() = runTest {
        val result = syncWith(emptyList())

        assertThat(result.imported).isEqualTo(0)
        assertThat(weights.entriesFor(profiles.activeId())).isEmpty()
    }

    @Test
    fun `the readings that arrive keep their own weights`() = runTest {
        syncWith((0 until 1_400).map(::weightRecord))

        // Spread across the page boundary, so a second page arriving mangled or duplicated would
        // show up here rather than only in the count.
        val grams = weights.entriesFor(profiles.activeId()).map { it.grams }.sorted()
        assertThat(grams.distinct()).hasSize(40)
        assertThat(grams.first()).isEqualTo(80_000)
        assertThat(grams.last()).isEqualTo(81_950)
    }

    @Test
    fun `syncing twice does not double the history`() = runTest {
        val everything = (0 until 1_100).map(::weightRecord)
        syncWith(everything)

        // A fresh client, the same records: what a second sync looks like from the app's side.
        syncWith(everything)

        assertThat(weights.entriesFor(profiles.activeId())).hasSize(1_100)
    }

    private companion object {
        val START: Instant = Instant.parse("2026-08-29T07:00:00Z")
    }
}
