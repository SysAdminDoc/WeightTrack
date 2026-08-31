package com.weighttrack.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.health.connect.client.units.Mass
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.HealthDirection
import com.weighttrack.core.model.RecordOrigin
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
 * Where a reading came from, and which way readings are allowed to move.
 *
 * Health Connect is a shared pool. A weigh-in in it was written by somebody, and a phone that
 * also syncs a watch and a fitness tracker gets the same morning from three of them. Nothing on
 * the row said which app wrote it, so "why are there three of these" and "stop taking these" were
 * both unanswerable. Connecting at all meant granting read and write together, on every record
 * type, whichever way somebody actually wanted their data to move.
 */
@RunWith(RobolectricTestRunner::class)
class HealthOriginTest {

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
    fun tearDown() {
        database.close()
    }

    /**
     * One reading, written by a named app.
     *
     * The origin is stamped by whoever is doing the writing rather than carried on the record,
     * which is exactly how the real thing behaves: an app cannot claim to be another one.
     */
    private fun record(
        clientRecordId: String,
        manufacturer: String? = null,
        model: String? = null,
        kilograms: Double = 80.0,
        at: Instant = MORNING,
    ) = WeightRecord(
        time = at,
        zoneOffset = java.time.ZoneOffset.UTC,
        weight = Mass.kilograms(kilograms),
        metadata = if (manufacturer == null && model == null) {
            Metadata.manualEntryWithId(clientRecordId)
        } else {
            Metadata.autoRecordedWithId(
                clientRecordId,
                Device(manufacturer = manufacturer, model = model, type = Device.TYPE_SCALE),
            )
        },
    )

    private fun controller(direction: HealthDirection): FakePermissionController {
        val permissions = FakePermissionController(false)
        permissions.grantPermissions(HealthConnectSync.permissionsFor(direction))
        return permissions
    }

