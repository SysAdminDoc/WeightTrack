package com.weighttrack.ui.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.testProfileRepository
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.UndoCoordinator
import com.weighttrack.ui.UndoOffer
import com.weighttrack.wear.NoWearBridge
import com.weighttrack.wear.WearSummaryBuilder
import com.weighttrack.widget.SurfaceUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The undo on the history screen, from the tap to the row coming back.
 *
 * The repository half is covered by `UndoDeleteTest`. This is the wiring above it, which nothing
 * tested: the offer being made, the answer reaching the repository, and the deletion standing
 * when nobody takes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryUndoTest {

    /**
     * Unconfined, with Room driven on the calling thread.
     *
     * The view model fires its work off into its own scope and hands back nothing to wait on,
     * and Room's own executor is a real thread pool, so every assertion here waits for the
     * effect rather than for the call.
     */
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var weights: WeightRepository
    private lateinit var undo: UndoCoordinator
    private lateinit var model: HistoryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, WeightTrackDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        settings = testSettingsRepository()
        val profiles = testProfileRepository(database, settings)
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        weights = WeightRepository(database.weightEntryDao(), profiles, deletions)
        undo = UndoCoordinator()
        model = HistoryViewModel(
            weightRepository = weights,
            SurfaceUpdater = SurfaceUpdater(
                context,
                NoWearBridge(),
                WearSummaryBuilder(
                    weights,
                    GoalRepository(database.goalDao(), profiles, deletions),
                    settings,
                ),
            ),
            strings = AppStrings(context),
            undoOffers = undo,
            settingsRepository = settings,
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun seed(grams: Int = 80_000, at: Long = 1_800_000_000_000) {
        weights.add(grams = grams, timestamp = Instant.ofEpochMilli(at))
    }

    /** The offer, once it has been made. */
    private suspend fun awaitOffer(): UndoOffer = undo.offer.filterNotNull().first()

    /** The history, once it holds this many rows. */
    private suspend fun awaitRows(count: Int) =
        weights.observeEntries().first { it.size == count }

    @Test
    fun `deleting a reading offers to put it back`() = runTest(dispatcher) {
        seed()

        model.delete(awaitRows(1).single())

        assertThat(awaitOffer().message).isEqualTo("Reading deleted")
        assertThat(awaitRows(0)).isEmpty()
    }

    @Test
    fun `taking the undo brings the reading back`() = runTest(dispatcher) {
        seed()
        val before = awaitRows(1).single()

        model.delete(before)
        awaitOffer()
        undo.undo()

        val after = awaitRows(1).single()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.clientRecordId).isEqualTo(before.clientRecordId)
        // And nothing is left offering to do it again.
        assertThat(undo.offer.value).isNull()
    }

    @Test
    fun `letting the offer go leaves the deletion where it is`() = runTest(dispatcher) {
        seed()

        model.delete(awaitRows(1).single())
        awaitOffer()
        undo.dismiss()

        assertThat(awaitRows(0)).isEmpty()
        assertThat(undo.offer.value).isNull()
    }

    @Test
    fun `deleting several at once says how many and puts all of them back`() = runTest(dispatcher) {
        seed()
        seed(grams = 79_500, at = 1_799_900_000_000)
        val ids = awaitRows(2).map { it.id }.toSet()
        ids.forEach { model.toggleSelection(it) }

        model.deleteSelected()

        assertThat(awaitOffer().message).isEqualTo("2 readings deleted")
        assertThat(awaitRows(0)).isEmpty()

        undo.undo()

        assertThat(awaitRows(2).map { it.id }).containsExactlyElementsIn(ids)
    }

    @Test
    fun `a second deletion offers itself rather than reusing the first`() = runTest(dispatcher) {
        seed()
        seed(grams = 79_500, at = 1_799_900_000_000)
        val entries = awaitRows(2)

        model.delete(entries[0])
        val first = awaitOffer().sequence
        model.delete(entries[1])
        val second = undo.offer.first { it != null && it.sequence > first }!!

        // Keyed on the sentence alone, the second deletion put nothing on screen and the person
        // had no way back from something they could see had happened.
        assertThat(second.sequence).isGreaterThan(first)
    }
}
