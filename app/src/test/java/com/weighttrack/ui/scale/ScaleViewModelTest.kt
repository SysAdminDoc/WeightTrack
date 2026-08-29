package com.weighttrack.ui.scale

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.ble.ScaleConnection
import com.weighttrack.ble.ScaleConnectionEvent
import com.weighttrack.ble.ScaleDevice
import com.weighttrack.ble.ScaleKind
import com.weighttrack.ble.ScaleMatch
import com.weighttrack.ble.ScaleProblem
import com.weighttrack.ble.ScaleScanEvent
import com.weighttrack.ble.ScaleScanner
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.scale.AssembledReading
import com.weighttrack.core.scale.ScaleBroadcast
import com.weighttrack.core.scale.ScaleReading
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.wear.NoWearBridge
import com.weighttrack.wear.WearSummaryBuilder
import com.weighttrack.widget.SurfaceUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * The weigh-in, driven end to end with a fake scale.
 *
 * This exists because the first version of this screen never recorded anything at all and the
 * whole project still built, passed and installed. The reading arrives inside the scan
 * collector, and the code that followed cancelled that very collector and then suspended, so it
 * never reached its own next line. Nothing but running it catches that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScaleViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val dispatcher = StandardTestDispatcher()

    private lateinit var database: WeightTrackDatabase
    private lateinit var weightRepository: WeightRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var surfaceUpdater: SurfaceUpdater
    private lateinit var profiles: ProfileRepository

    // Replaying, so an event emitted a moment before the view model subscribes is not lost and
    // the test is about the view model rather than about scheduling.
    private val scanEvents = MutableSharedFlow<ScaleScanEvent>(replay = 16, extraBufferCapacity = 32)
    private val connectionEvents =
        MutableSharedFlow<ScaleConnectionEvent>(replay = 16, extraBufferCapacity = 32)
    private var scanProblem: ScaleProblem? = null
    private var connectedAddress: String? = null

    private val scanner = object : ScaleScanner {
        override fun problem(): ScaleProblem? = scanProblem
        override fun requiredPermissions(): List<String> = listOf("android.permission.BLUETOOTH_SCAN")
        override fun scan(): Flow<ScaleScanEvent> = scanEvents
    }

    private val connection = object : ScaleConnection {
        override fun connect(device: ScaleDevice): Flow<ScaleConnectionEvent> {
            connectedAddress = device.address
            return connectionEvents
        }
    }

    private val broadcastScale = ScaleDevice("AA:BB:CC:DD:EE:FF", "MIBFS", ScaleKind.BROADCAST)
    private val standardScale = ScaleDevice("11:22:33:44:55:66", "Weigh", ScaleKind.STANDARD_SERVICE)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(context, WeightTrackDatabase::class.java)
            .allowMainThreadQueries()
            // Room dispatches suspending queries to its own executor, which is a real thread
            // pool and therefore invisible to the test scheduler. Advancing time would return
            // while the view model was still waiting on its first read.
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        settingsRepository = testSettingsRepository()
        profiles = ProfileRepository(
            database.profileDao(),
            settingsRepository,
            com.weighttrack.data.repo.DeletionRecorder(database.deletionDao(), database.syncDao()),
            database.weightEntryDao(),
        )
        weightRepository = WeightRepository(database.weightEntryDao(), profiles, com.weighttrack.data.repo.DeletionRecorder(database.deletionDao(), database.syncDao()))
        surfaceUpdater = SurfaceUpdater(
            context = context,
            wearBridge = NoWearBridge(),
            wearSummaryBuilder = WearSummaryBuilder(
                weightRepository = weightRepository,
                goalRepository = com.weighttrack.data.repo.GoalRepository(
                    database.goalDao(),
                    profiles,
                    com.weighttrack.data.repo.DeletionRecorder(database.deletionDao(), database.syncDao()),
                ),
                settingsRepository = settingsRepository,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ScaleViewModel(
        scanner = scanner,
        connection = connection,
        weightRepository = weightRepository,
        profileRepository = profiles,
        settingsRepository = settingsRepository,
        surfaceUpdater = surfaceUpdater,
    )

    private fun broadcast(grams: Int, stabilized: Boolean = true) = ScaleScanEvent.Broadcast(
        device = broadcastScale,
        broadcast = ScaleBroadcast(
            reading = ScaleReading(grams = grams),
            stabilized = stabilized,
            weightRemoved = false,
        ),
    )

    @Test
    fun `a settled broadcast is recorded`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        scanEvents.emit(broadcast(82_500))
        advanceUntilIdle()

        assertThat(viewModel.state.value.reading?.grams).isEqualTo(82_500)
        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.SAVED)
        val entry = weightRepository.latest()!!
        assertThat(entry.grams).isEqualTo(82_500)
        assertThat(entry.source).isEqualTo(EntrySource.SCALE)
        // The scale is remembered so the next weigh-in does not start with a hunt.
        assertThat(settingsRepository.settings.first().scaleAddress)
            .isEqualTo(broadcastScale.address)
    }

    @Test
    fun `a weight still settling is shown but not recorded`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        scanEvents.emit(broadcast(82_300, stabilized = false))
        advanceUntilIdle()

        assertThat(viewModel.state.value.liveGrams).isEqualTo(82_300)
        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.WAITING_FOR_WEIGHT)
        assertThat(weightRepository.latest()).isNull()
    }

    @Test
    fun `a reading far from the last one waits to be confirmed`() = runTest(dispatcher) {
        weightRepository.add(grams = 82_500)
        val viewModel = viewModel()
        advanceUntilIdle()

        // Somebody else in the house steps on the same scale.
        scanEvents.emit(broadcast(62_000))
        advanceUntilIdle()

        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.MEASURED)
        assertThat(viewModel.state.value.match).isEqualTo(ScaleMatch.OUT_OF_RANGE)
        assertThat(viewModel.state.value.needsConfirming).isTrue()
        assertThat(weightRepository.observeEntries().first()).hasSize(1)

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.SAVED)
        assertThat(weightRepository.latest()!!.grams).isEqualTo(62_000)
    }

    @Test
    fun `saying it is not me records nothing and starts looking again`() = runTest(dispatcher) {
        weightRepository.add(grams = 82_500)
        val viewModel = viewModel()
        advanceUntilIdle()

        scanEvents.emit(broadcast(62_000))
        advanceUntilIdle()
        viewModel.discard()
        advanceUntilIdle()

        // The scale is still there and still talking, so the screen goes back to asking for a
        // weight rather than back to hunting for a scale.
        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.WAITING_FOR_WEIGHT)
        assertThat(viewModel.state.value.reading).isNull()
        assertThat(weightRepository.observeEntries().first()).hasSize(1)

        // The scale is still broadcasting the same settled weight, which is what makes this
        // worth testing: without a memory of the refusal it comes straight back.
        scanEvents.emit(broadcast(62_000))
        advanceUntilIdle()
        assertThat(viewModel.state.value.reading).isNull()
        assertThat(weightRepository.observeEntries().first()).hasSize(1)

        // The person themselves steps on, and that is a different weight.
        scanEvents.emit(broadcast(82_400))
        advanceUntilIdle()
        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.SAVED)
        assertThat(weightRepository.latest()!!.grams).isEqualTo(82_400)
    }

    @Test
    fun `a body composition arriving after the weight is stored with it`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        scanEvents.emit(ScaleScanEvent.Found(standardScale))
        advanceUntilIdle()
        viewModel.connectTo(standardScale)
        advanceUntilIdle()
        assertThat(connectedAddress).isEqualTo(standardScale.address)

        // The scale sends the weight, then the body fat a heartbeat later. Recording the moment
        // the weight lands would store the weigh-in with the composition missing.
        connectionEvents.emit(
            ScaleConnectionEvent.Measured(
                AssembledReading(ScaleReading(grams = 82_500), revisesPrevious = false),
            ),
        )
        advanceTimeBy(ScaleViewModel.SETTLE_MILLIS / 2)
        connectionEvents.emit(
            ScaleConnectionEvent.Measured(
                AssembledReading(
                    ScaleReading(grams = 82_500, bodyFatPercent = 21.2),
                    revisesPrevious = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertThat(weightRepository.observeEntries().first()).hasSize(1)
        assertThat(weightRepository.latest()!!.bodyFatPercent!!).isWithin(1e-9).of(21.2)
    }

    @Test
    fun `a scale that hangs up after sending still gets its reading stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.connectTo(standardScale)
        advanceUntilIdle()

        connectionEvents.emit(
            ScaleConnectionEvent.Measured(
                AssembledReading(ScaleReading(grams = 82_500), revisesPrevious = false),
            ),
        )
        connectionEvents.emit(ScaleConnectionEvent.Failed(ScaleProblem.CONNECTION_LOST))
        advanceUntilIdle()

        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.SAVED)
        assertThat(weightRepository.latest()!!.grams).isEqualTo(82_500)
    }

    @Test
    fun `losing the connection before any weight is reported as a problem`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.connectTo(standardScale)
        advanceUntilIdle()

        connectionEvents.emit(ScaleConnectionEvent.Failed(ScaleProblem.CONNECTION_LOST))
        advanceUntilIdle()

        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.BLOCKED)
        assertThat(viewModel.state.value.problem).isEqualTo(ScaleProblem.CONNECTION_LOST)
    }

    @Test
    fun `a missing permission stops the scan and says so`() = runTest(dispatcher) {
        scanProblem = ScaleProblem.PERMISSION_MISSING
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.BLOCKED)
        assertThat(viewModel.state.value.problem).isEqualTo(ScaleProblem.PERMISSION_MISSING)
    }

    @Test
    fun `a weight no person has is ignored entirely`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        scanEvents.emit(broadcast(600))
        advanceUntilIdle()

        assertThat(viewModel.state.value.reading).isNull()
        assertThat(weightRepository.latest()).isNull()
    }

    @Test
    fun `a reading that looks like somebody else is offered to them and not recorded yet`() =
        runTest {
            profiles.ensureDefault()
            val me = profiles.activeId()
            weightRepository.add(grams = 82_500)
            val sam = profiles.add("Sam")
            weightRepository.add(grams = 64_000)
            profiles.setActive(me)
            val viewModel = viewModel()
            advanceUntilIdle()

            scanEvents.emit(broadcast(63_900))
            advanceUntilIdle()

            assertThat(viewModel.state.value.suggestedProfile?.id).isEqualTo(sam)
            // Nothing is recorded while the question is on screen.
            assertThat(weightRepository.observeEntries().first()).hasSize(1)
        }

    @Test
    fun `taking the offer records it for them without switching the app over`() = runTest {
        profiles.ensureDefault()
            val me = profiles.activeId()
            weightRepository.add(grams = 82_500)
            val sam = profiles.add("Sam")
            weightRepository.add(grams = 64_000)
            profiles.setActive(me)
        val viewModel = viewModel()
        advanceUntilIdle()
        scanEvents.emit(broadcast(63_900))
        advanceUntilIdle()

        viewModel.saveToSuggested()
        advanceUntilIdle()

        assertThat(weightRepository.latestFor(sam)!!.grams).isEqualTo(63_900)
        // Somebody weighing themselves has not asked to start looking at their partner's
        // history, and being left on it would send the next entry to the wrong person.
        assertThat(profiles.activeId()).isEqualTo(me)
        assertThat(viewModel.state.value.stage).isEqualTo(ScaleStage.SAVED)
        // The offer is answered, so it cannot be taken a second time.
        assertThat(viewModel.state.value.suggestedProfile).isNull()
    }

    @Test
    fun `saying it is mine answers the offer rather than leaving it on screen`() = runTest {
        profiles.ensureDefault()
        val me = profiles.activeId()
        weightRepository.add(grams = 82_500)
        profiles.add("Sam")
        weightRepository.add(grams = 64_000)
        profiles.setActive(me)
        val viewModel = viewModel()
        advanceUntilIdle()
        scanEvents.emit(broadcast(63_900))
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.state.value.suggestedProfile).isNull()
        assertThat(weightRepository.latestFor(me)!!.grams).isEqualTo(63_900)
    }

    @Test
    fun `discarding clears the offer as well as the reading`() = runTest {
        profiles.ensureDefault()
        val me = profiles.activeId()
        weightRepository.add(grams = 82_500)
        profiles.add("Sam")
        weightRepository.add(grams = 64_000)
        profiles.setActive(me)
        val viewModel = viewModel()
        advanceUntilIdle()
        scanEvents.emit(broadcast(63_900))
        advanceUntilIdle()

        viewModel.discard()
        advanceUntilIdle()

        // Left behind, the card sits there through the next search with no weight to save.
        assertThat(viewModel.state.value.suggestedProfile).isNull()
        assertThat(viewModel.state.value.reading).isNull()
    }
}
