package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.testProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class FastRepositoryTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var repository: FastRepository

    private val start: Instant = Instant.parse("2026-01-01T20:00:00Z")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = FastRepository(database.fastDao(), testProfileRepository(database))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a second start is refused rather than closing the running fast`() = runTest {
        val first = repository.start(targetMinutes = 16 * 60, at = start)
        assertThat(first).isNotNull()

        // A double tap 50 ms apart used to end the running fast at the second tap, writing a
        // zero length fast into history and leaving the user fasting against a new record.
        val second = repository.start(targetMinutes = 18 * 60, at = start.plusMillis(50))

        assertThat(second).isNull()
        assertThat(repository.observeCompleted().first()).isEmpty()
        assertThat(repository.active()!!.targetMinutes).isEqualTo(16 * 60)
        assertThat(repository.active()!!.start).isEqualTo(start)
    }

    @Test
    fun `a start after a backwards clock correction cannot record a fast that ends first`() =
        runTest {
            repository.start(targetMinutes = 16 * 60, at = start)
            // The clock jumps back two hours between the two taps.
            repository.start(targetMinutes = 16 * 60, at = start.minus(Duration.ofHours(2)))

            assertThat(repository.observeCompleted().first()).isEmpty()
            val open = repository.active()!!
            assertThat(open.end).isNull()
            assertThat(open.start).isEqualTo(start)
        }

    @Test
    fun `stopping before the start clamps rather than storing a negative fast`() = runTest {
        repository.start(targetMinutes = 16 * 60, at = start)
        repository.stop(at = start.minus(Duration.ofHours(3)))

        val recorded = repository.observeCompleted().first().single()
        assertThat(recorded.elapsed(start)).isEqualTo(Duration.ZERO)
        assertThat(recorded.end).isEqualTo(recorded.start)
    }

    @Test
    fun `a running fast can be given an earlier start`() = runTest {
        repository.start(targetMinutes = 16 * 60, at = start)
        val running = repository.active()!!

        val result = repository.update(running.copy(start = start.minus(Duration.ofHours(2))))

        assertThat(result).isEqualTo(FastUpdateResult.SAVED)
        val corrected = repository.active()!!
        assertThat(corrected.end).isNull()
        assertThat(corrected.start).isEqualTo(start.minus(Duration.ofHours(2)))
        assertThat(corrected.elapsed(start)).isEqualTo(Duration.ofHours(2))
    }

    @Test
    fun `an edit that ends before it starts is refused and reported`() = runTest {
        repository.start(targetMinutes = 16 * 60, at = start)
        repository.stop(at = start.plus(Duration.ofHours(16)))
        val recorded = repository.observeCompleted().first().single()

        val result = repository.update(recorded.copy(end = start.minus(Duration.ofHours(1))))

        assertThat(result).isEqualTo(FastUpdateResult.BACKWARDS)
        // The stored row is untouched, so the dialog can say so without lying about the data.
        assertThat(repository.observeCompleted().first().single().end)
            .isEqualTo(start.plus(Duration.ofHours(16)))
    }

    @Test
    fun `editing a fast that has been deleted underneath is reported`() = runTest {
        repository.start(targetMinutes = 16 * 60, at = start)
        repository.stop(at = start.plus(Duration.ofHours(16)))
        val recorded = repository.observeCompleted().first().single()
        repository.delete(recorded)

        val result = repository.update(recorded.copy(start = start.minus(Duration.ofHours(1))))

        assertThat(result).isEqualTo(FastUpdateResult.MISSING)
    }

    @Test
    fun `starting again after stopping records the finished fast and opens a new one`() = runTest {
        repository.start(targetMinutes = 16 * 60, at = start)
        repository.stop(at = start.plus(Duration.ofHours(16)))
        val second = repository.start(targetMinutes = 18 * 60, at = start.plus(Duration.ofHours(24)))

        assertThat(second).isNotNull()
        assertThat(repository.observeCompleted().first()).hasSize(1)
        assertThat(repository.active()!!.targetMinutes).isEqualTo(18 * 60)
    }
}
