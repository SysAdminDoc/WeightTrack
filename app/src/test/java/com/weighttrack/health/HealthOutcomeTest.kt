package com.weighttrack.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
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
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

/**
 * Telling "you have no step counts" apart from "we could not ask".
 *
 * Every read here used to answer with an empty list whichever had happened, so a permission the
 * person withdrew months ago, a provider that fell over, and somebody who genuinely has not worn
 * a watch all produced the same card. Only one of those three was true, and it was the one the
 * card asserted.
 */
@RunWith(RobolectricTestRunner::class)
class HealthOutcomeTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: WeightTrackDatabase
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settings = testSettingsRepository()
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(database.profileDao(), settings, deletions, database.weightEntryDao())
        weights = WeightRepository(database.weightEntryDao(), profiles, deletions)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sync(client: HealthConnectClient?): HealthConnectSync = HealthConnectSync(
        context = ApplicationProvider.getApplicationContext(),
        weightRepository = weights,
        settingsRepository = testSettingsRepository(),
        profileRepository = profiles,
        runtimeLog = RuntimeLog(File(temporary.root, "log.txt")),
        clientSource = { client },
    )

    /**
     * A permission controller that has granted exactly these and nothing else.
     *
     * The no-argument constructor grants everything, which quietly turned three of these tests
     * into tests of the happy path.
     */
    private fun allowed(vararg permissions: String) = FakePermissionController(false).apply {
        grantPermissions(permissions.toSet())
    }

    private fun nothingAllowed() = FakePermissionController(false)

    /** The fake refuses to aggregate without being told what to answer. */
    private fun FakeHealthConnectClient.answersWithNoActivity() = apply {
        overrides.aggregateGroupByPeriod = { emptyList() }
    }

    private val activityPermissions = arrayOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    @Test
    fun `no Health Connect on the phone says so`() = runTest {
        val outcome = sync(client = null).readDailyActivity()

        assertThat(outcome).isEqualTo(HealthOutcome.NotAvailable)
    }

    @Test
    fun `a permission that was never given says so`() = runTest {
        val client = FakeHealthConnectClient(permissionController = nothingAllowed())

        val outcome = sync(client).readDailyActivity()

        assertThat(outcome).isEqualTo(HealthOutcome.NotAllowed)
    }

    @Test
    fun `a read that goes wrong is not reported as having no data`() = runTest {
        val client = FakeHealthConnectClient(permissionController = allowed(*activityPermissions))
        client.overrides.aggregateGroupByPeriod = { throw IOException("the provider fell over") }

        val outcome = sync(client).readDailyActivity()

        assertThat(outcome).isInstanceOf(HealthOutcome.Failed::class.java)
        assertThat((outcome as HealthOutcome.Failed).cause).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `nothing recorded is its own answer`() = runTest {
        val client = FakeHealthConnectClient(permissionController = allowed(*activityPermissions))
            .answersWithNoActivity()

        val outcome = sync(client).readDailyActivity()

        // The honest empty: asked, allowed, answered, and there was nothing there.
        assertThat(outcome).isEqualTo(HealthOutcome.Ok(emptyList<DailyActivity>()))
    }

    @Test
    fun `sleep refused is not the same as sleeping badly`() = runTest {
        val client = FakeHealthConnectClient(permissionController = nothingAllowed())

        assertThat(sync(client).readSleepHours()).isEqualTo(HealthOutcome.NotAllowed)
    }

    @Test
    fun `sleep that reads gives hours by morning`() = runTest {
        val client = FakeHealthConnectClient(
            permissionController = allowed(HealthPermission.getReadPermission(SleepSessionRecord::class)),
        )
        val end = Instant.now()
        client.insertRecords(
            listOf(
                SleepSessionRecord(
                    startTime = end.minusSeconds(7 * 3600),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = end,
                    endZoneOffset = ZoneOffset.UTC,
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )

        val outcome = sync(client).readSleepHours()

        assertThat(outcome).isInstanceOf(HealthOutcome.Ok::class.java)
        assertThat((outcome as HealthOutcome.Ok).value.values.single()).isWithin(0.1).of(7.0)
    }

    @Test
    fun `each way of having nothing is a different answer`() = runTest {
        // The point of the whole type: four outcomes, four different things to say.
        val outcomes = setOf(
            sync(client = null).readDailyActivity(),
            sync(FakeHealthConnectClient(permissionController = nothingAllowed()))
                .readDailyActivity(),
            sync(
                FakeHealthConnectClient(permissionController = allowed(*activityPermissions))
                    .answersWithNoActivity(),
            ).readDailyActivity(),
            HealthOutcome.Failed(IOException("x")),
        )

        assertThat(outcomes).hasSize(4)
    }
}
