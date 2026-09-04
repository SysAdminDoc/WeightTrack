package com.weighttrack.ui.log

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.wear.NoWearBridge
import com.weighttrack.wear.WearSummaryBuilder
import com.weighttrack.widget.SurfaceUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The log screen's view model, and what happens when its own close does not take.
 *
 * The screen shuts itself when [LogWeightUiState.saved] turns true. Reached as the root of the
 * graph, which is what a launcher shortcut used to do, that close pops the only entry there is
 * and nothing moves, so the screen sits there looking unsaved and every further press files
 * another copy of the same morning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LogWeightViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val dispatcher = StandardTestDispatcher()

    private lateinit var database: WeightTrackDatabase
    private lateinit var weightRepository: WeightRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var surfaceUpdater: SurfaceUpdater
    private lateinit var profiles: ProfileRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(context, WeightTrackDatabase::class.java)
            .allowMainThreadQueries()
            // Room's own executor is a real thread pool and invisible to the test scheduler, so
            // advancing time would return while the view model was still waiting on its read.
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        settingsRepository = testSettingsRepository()
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(
            database.profileDao(),
            settingsRepository,
            deletions,
            database.weightEntryDao(),
        )
        weightRepository = WeightRepository(database.weightEntryDao(), profiles, deletions)
        surfaceUpdater = SurfaceUpdater(
            context = context,
            wearBridge = NoWearBridge(),
            wearSummaryBuilder = WearSummaryBuilder(
                weightRepository = weightRepository,
                goalRepository = GoalRepository(database.goalDao(), profiles, deletions),
                settingsRepository = settingsRepository,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = LogWeightViewModel(
        weightRepository = weightRepository,
        settingsRepository = settingsRepository,
        SurfaceUpdater = surfaceUpdater,
        savedStateHandle = SavedStateHandle(),
    )

    /** Types a weight in, the way the keypad does. */
    private fun LogWeightViewModel.type(digits: String) = digits.forEach { onDigit(it) }

    @Test
    fun `a typed weight is filed`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.type("800")
        viewModel.save()
        // Waited for rather than advanced to. The flag is set after the surfaces are refreshed,
        // and that refresh reaches Glance on a dispatcher the test scheduler cannot see, so
        // advanceUntilIdle returns while the save is still finishing.
        viewModel.state.first { it.saved }

        assertThat(weightRepository.observeCount().first()).isEqualTo(1)
        assertThat(weightRepository.latest()?.grams).isEqualTo(80_000)
    }

    @Test
    fun `pressing save again does not file the same weigh-in twice`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.type("800")
        viewModel.save()
        viewModel.state.first { it.saved }
        // What somebody does when the screen does not close: press it again.
        viewModel.save()
        viewModel.save()
        advanceUntilIdle()

        assertThat(weightRepository.observeCount().first()).isEqualTo(1)
    }
}
