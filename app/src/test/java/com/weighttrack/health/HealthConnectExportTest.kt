package com.weighttrack.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.EntrySource
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.diagnostics.RuntimeLog
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * What the app writes into somebody else's health record.
 *
 * The export used to send every reading ever taken on every run. An hour apart, for ever: five
 * years of weigh-ins is fifteen hundred records rewritten every time to say nothing new. And it
 * only ever added, so a reading deleted here stayed in Health Connect for good, where the phone's
 * owner reads it as current.
 */
@RunWith(RobolectricTestRunner::class)
class HealthConnectExportTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository
    private lateinit var recorder: DeletionRecorder
    private lateinit var client: CountingClient

    /** Counts what was written and what was asked to be removed. */
    private class CountingClient(
        private val real: HealthConnectClient,
        var refuseWrites: Boolean = false,
    ) : HealthConnectClient by real {
        var inserted = 0
        val deletedClientIds = mutableListOf<String>()

        override suspend fun insertRecords(
            records: List<Record>,
        ): androidx.health.connect.client.response.InsertRecordsResponse {
            if (refuseWrites) error("the provider would not take them")
            inserted += records.size
            return real.insertRecords(records)
        }

        override suspend fun deleteRecords(
            recordType: KClass<out Record>,
            recordIdsList: List<String>,
            clientRecordIdsList: List<String>,
        ) {
            deletedClientIds += clientRecordIdsList
            real.deleteRecords(recordType, recordIdsList, clientRecordIdsList)
        }
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = testSettingsRepository()
        recorder = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(
            database.profileDao(),
            settings,
            recorder,
            database.weightEntryDao(),
        )
        weights = WeightRepository(database.weightEntryDao(), profiles, recorder)
        val permissions = FakePermissionController()
        permissions.grantPermissions(
            setOf(
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getWritePermission(WeightRecord::class),
            ),
        )
        client = CountingClient(FakeHealthConnectClient(permissionController = permissions))
    }

    @After
    fun tearDown() = database.close()

    private fun sync(): HealthConnectSync = HealthConnectSync(
        context = ApplicationProvider.getApplicationContext(),
        weightRepository = weights,
        settingsRepository = settings,
        profileRepository = profiles,
        deletions = recorder,
        runtimeLog = RuntimeLog(File(temporary.root, "log.txt")),
        clientSource = { client },
    )

    private suspend fun seedOneReading(grams: Int = 80_000): Long {
        profiles.ensureDefault()
        return weights.add(grams = grams, timestamp = Instant.now().minus(1, ChronoUnit.HOURS))
    }

    @Test
    fun `a second unchanged run writes nothing`() = runTest {
        seedOneReading()
        val sync = sync()
        sync.sync().getOrThrow()
        val afterFirst = client.inserted
        assertThat(afterFirst).isEqualTo(1)

        val second = sync.sync().getOrThrow()

        assertThat(client.inserted).isEqualTo(afterFirst)
        assertThat(second.exported).isEqualTo(0)
    }

    @Test
    fun `an edit is sent again under the same name`() = runTest {
        seedOneReading()
        val sync = sync()
        sync.sync().getOrThrow()
        val entry = weights.observeEntries().first().single()

        weights.update(entry.copy(grams = 79_000))
        val second = sync.sync().getOrThrow()

        assertThat(second.exported).isEqualTo(1)
        // The same client record id, so Health Connect replaces the record rather than keeping
        // two readings a second apart for the same morning.
        val stored = client.readRecords(
            androidx.health.connect.client.request.ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.before(
                    Instant.now().plus(1, ChronoUnit.DAYS),
                ),
            ),
        ).records
        assertThat(stored.map { it.metadata.clientRecordId }.distinct())
            .containsExactly(entry.clientRecordId)
    }

    @Test
    fun `a reading deleted here is deleted there too`() = runTest {
        seedOneReading()
        val sync = sync()
        sync.sync().getOrThrow()
        val entry = weights.observeEntries().first().single()

        weights.delete(entry)
        sync.sync().getOrThrow()

        assertThat(client.deletedClientIds).containsExactly(entry.clientRecordId)
    }

    @Test
    fun `a reading that came from somewhere else is left alone when deleted here`() = runTest {
        profiles.ensureDefault()
        val id = profiles.observeAll().first().single().id
        weights.addFor(
            profileId = id,
            grams = 80_000,
            timestamp = Instant.now().minus(1, ChronoUnit.HOURS),
            source = EntrySource.HEALTH_CONNECT,
            healthConnectId = "theirs",
            clientRecordId = "hc:theirs",
        )
        val sync = sync()
        sync.sync().getOrThrow()
        val entry = weights.observeEntries().first().single()

        weights.delete(entry)
        sync.sync().getOrThrow()

        // Tidying away a copy of somebody else's record must not delete their original. The
        // deletion names it by a client record id this app never gave it, so nothing matches.
        assertThat(client.deletedClientIds).containsExactly("hc:theirs")
        assertThat(client.inserted).isEqualTo(0)
    }

    @Test
    fun `a write that fails is tried again rather than quietly forgotten`() = runTest {
        seedOneReading()
        val sync = sync()
        client.refuseWrites = true

        sync.sync().getOrThrow()
        assertThat(client.inserted).isEqualTo(0)
        // The mark must not move over a write that did not happen, or the reading is lost to
        // Health Connect for ever with nothing anywhere saying so.
        client.refuseWrites = false
        val second = sync.sync().getOrThrow()

        assertThat(second.exported).isEqualTo(1)
        assertThat(client.inserted).isEqualTo(1)
    }

    @Test
    fun `a failed write is written down where somebody can read it`() = runTest {
        seedOneReading()
        val log = RuntimeLog(File(temporary.newFolder(), "log.txt"))
        client.refuseWrites = true
        HealthConnectSync(
            context = ApplicationProvider.getApplicationContext(),
            weightRepository = weights,
            settingsRepository = settings,
            profileRepository = profiles,
            deletions = recorder,
            runtimeLog = log,
            clientSource = { client },
        ).sync().getOrThrow()

        assertThat(log.read()).contains("health_write_failed")
    }

    @Test
    fun `no height is written into somebody's health record`() = runTest {
        seedOneReading()
        settings.setHeightMm(1_803)

        sync().sync().getOrThrow()

        // Height export is not a feature anybody asked for or can turn off, and it was writing a
        // record dated now on every single run.
        val heights = client.readRecords(
            androidx.health.connect.client.request.ReadRecordsRequest(
                recordType = androidx.health.connect.client.records.HeightRecord::class,
                timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.before(
                    Instant.now().plus(1, ChronoUnit.DAYS),
                ),
            ),
        ).records
        assertThat(heights).isEmpty()
    }
}
