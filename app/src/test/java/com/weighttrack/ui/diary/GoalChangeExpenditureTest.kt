package com.weighttrack.ui.diary

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.AdaptiveExpenditure
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.domain.ProgressSnapshot
import org.junit.Test
import java.time.LocalDate

/**
 * What happens to the number on the diary screen the day somebody changes their mind.
 *
 * Expenditure falls while somebody is in a deficit and comes back when they stop, and it does
 * that within days, long before a scale can show it. Handing back a figure measured across the
 * old target recommends a fortnight of eating too little to somebody who has just decided to
 * stop dieting.
 */
class GoalChangeExpenditureTest {

    private val today = LocalDate.of(2026, 5, 20)
    private val changedOn = today.minusDays(3)

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

    private val dieting = Goal(
        id = 1,
        direction = GoalDirection.LOSE,
        startGrams = 80_000,
        // A little under one percent a week over twelve weeks.
        targetGrams = 71_000,
        startDate = today.minusDays(60),
        milestoneStepGrams = 1_000,
        active = false,
    )

    private val holding = dieting.copy(
        id = 2,
        direction = GoalDirection.MAINTAIN,
        startGrams = 78_400,
        targetGrams = 78_400,
        startDate = changedOn,
        active = true,
    )

    private fun snapshot(goal: Goal?) = ProgressSnapshot.empty(AppSettings()).copy(
        goal = goal,
        series = TrendSeries(emptyList(), TrendEngine.alphaForWindow(10)),
        latestEntry = null,
        entryCount = 14,
    )

    /** The snapshot's weight, which is what the adaptation is measured as a percentage of. */
    private fun snapshotWeighing(goal: Goal?, grams: Int) = snapshot(goal).copy(
        series = TrendSeries(
            listOf(
                com.weighttrack.core.math.TrendPoint(today, grams.toDouble(), grams),
            ),
            TrendEngine.alphaForWindow(10),
        ),
    )

    @Test
    fun `switching from losing to holding hands the suppressed expenditure straight back`() {
        val corrected = DiaryViewModel.afterAnyGoalChange(
            measured,
            snapshotWeighing(holding, 78_400),
            listOf(holding, dieting),
        )

        assertThat(corrected.kcalPerDay).isGreaterThan(measured.kcalPerDay)
        // Four times the weekly percentage the old target was asking for.
        val oldRate = AdaptiveExpenditure.rateForGoal(80_000, 71_000, 12.0)
        val expected = 2_400.0 * (1.0 + 4.0 * (0.0 - oldRate) / 78.4)
        assertThat(corrected.kcalPerDay).isWithin(0.01).of(expected)
    }

    @Test
    fun `once the whole window sits after the change the correction goes away`() {
        // Two weeks later. Everything the estimate measured happened under the new target, so
        // the measurement already carries the adaptation and applying it again doubles it.
        val settled = measured.copy(from = changedOn, to = changedOn.plusDays(13))

        val corrected = DiaryViewModel.afterAnyGoalChange(
            settled,
            snapshotWeighing(holding, 78_400),
            listOf(holding, dieting),
        )

        assertThat(corrected).isEqualTo(settled)
    }

    @Test
    fun `nothing happens without a goal, or without one before it`() {
        assertThat(DiaryViewModel.afterAnyGoalChange(measured, snapshot(null), emptyList()))
            .isEqualTo(measured)
        // Somebody's first ever goal. There is no old target for the body to be coming back from.
        assertThat(
            DiaryViewModel.afterAnyGoalChange(
                measured,
                snapshotWeighing(holding, 78_400),
                listOf(holding),
            ),
        ).isEqualTo(measured)
    }

    @Test
    fun `the goal itself is never mistaken for the one it replaced`() {
        // A retired row carrying the same id as the active goal would make the correction cancel
        // to nothing, which is the quiet failure this guards against.
        val corrected = DiaryViewModel.afterAnyGoalChange(
            measured,
            snapshotWeighing(holding, 78_400),
            listOf(holding, holding.copy(active = false), dieting),
        )

        assertThat(corrected.kcalPerDay).isGreaterThan(measured.kcalPerDay)
    }

    @Test
    fun `tightening a target takes expenditure away instead`() {
        val harder = dieting.copy(
            id = 3,
            startGrams = 78_400,
            targetGrams = 66_000,
            startDate = changedOn,
            active = true,
        )

        val corrected = DiaryViewModel.afterAnyGoalChange(
            measured,
            snapshotWeighing(harder, 78_400),
            listOf(harder, dieting),
        )

        assertThat(corrected.kcalPerDay).isLessThan(measured.kcalPerDay)
    }
}
