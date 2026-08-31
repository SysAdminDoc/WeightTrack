package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.AdaptiveExpenditure
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.domain.ProgressSnapshot
import com.weighttrack.ui.diary.DiaryViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * The goal rows the app actually writes, rather than the ones a test finds convenient.
 *
 * The first version of the goal-switch correction was checked against a goal hand-built with a
 * start date of the day the target changed. The app never writes that: editing a goal keeps the
 * date it started from so the progress bar does not reset, so the correction read a start date
 * two months old and never fired once on the only path a person can change a goal by.
 *
 * So this one goes through the repository.
 */
@RunWith(RobolectricTestRunner::class)
class GoalChangeStampsTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var goals: GoalRepository

    private val today = LocalDate.of(2026, 9, 10)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val measured = AdaptiveExpenditure.Estimate(
        kcalPerDay = 2_400.0,
        from = today.minusDays(13),
        to = today,
        days = 14,
        loggedDays = 14,
        weighIns = 14,
        meanIntakeKcal = 1_900.0,
        trendChangeKg = -1.6,
    )

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
        goals = GoalRepository(database.goalDao(), profiles, deletions)
    }

    @After
    fun tearDown() = database.close()

    private fun snapshot(goal: com.weighttrack.core.model.Goal?) =
        ProgressSnapshot.empty(AppSettings()).copy(
            goal = goal,
            entryCount = 14,
            series = TrendSeries(
                listOf(TrendPoint(today, 78_400.0, 78_400)),
                TrendEngine.alphaForWindow(10),
            ),
        )

    @Test
    fun `editing a goal through the repository is seen as a change of target`() = runTest {
        profiles.ensureDefault()
        // Losing, set two months ago.
        goals.setGoal(
            startGrams = 80_000,
            targetGrams = 71_000,
            milestoneStepGrams = 1_000,
            startDate = today.minusDays(60),
        )
        // Edited to maintain today, exactly as GoalViewModel.save writes it: the start date is
        // carried over from the goal being replaced.
        val existing = goals.active()!!
        goals.setGoal(
            startGrams = existing.startGrams,
            targetGrams = 78_400,
            milestoneStepGrams = existing.milestoneStepGrams,
            startDate = existing.startDate,
        )

        val all = goals.observeAll().first()
        val current = goals.active()!!

        // The row really does keep the old start date. That is the trap.
        assertThat(current.startDate).isEqualTo(today.minusDays(60))

        val corrected = DiaryViewModel.afterAnyGoalChange(measured, snapshot(current), all, zone)
        assertThat(corrected.kcalPerDay).isGreaterThan(measured.kcalPerDay)
    }

    @Test
    fun `a goal given up long before today's is not treated as the one it replaced`() = runTest {
        profiles.ensureDefault()
        goals.setGoal(
            startGrams = 80_000,
            targetGrams = 71_000,
            milestoneStepGrams = 1_000,
            startDate = today.minusDays(400),
        )
        // Gave it up rather than replacing it, and lived without a goal for a while.
        goals.clearActive()
        val retiredLongAgo = today.minusDays(400).atStartOfDay(zone).toInstant().toEpochMilli()
        database.goalDao().observeAll(1L).first().forEach { row ->
            database.goalDao().update(row.copy(updatedAtUtcMillis = retiredLongAgo))
        }
        // Only now do they set a new one.
        goals.setGoal(
            startGrams = 78_400,
            targetGrams = 78_400,
            milestoneStepGrams = 1_000,
            startDate = today,
        )

        val corrected = DiaryViewModel.afterAnyGoalChange(
            measured,
            snapshot(goals.active()!!),
            goals.observeAll().first(),
            zone,
        )

        assertThat(corrected).isEqualTo(measured)
    }

    @Test
    fun `a first goal corrects nothing`() = runTest {
        profiles.ensureDefault()
        goals.setGoal(
            startGrams = 80_000,
            targetGrams = 71_000,
            milestoneStepGrams = 1_000,
            startDate = today,
        )

        val corrected = DiaryViewModel.afterAnyGoalChange(
            measured,
            snapshot(goals.active()!!),
            goals.observeAll().first(),
            zone,
        )

        assertThat(corrected).isEqualTo(measured)
    }
}
