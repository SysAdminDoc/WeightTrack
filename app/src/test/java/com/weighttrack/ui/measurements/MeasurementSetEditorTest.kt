package com.weighttrack.ui.measurements

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.MeasurementRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.UndoCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * The half of the measurement set that lives in the view model.
 *
 * Every clause of what a set is meant to do is a statement about this layer, and the first round
 * of tests only covered the repository underneath it. Three things went wrong here and nowhere
 * else: a carried value changed by a millimetre on the way through the text box, clearing a box
 * dropped that site out of the set entirely, and neither was visible on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MeasurementSetEditorTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var measurements: MeasurementRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        settings = testSettingsRepository()
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(
            database.profileDao(),
            settings,
            deletions,
            database.weightEntryDao(),
        )
        measurements = MeasurementRepository(database.measurementDao(), profiles, deletions)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = MeasurementsViewModel(
        measurementRepository = measurements,
        settingsRepository = settings,
        strings = mock(AppStrings::class.java),
        undoOffers = mock(UndoCoordinator::class.java),
    )

    /**
     * What was measured last week.
     *
     * Backdated on purpose: a set written today has to be told apart from the history it carried
     * forward, and both landing on the same date makes every assertion below meaningless.
     */
    private suspend fun history(vararg sites: Pair<MeasurementType, Int>) {
        profiles.ensureDefault()
        val lastWeek = java.time.Instant.now().minus(java.time.Duration.ofDays(7))
        sites.forEach { (type, mm) ->
            measurements.add(type = type, valueMm = mm, timestamp = lastWeek)
        }
    }

    @Test
    fun `carrying a value in inches does not change it`() = runTest(dispatcher) {
        // 865 mm is 34.0551 inches, which the box shows as 34.1, which parses back as 866. A
        // value nobody touched must not come out a millimetre different from the one it copied.
        settings.setLengthUnit(LengthUnit.IN)
        history(MeasurementType.WAIST to 865, MeasurementType.CHEST to 1_020)
        val viewModel = viewModel()

        viewModel.startSet()
        viewModel.onSetValueChange(MeasurementType.CHEST, "40.4")
        viewModel.saveSet()

        val today = measurements.observeAll().first()
            .filter { it.localDate == java.time.LocalDate.now() }
            .associateBy { it.type }
        assertThat(today.getValue(MeasurementType.WAIST).valueMm).isEqualTo(865)
        assertThat(today.getValue(MeasurementType.WAIST).carried).isTrue()
    }

    @Test
    fun `clearing a box does not drop the site out of the set`() = runTest(dispatcher) {
        // Somebody starts retyping the chest, changes their mind, and leaves the box empty. The
        // site was measured last week and is still true; dropping it silently made the "complete
        // set" incomplete with nothing on screen to say so.
        history(MeasurementType.WAIST to 880, MeasurementType.CHEST to 1_020)
        val viewModel = viewModel()

        viewModel.startSet()
        viewModel.onSetValueChange(MeasurementType.CHEST, "")
        viewModel.onSetValueChange(MeasurementType.WAIST, "86.5")
        viewModel.saveSet()

        val today = measurements.observeAll().first()
            .filter { it.localDate == java.time.LocalDate.now() }
            .associateBy { it.type }
        assertThat(today.keys).contains(MeasurementType.CHEST)
        assertThat(today.getValue(MeasurementType.CHEST).valueMm).isEqualTo(1_020)
        assertThat(today.getValue(MeasurementType.CHEST).carried).isTrue()
        assertThat(today.getValue(MeasurementType.WAIST).carried).isFalse()
    }

    @Test
    fun `a cleared box goes back to reading as carried on screen`() = runTest(dispatcher) {
        history(MeasurementType.CHEST to 1_020)
        val viewModel = viewModel()

        viewModel.startSet()
        viewModel.onSetValueChange(MeasurementType.CHEST, "40.4")
        assertThat(viewModel.measurementSet.value!!.isCarried(MeasurementType.CHEST)).isFalse()

        viewModel.onSetValueChange(MeasurementType.CHEST, "")

        val open = viewModel.measurementSet.value!!
        assertThat(open.changed).doesNotContain(MeasurementType.CHEST)
        assertThat(open.hasAnyChange).isFalse()
    }

    @Test
    fun `changing one site saves every site that has ever been measured`() = runTest(dispatcher) {
        // The acceptance's "all thirteen", under its own premise: every site has a value to
        // carry, so changing one writes the lot.
        val everySite = MeasurementType.entries.mapIndexed { index, type ->
            type to (300 + index * 40)
        }
        history(*everySite.toTypedArray())
        val viewModel = viewModel()

        viewModel.startSet()
        viewModel.onSetValueChange(MeasurementType.WAIST, "86.5")
        viewModel.saveSet()

        val today = measurements.observeAll().first()
            .filter { it.localDate == java.time.LocalDate.now() }
        assertThat(today.map { it.type }).containsExactlyElementsIn(MeasurementType.entries)
        assertThat(today.count { it.carried }).isEqualTo(MeasurementType.entries.size - 1)
    }

    @Test
    fun `opening and closing a set writes nothing`() = runTest(dispatcher) {
        history(MeasurementType.WAIST to 880, MeasurementType.CHEST to 1_020)
        val before = measurements.observeAll().first().size
        val viewModel = viewModel()

        viewModel.startSet()
        viewModel.saveSet()

        assertThat(measurements.observeAll().first()).hasSize(before)
    }

    @Test
    fun `a site nobody has ever measured is not invented`() = runTest(dispatcher) {
        history(MeasurementType.WAIST to 880)
        val viewModel = viewModel()

        viewModel.startSet()
        viewModel.onSetValueChange(MeasurementType.WAIST, "86.5")
        viewModel.saveSet()

        val today = measurements.observeAll().first()
            .filter { it.localDate == java.time.LocalDate.now() }
        assertThat(today.map { it.type }).containsExactly(MeasurementType.WAIST)
    }
}
