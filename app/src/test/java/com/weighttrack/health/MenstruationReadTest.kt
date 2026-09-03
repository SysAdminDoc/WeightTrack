package com.weighttrack.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.diagnostics.RuntimeLog
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turning periods into the days the rest of the app works in.
 *
 * The read itself is three lines; the day arithmetic underneath it is where this can be wrong in
 * ways nobody would see. A period marked one day too long shades a morning that was ordinary and
 * throws that morning out of the fit, and the only evidence would be a chart that looks slightly
 * odd once a month.
 */
@RunWith(RobolectricTestRunner::class)
class MenstruationReadTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val zone: ZoneId = ZoneId.systemDefault()
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
    fun tearDown() = database.close()

    private fun sync(client: HealthConnectClient?): HealthConnectSync = HealthConnectSync(
        context = ApplicationProvider.getApplicationContext(),
        weightRepository = weights,
        settingsRepository = testSettingsRepository(),
        profileRepository = profiles,
        deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao()),
        runtimeLog = RuntimeLog(File(temporary.root, "log.txt")),
        clientSource = { client },
    )

    private val readPeriods = HealthPermission.getReadPermission(MenstruationPeriodRecord::class)

    private fun allowing(vararg permissions: String) =
        FakePermissionController(false).apply { grantPermissions(permissions.toSet()) }

    /** A record covering [from] up to but not including [until], which is how these are written. */
    private fun period(from: LocalDate, until: LocalDate) = MenstruationPeriodRecord(
        startTime = from.atStartOfDay(zone).toInstant(),
        startZoneOffset = null,
        endTime = until.atStartOfDay(zone).toInstant(),
        endZoneOffset = null,
        metadata = Metadata.manualEntry(),
    )

    private suspend fun daysFrom(vararg records: MenstruationPeriodRecord): Set<LocalDate> {
        val client = FakeHealthConnectClient(permissionController = allowing(readPeriods))
        client.insertRecords(records.toList())
        val outcome = sync(client).readMenstruationDays()
        assertThat(outcome).isInstanceOf(HealthOutcome.Ok::class.java)
        return (outcome as HealthOutcome.Ok).value
    }

    @Test
    fun `refusing the permission is its own answer`() = runTest {
        val client = FakeHealthConnectClient(permissionController = FakePermissionController(false))

        assertThat(sync(client).readMenstruationDays()).isEqualTo(HealthOutcome.NotAllowed)
    }

    @Test
    fun `no Health Connect on the phone says so`() = runTest {
        assertThat(sync(client = null).readMenstruationDays()).isEqualTo(HealthOutcome.NotAvailable)
    }

    @Test
    fun `a read that goes wrong is not reported as no periods`() = runTest {
        // The difference that matters. Answering an outage with an empty set would tell the
        // chart there were no periods, which is a claim rather than an absence of one.
        val client = FakeHealthConnectClient(permissionController = allowing(readPeriods))
        client.overrides.readRecords = { throw IOException("the provider fell over") }

        val outcome = sync(client).readMenstruationDays()

        assertThat(outcome).isInstanceOf(HealthOutcome.Failed::class.java)
    }

    @Test
    fun `a period becomes every day it covered`() = runTest {
        val start = LocalDate.now(zone).minusDays(20)

        val days = daysFrom(period(from = start, until = start.plusDays(4)))

        assertThat(days).containsExactly(
            start,
            start.plusDays(1),
            start.plusDays(2),
            start.plusDays(3),
        )
    }

    @Test
    fun `an end at midnight does not claim the day after it`() = runTest {
        // Health Connect writes a period that finished on the fourth as an interval closing at
        // the fifth at 00:00. Read literally that shades a morning nobody said anything about,
        // and drops it out of the fit as well.
        val start = LocalDate.now(zone).minusDays(20)

        val days = daysFrom(period(from = start, until = start.plusDays(1)))

        assertThat(days).containsExactly(start)
    }

    @Test
    fun `two periods both come back`() = runTest {
        val first = LocalDate.now(zone).minusDays(60)
        val second = LocalDate.now(zone).minusDays(32)

        val days = daysFrom(
            period(from = first, until = first.plusDays(3)),
            period(from = second, until = second.plusDays(3)),
        )

        assertThat(days).hasSize(6)
        assertThat(days).containsAtLeast(first, second)
    }

    @Test
    fun `a record with an absurd end is not walked for ever`() = runTest {
        // Written by some other app, and this expands it a day at a time on a background thread
        // nobody is watching. Ten days is already beyond anything ordinary.
        val start = LocalDate.now(zone).minusDays(30)

        val days = daysFrom(period(from = start, until = start.plusYears(400)))

        assertThat(days).hasSize(10)
        assertThat(days.max()).isEqualTo(start.plusDays(9))
    }

    @Test
    fun `nothing recorded is an empty set rather than a refusal`() = runTest {
        assertThat(daysFrom()).isEmpty()
    }
}
