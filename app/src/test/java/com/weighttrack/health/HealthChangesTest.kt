package com.weighttrack.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.health.connect.client.units.Mass
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.sync.SyncKind
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Keeping up with Health Connect after the first sync.
 *
 * The first read takes everything. After that the app asks what has changed, which is the only
 * way it can hear about a reading somebody deleted in their scale's app: an import that only ever
 * upserts has no way to learn that something is no longer there, so a reading deleted elsewhere
 * used to sit here for ever.
 */
@RunWith(RobolectricTestRunner::class)
class HealthChangesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository
    private val runtimeLogFile: File get() = File(temporary.root, "log.txt")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = testSettingsRepository()
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(database.profileDao(), settings, deletions, database.weightEntryDao())
        weights = WeightRepository(database.weightEntryDao(), profiles, deletions)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sync(client: HealthConnectClient) = HealthConnectSync(
        context = ApplicationProvider.getApplicationContext(),
        weightRepository = weights,
        settingsRepository = settings,
        profileRepository = profiles,
        deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao()),
        runtimeLog = RuntimeLog(runtimeLogFile),
        clientSource = { client },
    )

    private fun client(): FakeHealthConnectClient {
        val permissions = FakePermissionController(false)
        permissions.grantPermissions(
            setOf(
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getWritePermission(WeightRecord::class),
                HealthPermission.getReadPermission(BodyFatRecord::class),
                HealthPermission.getWritePermission(BodyFatRecord::class),
                HealthPermission.getReadPermission(HeightRecord::class),
            ),
        )
        return FakeHealthConnectClient(permissionController = permissions)
    }

    private fun weightRecord(index: Int, clientId: String? = null) = WeightRecord(
        time = Instant.now().minus(index.toLong(), ChronoUnit.HOURS),
        zoneOffset = null,
        weight = Mass.kilograms(80.0 + index * 0.1),
        metadata = clientId?.let { Metadata.manualEntry(clientRecordId = it) }
            ?: Metadata.manualEntry(),
    )

    @Test
    fun `a reading deleted in the other app is deleted here too`() = runTest {
        val client = client()
        profiles.ensureDefault()
        val inserted = client.insertRecords(listOf(weightRecord(1), weightRecord(2)))
        sync(client).sync().getOrThrow()
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(2)

        client.deleteRecords(WeightRecord::class, listOf(inserted.recordIdsList.first()), emptyList())
        val second = sync(client).sync().getOrThrow()

        assertThat(second.removed).isEqualTo(1)
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(1)
    }

    @Test
    fun `a reading that goes leaves a tombstone, so it stays gone on the other phone`() = runTest {
        val client = client()
        profiles.ensureDefault()
        val inserted = client.insertRecords(listOf(weightRecord(1)))
        sync(client).sync().getOrThrow()

        client.deleteRecords(WeightRecord::class, listOf(inserted.recordIdsList.first()), emptyList())
        sync(client).sync().getOrThrow()

        // Without this the person's other device still holds the reading, has no reason to drop
        // it, and hands it straight back on the next sync.
        val tombstones = database.deletionDao().all().filter { it.kind == SyncKind.WEIGHT.name }
        assertThat(tombstones).hasSize(1)
    }

    @Test
    fun `a reading added in the other app arrives on the next sync`() = runTest {
        val client = client()
        profiles.ensureDefault()
        client.insertRecords(listOf(weightRecord(1)))
        sync(client).sync().getOrThrow()

        client.insertRecords(listOf(weightRecord(5)))
        val second = sync(client).sync().getOrThrow()

        assertThat(second.imported).isEqualTo(1)
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(2)
    }

    @Test
    fun `incremental readings outside the timestamp window are counted and logged`() = runTest {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        val client = client()
        profiles.ensureDefault()
        client.insertRecords(
            listOf(
                WeightRecord(
                    time = now.minusSeconds(60),
                    zoneOffset = null,
                    weight = Mass.kilograms(80.0),
                    metadata = Metadata.manualEntry(clientRecordId = "valid"),
                ),
            ),
        )
        sync(client).sync(now = now).getOrThrow()
        client.insertRecords(
            listOf(
                WeightRecord(
                    time = Instant.EPOCH.minusSeconds(1),
                    zoneOffset = null,
                    weight = Mass.kilograms(81.0),
                    metadata = Metadata.manualEntry(clientRecordId = "before-epoch"),
                ),
                WeightRecord(
                    time = now.plusSeconds(24 * 60 * 60L + 1),
                    zoneOffset = null,
                    weight = Mass.kilograms(82.0),
                    metadata = Metadata.manualEntry(clientRecordId = "too-far-ahead"),
                ),
            ),
        )

        val result = sync(client).sync(now = now).getOrThrow()

        assertThat(result.imported).isEqualTo(0)
        assertThat(result.skipped).isEqualTo(2)
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(1)
        assertThat(
            runtimeLogFile.readLines().count {
                "health_connect health_record_refused code=1" in it
            },
        ).isEqualTo(2)
    }

    @Test
    fun `the second sync does not read the whole history again`() = runTest {
        val client = client()
        profiles.ensureDefault()
        client.insertRecords((1..5).map { weightRecord(it) })
        sync(client).sync().getOrThrow()

        // Nothing has changed, so there is nothing to bring in. Before this the same five
        // readings were read and skipped on every sync, for ever, against a rate limit.
        val second = sync(client).sync().getOrThrow()

        assertThat(second.imported).isEqualTo(0)
        assertThat(second.skipped).isEqualTo(0)
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(5)
    }

    @Test
    fun `the first sync remembers where it got to`() = runTest {
        val client = client()
        profiles.ensureDefault()
        client.insertRecords(listOf(weightRecord(1)))

        sync(client).sync().getOrThrow()

        assertThat(settings.healthChangesToken(profiles.activeId())).isNotNull()
    }

    @Test
    fun `each sync stores where it got to, not where it started`() = runTest {
        val client = client()
        profiles.ensureDefault()
        client.insertRecords(listOf(weightRecord(1)))
        sync(client).sync().getOrThrow()
        val first = settings.healthChangesToken(profiles.activeId())

        client.insertRecords(listOf(weightRecord(9)))
        sync(client).sync().getOrThrow()
        val second = settings.healthChangesToken(profiles.activeId())

        // Storing the token it was given rather than the one it was handed back would replay the
        // same changes on every sync, which for a deletion means deleting a row that is already
        // gone and for an insertion means an import count that never settles.
        assertThat(second).isNotNull()
        assertThat(second).isNotEqualTo(first)
    }

    @Test
    fun `a reading this app wrote is not removed when it goes from Health Connect`() = runTest {
        // Deliberate. A weigh-in logged here is the original; Health Connect holds a copy. Tidying
        // the copy away should not delete somebody's own history, so the next sync writes it back.
        val client = client()
        profiles.ensureDefault()
        weights.addFor(profileId = profiles.activeId(), grams = 82_000, timestamp = Instant.now())
        sync(client).sync().getOrThrow()

        val ours = client.readRecords(
            androidx.health.connect.client.request.ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter =
                    androidx.health.connect.client.time.TimeRangeFilter.before(Instant.now().plusSeconds(60)),
            ),
        ).records
        client.deleteRecords(WeightRecord::class, ours.map { it.metadata.id }, emptyList())
        val second = sync(client).sync().getOrThrow()

        assertThat(second.removed).isEqualTo(0)
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(1)
    }

    @Test
    fun `a token Health Connect no longer knows falls back to reading it all`() = runTest {
        val client = client()
        profiles.ensureDefault()
        client.insertRecords((1..3).map { weightRecord(it) })
        // A token from nowhere, which is what an expired one looks like from here.
        settings.setHealthChangesToken(profiles.activeId(), "stale-token")

        val result = sync(client).sync().getOrThrow()

        assertThat(result.imported).isEqualTo(3)
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(3)
        // And it takes a fresh one, so the next sync is incremental again.
        assertThat(settings.healthChangesToken(profiles.activeId())).isNotEqualTo("stale-token")
        assertThat(settings.healthChangesToken(profiles.activeId())).isNotNull()
    }

    @Test
    fun `the day's lowest still applies once the import has gone incremental`() = runTest {
        // The rule used to live only in the full read, so it worked for the first import and
        // silently never again: every sync after that goes down the changes path.
        settings.setImportLowestOfDay(true)
        val now = Instant.parse("2026-08-29T12:00:00Z")
        val client = client()
        profiles.ensureDefault()
        // A first sync with nothing to read still takes a token, which is what makes every
        // sync after it incremental.
        sync(client).sync(now = now).getOrThrow()
        assertThat(settings.healthChangesToken(profiles.activeId())).isNotNull()

        // The heavier weigh-in arrives first, which is the case that needs the one already
        // filed to go rather than the new one to be refused.
        client.insertRecords(
            listOf(
                WeightRecord(
                    time = now.minusSeconds(3600),
                    zoneOffset = null,
                    weight = Mass.kilograms(81.2),
                    metadata = Metadata.manualEntry(clientRecordId = "after-breakfast"),
                ),
            ),
        )
        client.insertRecords(
            listOf(
                WeightRecord(
                    time = now.minusSeconds(1800),
                    zoneOffset = null,
                    weight = Mass.kilograms(80.4),
                    metadata = Metadata.manualEntry(clientRecordId = "first-thing"),
                ),
            ),
        )

        sync(client).sync(now = now).getOrThrow()

        val kept = weights.entriesFor(profiles.activeId())
        assertThat(kept.map { it.grams }).containsExactly(80_400)
    }

    @Test
    fun `the day's lowest never removes a weight somebody typed in`() = runTest {
        // The rule is about a scale writing twice, not about overruling the person. A reading
        // entered here is theirs and stays whatever Health Connect says later.
        settings.setImportLowestOfDay(true)
        val now = Instant.parse("2026-08-29T12:00:00Z")
        val client = client()
        profiles.ensureDefault()
        weights.addFor(
            profileId = profiles.activeId(),
            grams = 82_000,
            timestamp = now.minusSeconds(7200),
        )
        sync(client).sync(now = now).getOrThrow()

        client.insertRecords(
            listOf(
                WeightRecord(
                    time = now.minusSeconds(1800),
                    zoneOffset = null,
                    weight = Mass.kilograms(80.4),
                    metadata = Metadata.manualEntry(clientRecordId = "first-thing"),
                ),
            ),
        )
        sync(client).sync(now = now).getOrThrow()

        val kept = weights.entriesFor(profiles.activeId())
        assertThat(kept.map { it.grams }).containsExactly(82_000, 80_400)
    }
}