    /** What a shared pool looks like: several apps, each writing under its own name. */
    private suspend fun syncWith(
        vararg written: Pair<String, List<WeightRecord>>,
        direction: HealthDirection = HealthDirection.TWO_WAY,
    ): Pair<HealthConnectSyncResult, FakeHealthConnectClient> {
        settings.setHealthDirection(direction)
        val fake = FakeHealthConnectClient(permissionController = controller(direction))
        written.forEach { (packageName, records) ->
            fake.setPackageName(packageName)
            fake.insertRecords(records)
        }
        profiles.ensureDefault()
        val sync = HealthConnectSync(
            context = ApplicationProvider.getApplicationContext(),
            weightRepository = weights,
            settingsRepository = settings,
            profileRepository = profiles,
            deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao()),
            runtimeLog = RuntimeLog(File(temporary.root, "log.txt")),
            clientSource = { fake },
        )
        return sync.sync().getOrThrow() to fake
    }

    @Test
    fun `a reading says which app wrote it, and on what`() = runTest {
        syncWith(
            WITHINGS to listOf(
                record(clientRecordId = "w-1", manufacturer = "Withings", model = "Body+"),
            ),
        )

        val entry = weights.entriesFor(profiles.activeId()).single()
        assertThat(entry.origin).isEqualTo(RecordOrigin("com.withings.wiscale2", "Withings Body+"))
    }

    @Test
    fun `a writer that named no device is still named itself`() = runTest {
        // Plenty of apps fill in neither manufacturer nor model. The app is the part that
        // answers "where did this come from", so it must not be lost with the hardware.
        syncWith(RENPHO to listOf(record(clientRecordId = "w-1")))

        val entry = weights.entriesFor(profiles.activeId()).single()
        assertThat(entry.origin).isEqualTo(RecordOrigin("com.renpho.health", null))
    }

    @Test
    fun `the apps that have written are listed for somebody to choose between`() = runTest {
        syncWith(
            WITHINGS to listOf(record("w-1", "Withings", "Body+")),
            RENPHO to listOf(record("w-2", kilograms = 80.5, at = MORNING.plusSeconds(60))),
        )

        assertThat(weights.origins().map { it.packageName }).containsExactly(WITHINGS, RENPHO)
    }

    @Test
    fun `an app somebody has turned off stops arriving, and the others do not`() = runTest {
        settings.setHealthOriginExcluded(RENPHO, excluded = true)

        val (result, _) = syncWith(
            WITHINGS to listOf(record("w-1")),
            RENPHO to listOf(record("w-2", kilograms = 80.5, at = MORNING.plusSeconds(60))),
        )

        assertThat(result.imported).isEqualTo(1)
        assertThat(weights.entriesFor(profiles.activeId()).single().origin?.packageName)
            .isEqualTo(WITHINGS)
    }

    @Test
    fun `turning one off again lets it back in`() = runTest {
        settings.setHealthOriginExcluded(RENPHO, excluded = true)
        settings.setHealthOriginExcluded(RENPHO, excluded = false)

        val (result, _) = syncWith(RENPHO to listOf(record("w-2")))

        assertThat(result.imported).isEqualTo(1)
    }

    @Test
    fun `the same record from the same app is one row however often it is read`() = runTest {
        val same = WITHINGS to listOf(record("w-1", "Withings", "Body+"))
        syncWith(same)

        // A fresh client with the same records, which is what a second sync looks like from
        // this side of it.
        syncWith(same)

        assertThat(weights.entriesFor(profiles.activeId())).hasSize(1)
    }

    @Test
    fun `read only publishes nothing`() = runTest {
        profiles.ensureDefault()
        weights.add(grams = 82_000)

        val (result, fake) = syncWith(
            WITHINGS to listOf(record("w-1")),
            direction = HealthDirection.READ_ONLY,
        )

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.exported).isEqualTo(0)
        // And the reading typed in here is still only here.
        assertThat(inHealthConnect(fake)).hasSize(1)
    }

    @Test
    fun `write only imports nothing`() = runTest {
        profiles.ensureDefault()
        weights.add(grams = 82_000)

        val (result, _) = syncWith(
            WITHINGS to listOf(record("w-1")),
            direction = HealthDirection.WRITE_ONLY,
        )

        assertThat(result.imported).isEqualTo(0)
        assertThat(result.exported).isEqualTo(1)
        assertThat(weights.entriesFor(profiles.activeId())).hasSize(1)
    }

    @Test
    fun `read only works without the permission to write`() = runTest {
        // The point of the setting. Weight sync used to be gated on read and write together, so
        // somebody who granted only what they wanted got an app reporting itself unauthorised.
        val (result, _) = syncWith(
            WITHINGS to listOf(record("w-1")),
            direction = HealthDirection.READ_ONLY,
        )

        assertThat(result.imported).isEqualTo(1)
    }

    /** Everything Health Connect is holding, however it got there. */
    private suspend fun inHealthConnect(fake: FakeHealthConnectClient): List<WeightRecord> =
        fake.readRecords(
            androidx.health.connect.client.request.ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.between(
                    MORNING.minusSeconds(86_400),
                    MORNING.plusSeconds(86_400 * 400),
                ),
            ),
        ).records

    @Test
    fun `a direction asks for exactly what it uses`() {
        val readOnly = HealthConnectSync.permissionsFor(HealthDirection.READ_ONLY)
        val writeOnly = HealthConnectSync.permissionsFor(HealthDirection.WRITE_ONLY)

        assertThat(readOnly.filter { it.contains("WRITE") }).isEmpty()
        assertThat(writeOnly.filter { it.contains("READ") }).isEmpty()
        assertThat(readOnly).contains(HealthPermission.getReadPermission(WeightRecord::class))
        assertThat(writeOnly).contains(HealthPermission.getWritePermission(WeightRecord::class))
        // Together they are the whole set, so choosing a direction loses nothing that both ways
        // would have asked for.
        assertThat(readOnly + writeOnly)
            .containsExactlyElementsIn(HealthConnectSync.permissionsFor(HealthDirection.TWO_WAY))
    }

    @Test
    fun `every permission the app asks for says which way it goes`() {
        // The split is made on what each permission calls itself rather than on a list kept
        // beside the feature sets, and this is what keeps that safe: a permission naming itself
        // neither, or both, would be classified by a coin toss and asked for in the wrong mode.
        val confused = HealthConnectSync.permissions.filterNot {
            it.contains("READ_") xor it.contains("WRITE_")
        }

        assertThat(confused).isEmpty()
    }

    @Test
    fun `read only still needs to be allowed to read`() {
        assertThat(HealthConnectSync.corePermissionsFor(HealthDirection.READ_ONLY))
            .containsExactly(HealthPermission.getReadPermission(WeightRecord::class))
        assertThat(HealthConnectSync.corePermissionsFor(HealthDirection.WRITE_ONLY))
            .containsExactly(HealthPermission.getWritePermission(WeightRecord::class))
    }

    private companion object {
        const val WITHINGS = "com.withings.wiscale2"
        const val RENPHO = "com.renpho.health"

        val MORNING: Instant = Instant.parse("2026-08-29T07:00:00Z")
    }
}
