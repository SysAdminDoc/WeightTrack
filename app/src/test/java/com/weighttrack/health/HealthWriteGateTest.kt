package com.weighttrack.health

import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.HealthDirection
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

/**
 * Who a meal or a glass of water is published as, and whether it is published at all.
 *
 * Health Connect holds one person's records, so a household picks who, and the weight exchange
 * has always honoured that. Food and water never did: they went across for whoever happened to
 * be on screen, into the record every other app reads as the phone owner's. Nor did they honour
 * the direction, so somebody who had set the exchange to read only still had their meals
 * published, because the grant from the operating system outlives a change of mind.
 */
@RunWith(RobolectricTestRunner::class)
class HealthWriteGateTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository

    private val noon: Instant = Instant.parse("2026-09-04T12:00:00Z")

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

    private fun syncWith(fake: FakeHealthConnectClient) = HealthConnectSync(
        context = ApplicationProvider.getApplicationContext(),
        weightRepository = weights,
        settingsRepository = settings,
        profileRepository = profiles,
        deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao()),
        runtimeLog = RuntimeLog(File(temporary.root, "log.txt")),
        clientSource = { fake },
    )

    /** A client that has been allowed everything the direction permits. */
    private fun granting(direction: HealthDirection): FakeHealthConnectClient {
        val permissions = FakePermissionController(grantAll = false)
        permissions.grantPermissions(HealthConnectSync.permissionsFor(direction))
        return FakeHealthConnectClient(permissionController = permissions)
    }

    private suspend fun meals(fake: FakeHealthConnectClient) = fake.readRecords(
        ReadRecordsRequest(
            recordType = NutritionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(
                noon.minusSeconds(86_400),
                noon.plusSeconds(86_400),
            ),
        ),
    ).records

    private suspend fun drinks(fake: FakeHealthConnectClient) = fake.readRecords(
        ReadRecordsRequest(
            recordType = HydrationRecord::class,
            timeRangeFilter = TimeRangeFilter.between(
                noon.minusSeconds(86_400),
                noon.plusSeconds(86_400),
            ),
        ),
    ).records

    private suspend fun HealthConnectSync.logAMeal() = writeNutrition(
        instant = noon,
        kcal = 500.0,
        proteinG = 30.0,
        carbsG = 50.0,
        fatG = 20.0,
        name = "Lunch",
        mealType = 0,
        clientRecordId = "meal-1",
    )

    @Test
    fun `the person who holds Health Connect has their meal and their water published`() =
        runTest {
            profiles.ensureDefault()
            val fake = granting(HealthDirection.TWO_WAY)
            val sync = syncWith(fake)
            sync.claimProfile()

            assertThat(sync.logAMeal()).isTrue()
            assertThat(sync.writeHydration(250, noon, "water-1")).isTrue()

            assertThat(meals(fake)).hasSize(1)
            assertThat(drinks(fake)).hasSize(1)
        }

    @Test
    fun `somebody else in the house does not write into their record`() = runTest {
        profiles.ensureDefault()
        val fake = granting(HealthDirection.TWO_WAY)
        val sync = syncWith(fake)
        // The first person connects, so the claim is theirs.
        sync.claimProfile()
        // And now the second person is the one on screen.
        val other = profiles.add("Them")
        profiles.setActive(other)

        assertThat(sync.logAMeal()).isFalse()
        assertThat(sync.writeHydration(250, noon, "water-1")).isFalse()

        assertThat(meals(fake)).isEmpty()
        assertThat(drinks(fake)).isEmpty()
    }

    @Test
    fun `read only publishes no meals and no water`() = runTest {
        profiles.ensureDefault()
        // Granted while the exchange was two-way, which is how it happens: the grant from the
        // operating system stays after the direction is changed here.
        val fake = granting(HealthDirection.TWO_WAY)
        val sync = syncWith(fake)
        sync.claimProfile()
        settings.setHealthDirection(HealthDirection.READ_ONLY)

        assertThat(sync.logAMeal()).isFalse()
        assertThat(sync.writeHydration(250, noon, "water-1")).isFalse()

        assertThat(meals(fake)).isEmpty()
        assertThat(drinks(fake)).isEmpty()
    }
}
