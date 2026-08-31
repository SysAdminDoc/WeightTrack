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
        grant()
    }

    /**
     * A client allowed to do the weight sync, plus whatever else a test asks for.
     *
     * Built with nothing granted and then given the set: the default controller grants
     * everything, which would make every refusal here a test of the happy path.
     */
    private fun grant(vararg extra: String) {
        val permissions = FakePermissionController(false)
        permissions.grantPermissions(HealthConnectSync.corePermissions + extra)
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

        // Two calls: the weigh-in, and the body-fat record that carries the same name with a
        // marker in front. The second is asked for even when there was no body fat to write,
        // because nothing left to delete costs a call and a figure left behind costs the truth.
        assertThat(client.deletedClientIds)
            .containsExactly(entry.clientRecordId, "bf:" + entry.clientRecordId)
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
        // name it carries here is the one this app gave it for its own use, and Health Connect
        // has never heard it: asking to delete by it is at best noise and at worst an error
        // that stalls the deletions in the same batch that are real.
        assertThat(client.deletedClientIds).isEmpty()
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
    fun `a history that arrives from another phone is exported too`() = runTest {
        profiles.ensureDefault()
        val id = profiles.observeAll().first().single().id
        sync().sync().getOrThrow()
        val beforeTheirs = client.inserted

        // What a phone switch looks like: rows written here carrying the other device's clock,
        // every one of them older than anything this phone has done. A wall-time watermark reads
        // them as already sent and none of them ever reaches Health Connect.
        database.syncDao().insertWeights(
            listOf(
                com.weighttrack.data.db.WeightEntryEntity(
                    profileId = id,
                    timestampUtcMillis = Instant.now().minus(400, ChronoUnit.DAYS).toEpochMilli(),
                    zoneOffsetSeconds = 0,
                    localDate = "2025-07-27",
                    grams = 82_000,
                    bodyFatPercent = null,
                    note = null,
                    tags = "",
                    source = "MANUAL",
                    clientRecordId = "from-the-other-phone",
                    healthConnectId = null,
                    updatedAtUtcMillis = Instant.now().minus(400, ChronoUnit.DAYS).toEpochMilli(),
                ),
            ),
        )

        val result = sync().sync().getOrThrow()

        assertThat(result.exported).isEqualTo(1)
        assertThat(client.inserted).isGreaterThan(beforeTheirs)
    }

    @Test
    fun `body fat is sent with its reading and not again afterwards`() = runTest {
        profiles.ensureDefault()
        val id = profiles.observeAll().first().single().id
        weights.addFor(
            profileId = id,
            grams = 80_000,
            timestamp = Instant.now().minus(1, ChronoUnit.HOURS),
            bodyFatPercent = 22.5,
        )
        grant(*HealthConnectSync.bodyFatPermissions.toTypedArray())
        val sync = sync()

        sync.sync().getOrThrow()
        val afterFirst = client.inserted
        sync.sync().getOrThrow()

        // The weigh-in and its body fat, once. The body-fat export used to be a pass of its own
        // with no record of what it had sent, so every press of the sync button rewrote every
        // figure the person had ever recorded.
        assertThat(afterFirst).isEqualTo(2)
        assertThat(client.inserted).isEqualTo(afterFirst)
    }

    @Test
    fun `body fat is not written without permission for it`() = runTest {
        profiles.ensureDefault()
        val id = profiles.observeAll().first().single().id
        weights.addFor(
            profileId = id,
            grams = 80_000,
            timestamp = Instant.now().minus(1, ChronoUnit.HOURS),
            bodyFatPercent = 22.5,
        )

        // The set granted in setUp is weight only. The old separate pass checked nothing at all.
        sync().sync().getOrThrow()

        assertThat(client.inserted).isEqualTo(1)
    }

    @Test
    fun `turning it off does not hand Health Connect to somebody else an hour later`() = runTest {
        seedOneReading()
        val sync = sync()
        sync.sync().getOrThrow()
        val alice = profiles.observeAll().first().single().id
        profiles.setHealthConnect(alice, enabled = false)
        // Adding a profile switches to it, which is what the next background run would meet.
        val bob = profiles.add("Bob")

        val result = sync.sync()

        assertThat(result.isFailure).isTrue()
        assertThat(profiles.healthConnectId()).isNull()
        assertThat(weights.entriesFor(bob)).isEmpty()
    }

    @Test
    fun `body fat allowed after the fact reaches what was already sent`() = runTest {
        profiles.ensureDefault()
        val id = profiles.observeAll().first().single().id
        weights.addFor(
            profileId = id,
            grams = 80_000,
            timestamp = Instant.now().minus(1, ChronoUnit.HOURS),
            bodyFatPercent = 22.5,
        )
        // Sent while body fat was not allowed: the weight went across, the figure did not, and
        // the row was marked done.
        sync().sync().getOrThrow()
        val afterWeightOnly = client.inserted
        assertThat(afterWeightOnly).isEqualTo(1)

        grant(*HealthConnectSync.bodyFatPermissions.toTypedArray())
        sync().sync().getOrThrow()

        // Allowing it afterwards has to reach the readings already recorded, or no historical
        // figure ever arrives and nothing anywhere says why.
        assertThat(client.inserted).isEqualTo(2)
    }

    @Test
    fun `body fat taken away and allowed again reaches the readings in between`() = runTest {
        profiles.ensureDefault()
        val id = profiles.observeAll().first().single().id
        grant(*HealthConnectSync.bodyFatPermissions.toTypedArray())
        weights.addFor(
            profileId = id,
            grams = 80_000,
            timestamp = Instant.now().minus(2, ChronoUnit.HOURS),
            bodyFatPercent = 22.5,
        )
        sync().sync().getOrThrow()

        // Taken away, and a reading recorded while it was off goes across without its figure.
        grant()
        weights.addFor(
            profileId = id,
            grams = 79_500,
            timestamp = Instant.now().minus(1, ChronoUnit.HOURS),
            bodyFatPercent = 22.0,
        )
        sync().sync().getOrThrow()
        val beforeRegrant = client.inserted

        grant(*HealthConnectSync.bodyFatPermissions.toTypedArray())
        sync().sync().getOrThrow()

        // Allowing it again is the same situation the backfill exists for. A mark that only ever
        // went true left that reading in the health record with a weight and no figure, for good.
        assertThat(client.inserted).isGreaterThan(beforeRegrant)
    }

    @Test
    fun `a deleted reading takes its body-fat record with it`() = runTest {
        profiles.ensureDefault()
        val id = profiles.observeAll().first().single().id
        grant(*HealthConnectSync.bodyFatPermissions.toTypedArray())
        weights.addFor(
            profileId = id,
            grams = 80_000,
            timestamp = Instant.now().minus(1, ChronoUnit.HOURS),
            bodyFatPercent = 22.5,
        )
        val sync = sync()
        sync.sync().getOrThrow()
        val entry = weights.observeEntries().first().single()

        weights.delete(entry)
        sync.sync().getOrThrow()

        // Left behind, a figure somebody deleted here stays in their health record for ever with
        // the weight it belonged to gone.
        assertThat(client.deletedClientIds)
            .containsExactly(entry.clientRecordId, "bf:${entry.clientRecordId}")
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
