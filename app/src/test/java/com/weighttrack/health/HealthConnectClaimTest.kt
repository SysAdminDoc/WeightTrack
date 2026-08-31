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

/**
 * Whose Health Connect this is.
 *
 * It used to follow whoever was on screen whenever nobody had claimed it, which is every install
 * until the day a second person is added. From that day, switching person pointed Health Connect
 * at them without saying so: their scale readings landed on the first person's history, and the
 * first person's weigh-ins were written into a Health Connect that the phone's owner reads as
 * their own. Nothing anywhere said it had happened.
 */
@RunWith(RobolectricTestRunner::class)
class HealthConnectClaimTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository

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
        // Relative to now, because Health Connect refuses a record dated in the future.
        time = Instant.now().minus(index + 1L, ChronoUnit.HOURS),
        zoneOffset = null,
        weight = Mass.kilograms(80.0 + index),
        metadata = Metadata.manualEntry(clientRecordId = "scale-$index"),
    )

    private fun syncFor(records: List<WeightRecord>): HealthConnectSync {
        val permissions = FakePermissionController()
        permissions.grantPermissions(
            setOf(
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getWritePermission(WeightRecord::class),
            ),
        )
        val fake = FakeHealthConnectClient(permissionController = permissions)
        kotlinx.coroutines.runBlocking { fake.insertRecords(records) }
        return HealthConnectSync(
            context = ApplicationProvider.getApplicationContext(),
            weightRepository = weights,
            settingsRepository = settings,
            profileRepository = profiles,
            runtimeLog = RuntimeLog(File(temporary.root, "log.txt")),
            clientSource = { fake },
        )
    }

    @Test
    fun `connecting writes down whose Health Connect it is`() = runTest {
        profiles.ensureDefault()
        val only = profiles.observeAll().first().single().id

        val claimed = syncFor(emptyList()).claimProfile()

        assertThat(claimed).isEqualTo(only)
        // Durable, not remembered in memory: the answer has to survive the process being killed
        // between connecting and the first background sync an hour later.
        assertThat(profiles.healthConnectId()).isEqualTo(only)
    }

    @Test
    fun `the claim is made once and never changes hands by itself`() = runTest {
        profiles.ensureDefault()
        val first = profiles.observeAll().first().single().id
        val sync = syncFor(listOf(record(0)))
        sync.claimProfile()

        // Somebody adds a second person and switches to them, which `add` does on its own.
        val second = profiles.add("Them")

        assertThat(profiles.activeId()).isEqualTo(second)
        assertThat(profiles.healthConnectId()).isEqualTo(first)
        sync.sync().getOrThrow()
        // The reading belongs to the person who connected, not to whoever is on screen.
        assertThat(weights.entriesFor(first)).hasSize(1)
        assertThat(weights.entriesFor(second)).isEmpty()
    }

    @Test
    fun `a first sync on an install that never claimed claims as it goes`() = runTest {
        // Every install made before this existed is in this state: connected, syncing, and with
        // nobody recorded as the owner.
        profiles.ensureDefault()
        val only = profiles.observeAll().first().single().id
        assertThat(profiles.healthConnectId()).isNull()

        syncFor(listOf(record(0))).sync().getOrThrow()

        assertThat(profiles.healthConnectId()).isEqualTo(only)
        assertThat(weights.entriesFor(only)).hasSize(1)
    }

    @Test
    fun `handing it to somebody else is what changes the owner`() = runTest {
        profiles.ensureDefault()
        val first = profiles.observeAll().first().single().id
        val sync = syncFor(listOf(record(0)))
        sync.claimProfile()
        val second = profiles.add("Them")

        sync.whileNotSyncing { profiles.setHealthConnect(second, enabled = true) }

        // Exclusive: two profiles writing into one Health Connect would interleave two people.
        assertThat(profiles.healthConnectId()).isEqualTo(second)
        sync.sync().getOrThrow()
        assertThat(weights.entriesFor(second)).hasSize(1)
        assertThat(weights.entriesFor(first)).isEmpty()
    }

    @Test
    fun `taking it away leaves nobody holding it until somebody syncs again`() = runTest {
        profiles.ensureDefault()
        val only = profiles.observeAll().first().single().id
        val sync = syncFor(emptyList())
        sync.claimProfile()

        sync.whileNotSyncing { profiles.setHealthConnect(only, enabled = false) }

        assertThat(profiles.healthConnectId()).isNull()
    }
}
