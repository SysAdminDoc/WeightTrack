package com.weighttrack.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.ReadRecordsResponse
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
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What happens when Health Connect does not answer.
 *
 * Every failure used to mean the same thing: throw the cursor away and read five years of records
 * again. A provider having a bad minute, a rate limit, and a grant withdrawn months ago all cost
 * the most expensive query the app can make, hourly, until whatever it was went away. That is
 * also the query Health Connect rate-limits, so the recovery fed the problem.
 */
@RunWith(RobolectricTestRunner::class)
class HealthTokenRecoveryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository

    /** Answers changes with whatever a test wants, and remembers what was asked of it. */
    private class AwkwardClient(
        private val real: HealthConnectClient,
        private val onChanges: (String) -> ChangesResponse,
    ) : HealthConnectClient by real {
        val readsFrom = mutableListOf<Instant>()

        override suspend fun getChanges(changesToken: String): ChangesResponse =
            onChanges(changesToken)

        override suspend fun <T : Record> readRecords(
            request: ReadRecordsRequest<T>,
        ): ReadRecordsResponse<T> {
            (request.timeRangeFilter.startTime)?.let {
                readsFrom += it.atZone(java.time.ZoneOffset.UTC).toInstant()
            }
            return real.readRecords(request)
        }
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = testSettingsRepository()
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(
            database.profileDao(),
            settings,
            deletions,
            database.weightEntryDao(),
        )
        weights = WeightRepository(database.weightEntryDao(), profiles, deletions)
    }

    @After
    fun tearDown() = database.close()

    private fun record(index: Int) = WeightRecord(
        time = Instant.now().minus(index + 1L, ChronoUnit.HOURS),
        zoneOffset = null,
        weight = Mass.kilograms(80.0 + index),
        metadata = Metadata.manualEntry(clientRecordId = "scale-$index"),
    )

    private fun syncWith(client: HealthConnectClient): HealthConnectSync {
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        return HealthConnectSync(
            context = ApplicationProvider.getApplicationContext(),
            weightRepository = weights,
            settingsRepository = settings,
            profileRepository = profiles,
            deletions = deletions,
            runtimeLog = RuntimeLog(File(temporary.root, "log.txt")),
            clientSource = { client },
        )
    }

    private fun fake(): FakeHealthConnectClient {
        val permissions = FakePermissionController(false)
        permissions.grantPermissions(HealthConnectSync.corePermissions)
        return FakeHealthConnectClient(permissionController = permissions)
    }

    /** A first run, so a cursor exists and the import has read up to something. */
    private suspend fun firstRun(): Long {
        profiles.ensureDefault()
        val plain = fake()
        plain.insertRecords(listOf(record(0)))
        syncWith(plain).sync().getOrThrow()
        return profiles.observeAll().first().single().id
    }

    @Test
    fun `a provider having a bad minute keeps its place in the queue`() = runTest {
        val id = firstRun()
        val before = checkNotNull(settings.healthChangesToken(id))
        val awkward = AwkwardClient(fake()) { error("the provider is busy") }

        syncWith(awkward).sync().getOrThrow()

        // Kept, so the next run picks up exactly where this one stopped. Thrown away, the next
        // run reads five years, and so does the one after that.
        assertThat(settings.healthChangesToken(id)).isEqualTo(before)
        assertThat(awkward.readsFrom).isEmpty()
    }

    @Test
    fun `a grant withdrawn since the last run keeps its place too`() = runTest {
        val id = firstRun()
        val before = checkNotNull(settings.healthChangesToken(id))
        val awkward = AwkwardClient(fake()) { throw SecurityException("not allowed") }

        syncWith(awkward).sync().getOrThrow()

        assertThat(settings.healthChangesToken(id)).isEqualTo(before)
        assertThat(awkward.readsFrom).isEmpty()
    }

    @Test
    fun `a rate limit is not answered with the most expensive query there is`() = runTest {
        val id = firstRun()
        val before = checkNotNull(settings.healthChangesToken(id))
        val awkward = AwkwardClient(fake()) { error("API call rate limit exceeded") }

        syncWith(awkward).sync().getOrThrow()

        // Reading five years here is the query Health Connect is rate-limiting, so the old
        // recovery fed the very problem it was reacting to.
        assertThat(settings.healthChangesToken(id)).isEqualTo(before)
        assertThat(awkward.readsFrom).isEmpty()
    }

    @Test
    fun `a token the provider has forgotten is replaced, and only that`() = runTest {
        val id = firstRun()
        val awkward = AwkwardClient(fake()) { error("Unknown changes token") }

        syncWith(awkward).sync().getOrThrow()

        // A fresh one, taken after reading again.
        assertThat(settings.healthChangesToken(id)).isNotNull()
        assertThat(awkward.readsFrom).isNotEmpty()
    }

    @Test
    fun `losing the cursor reads back a little way, not five years`() = runTest {
        firstRun()
        val awkward = AwkwardClient(fake()) { error("Unknown changes token") }

        syncWith(awkward).sync().getOrThrow()

        // Days behind where the last successful run read to, rather than the five years a first
        // connect asks for. The overlap covers a record written just before that moment.
        val from = awkward.readsFrom.first()
        val behind = Duration.between(from, Instant.now())
        assertThat(behind.toDays()).isAtMost(7)
        assertThat(behind.toDays()).isAtLeast(1)
    }

    @Test
    fun `recovering does not import the same reading twice`() = runTest {
        val id = firstRun()
        val awkward = AwkwardClient(fake().also { it.insertRecords(listOf(record(0))) }) {
            error("Unknown changes token")
        }

        syncWith(awkward).sync().getOrThrow()

        // The reading is keyed on its own name, so reading it again replaces it rather than
        // adding a second morning.
        assertThat(weights.entriesFor(id)).hasSize(1)
    }

    @Test
    fun `a first connect still reads the whole history`() = runTest {
        profiles.ensureDefault()
        val awkward = AwkwardClient(fake()) { error("Unknown changes token") }

        syncWith(awkward).sync().getOrThrow()

        // Nothing has been read yet, so there is nothing to read back from. Somebody arriving
        // with four years of weigh-ins in their scale's app should inherit all of them.
        val from = awkward.readsFrom.first()
        assertThat(Duration.between(from, Instant.now()).toDays()).isAtLeast(365 * 4L)
    }
}
