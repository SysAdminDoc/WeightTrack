package com.weighttrack.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
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

/**
 * Asking for the background read without taking the hourly exchange away from anybody.
 *
 * The permission only exists on a Health Connect new enough to offer it. Somewhere older will
 * never report it as granted however many times it is asked for, and this app has walked into
 * that once already with the history permission: "Allow the rest" stayed on screen for ever for
 * somebody who had allowed everything they could. Repeating it here would have been worse,
 * because the hourly job is now gated on the grant and would have been cancelled for good on
 * exactly those phones, where it had been working.
 */
@RunWith(RobolectricTestRunner::class)
class HealthBackgroundAccessTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository

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
    }

    @After
    fun tearDown() = database.close()

    private fun sync(granted: Set<String>): HealthConnectSync {
        val permissions = FakePermissionController(false)
        permissions.grantPermissions(granted)
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        return HealthConnectSync(
            context = ApplicationProvider.getApplicationContext(),
            weightRepository = WeightRepository(
                database.weightEntryDao(),
                profiles,
                deletions,
            ),
            settingsRepository = settings,
            profileRepository = profiles,
            deletions = deletions,
            runtimeLog = RuntimeLog(File(temporary.root, "log.txt")),
            clientSource = { FakeHealthConnectClient(permissionController = permissions) },
        )
    }

    @Test
    fun `a provider that cannot offer the background grant is not asked for it`() = runTest {
        // The fake reports no features, which is what an older Health Connect looks like.
        val asked = sync(HealthConnectSync.corePermissions).grantablePermissions()

        assertThat(asked).containsNoneIn(HealthConnectSync.backgroundPermissions)
        assertThat(asked).containsAtLeastElementsIn(HealthConnectSync.corePermissions)
    }

    @Test
    fun `the hourly exchange still runs where the grant does not exist`() = runTest {
        // Those phones read in the background without asking, which is how this worked before
        // the grant existed. Demanding one nobody can give would cancel the job for good.
        assertThat(sync(HealthConnectSync.corePermissions).backgroundReadIsPossible()).isTrue()
    }

    @Test
    fun `nobody holding it means there is nothing to run`() = runTest {
        profiles.ensureDefault()
        val only = profiles.observeAll().first().single().id
        val sync = sync(HealthConnectSync.corePermissions)
        assertThat(sync.isTiedToAProfile()).isTrue()

        profiles.setHealthConnect(only, enabled = false)

        // Otherwise the hourly job goes on running against a connection that has been given up,
        // failing and retrying every hour for ever.
        assertThat(sync.isTiedToAProfile()).isFalse()
    }

    @Test
    fun `an install that has never answered is still worth running`() = runTest {
        profiles.ensureDefault()

        // Nobody has said yes and nobody has said no. The first run settles it.
        assertThat(sync(HealthConnectSync.corePermissions).isTiedToAProfile()).isTrue()
    }

    @Test
    fun `the background grant is asked for in the manifest under the name the library uses`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val requested = HealthConnectSync.backgroundPermissions.single().substringAfterLast('.')

        assertThat(manifest).contains("android.permission.health.$requested")
        assertThat(requested).isEqualTo("READ_HEALTH_DATA_IN_BACKGROUND")
        assertThat(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            .isEqualTo(HealthConnectSync.backgroundPermissions.single())
        // Not in the core set: refusing it costs the hourly run, not the feature.
        assertThat(HealthConnectSync.corePermissions)
            .containsNoneIn(HealthConnectSync.backgroundPermissions)
        assertThat(HealthPermission.getReadPermission(WeightRecord::class))
            .isIn(HealthConnectSync.corePermissions)
    }
}
