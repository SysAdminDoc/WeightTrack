package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.GoalEntity
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.db.toDomain
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A goal date nobody can read means the same thing tomorrow as it does today.
 *
 * It used to be read as the current date. The same damaged row therefore said something
 * different every morning: the progress bar moved, the projected date moved, and nothing
 * anywhere said the date could not be read. A wrong answer that changes daily is worse than a
 * wrong answer, because nothing built on it can be reproduced or reported.
 */
@RunWith(RobolectricTestRunner::class)
class GoalDateRepairTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var goals: GoalRepository

    /** The day the goal was made, which is what a damaged date falls back to. */
    private val createdAt = Instant.parse("2026-06-01T09:00:00Z")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settings = testSettingsRepository()
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        val profiles = ProfileRepository(
            database.profileDao(),
            settings,
            deletions,
            database.weightEntryDao(),
        )
        goals = GoalRepository(database.goalDao(), profiles, deletions)
    }

    @After
    fun tearDown() = database.close()

    private suspend fun damagedGoal(startDate: String, targetDate: String? = null): Long =
        database.goalDao().insert(
            GoalEntity(
                profileId = 1,
                direction = "LOSE",
                startGrams = 84_000,
                targetGrams = 76_000,
                startDate = startDate,
                targetDate = targetDate,
                milestoneStepGrams = 2_000,
                active = true,
                createdAtUtcMillis = createdAt.toEpochMilli(),
                syncId = "g-1",
                updatedAtUtcMillis = createdAt.toEpochMilli(),
            ),
        )

    @Test
    fun `an unreadable date reads the same on any day`() = runTest {
        damagedGoal("not a date at all")

        val first = checkNotNull(database.goalDao().byId(1)).toDomain().startDate
        val second = checkNotNull(database.goalDao().byId(1)).toDomain().startDate

        // Derived from the moment the row itself records rather than from the clock, so it is
        // the same answer next week as it is now.
        assertThat(first).isEqualTo(second)
        assertThat(first).isEqualTo(createdAt.atOffset(ZoneOffset.UTC).toLocalDate())
        assertThat(first).isNotEqualTo(LocalDate.now())
    }

    @Test
    fun `the repair writes a readable date back over the damaged one`() = runTest {
        damagedGoal("not a date at all")

        val repaired = goals.repairUnreadableDates()

        // Named, not counted: the log is allowed no identifiers, so the caller gets them and
        // can say which goal it was.
        assertThat(repaired).containsExactly("g-1")
        // Written back, so the row stops being damaged rather than being read forgivingly for
        // ever. Not stamped: a row damaged only here is a local fault, and stamping it would
        // push this phone's guess over a perfectly readable copy on the other one.
        val row = checkNotNull(database.goalDao().byId(1))
        assertThat(row.startDate).isEqualTo("2026-06-01")
        assertThat(row.updatedAtUtcMillis).isEqualTo(createdAt.toEpochMilli())
    }

    @Test
    fun `a target date nobody can read becomes no target date`() = runTest {
        damagedGoal("2026-06-01", targetDate = "sometime")

        goals.repairUnreadableDates()

        // Guessing one would put a deadline on somebody's goal that they never set.
        assertThat(checkNotNull(database.goalDao().byId(1)).targetDate).isNull()
    }

    @Test
    fun `a goal that reads perfectly well is left exactly as it is`() = runTest {
        damagedGoal("2026-06-01", targetDate = "2026-12-01")
        val before = checkNotNull(database.goalDao().byId(1))

        val repaired = goals.repairUnreadableDates()

        assertThat(repaired).isEmpty()
        assertThat(database.goalDao().byId(1)).isEqualTo(before)
    }

    @Test
    fun `repairing twice changes nothing the second time`() = runTest {
        damagedGoal("not a date at all")
        goals.repairUnreadableDates()

        assertThat(goals.repairUnreadableDates()).isEmpty()
    }
}
