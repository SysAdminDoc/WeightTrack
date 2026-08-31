package com.weighttrack.ui.settings

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.io.AutoBackupScheduler
import com.weighttrack.data.io.BackupService
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.ProgressPhotoRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.sync.SyncEngine
import com.weighttrack.data.sync.SyncPreferences
import com.weighttrack.data.sync.SyncSettings
import com.weighttrack.diagnostics.CrashLogStore
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.notifications.ReminderScheduler
import com.weighttrack.notifications.WeeklySummaryScheduler
import com.weighttrack.sync.SyncScheduler
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.UndoCoordinator
import com.weighttrack.widget.SurfaceUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

/**
 * The constructor reaches for state that other properties own.
 *
 * Kotlin builds properties in the order they are written, so anything the `init` block touches has
 * to be declared above it. Get that wrong and the app dies on the way to the settings screen with
 * a null pointer and no clue in it. This used to be guarded by searching the file for the line
 * that declares the flow, which passes just as happily when the declaration is moved somewhere it
 * cannot help and breaks on a rename that changes nothing, so it is built here instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelInitializationTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestDispatcher() {
        // Standard, not unconfined: what is being checked is what the constructor does before any
        // of the work it starts has had a turn to run.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun releaseDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `everything the constructor reads is built before it runs`() {
        val viewModel = build()

        assertThat(viewModel.autoBackup.value).isEqualTo(AutoBackupState())
        assertThat(viewModel.healthConnectState.value).isEqualTo(HealthConnectState())
        assertThat(viewModel.crashReportCount.value).isEqualTo(0)
        assertThat(viewModel.busy.value).isFalse()
        assertThat(viewModel.pendingRestore.value).isNull()
        assertThat(viewModel.message.value).isNull()
        assertThat(viewModel.syncing.value).isFalse()
    }

    private fun build(): SettingsViewModel {
        val settingsRepository = mock(SettingsRepository::class.java)
        doReturn(flowOf(AppSettings())).`when`(settingsRepository).settings

        val weightRepository = mock(WeightRepository::class.java)
        doReturn(flowOf(0)).`when`(weightRepository).observeCount()

        val profileRepository = mock(ProfileRepository::class.java)
        doReturn(flowOf(emptyList<Nothing>())).`when`(profileRepository).observeAll()
        doReturn(flowOf(1L)).`when`(profileRepository).activeProfileId
        doReturn(flowOf(null)).`when`(profileRepository).activeProfile

        val syncPreferences = mock(SyncPreferences::class.java)
        doReturn(MutableStateFlow(SyncSettings())).`when`(syncPreferences).settings

        return SettingsViewModel(
            strings = mock(AppStrings::class.java),
            syncPreferences = syncPreferences,
            syncEngine = mock(SyncEngine::class.java),
            syncScheduler = mock(SyncScheduler::class.java),
            settingsRepository = settingsRepository,
            weightRepository = weightRepository,
            profileRepository = profileRepository,
            progressPhotoRepository = mock(ProgressPhotoRepository::class.java),
            backupService = mock(BackupService::class.java),
            autoBackupScheduler = mock(AutoBackupScheduler::class.java),
            reminderScheduler = mock(ReminderScheduler::class.java),
            weeklySummaryScheduler = mock(WeeklySummaryScheduler::class.java),
            crashLogStore = mock(CrashLogStore::class.java),
            surfaces = mock(SurfaceUpdater::class.java),
            undoOffers = mock(UndoCoordinator::class.java),
            context = mock(Context::class.java),
            healthConnect = mock(HealthConnectSync::class.java),
        )
    }
}
