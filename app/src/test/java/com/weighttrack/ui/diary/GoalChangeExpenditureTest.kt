package com.weighttrack.ui.diary

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.AdaptiveExpenditure
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.domain.ProgressSnapshot
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * What happens to the number on the diary screen the day somebody changes their mind.
 *
 * Expenditure falls while somebody is in a deficit and comes back when they stop, within days,
 * long before a scale can show it. Handing back a figure measured across the old target
 * recommends a fortnight of eating too little to somebody who has just decided to stop dieting.
 *
 * Which goals count as a change is the hard half. Editing a goal keeps the date it started from,
 * so the start date says nothing about when the target moved, and a goal somebody abandoned last
 * year is not the one today's replaced.
 */
class GoalChangeExpenditureTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 5, 20)
    private val changedOn = today.minusDays(3)
    private val changedAt = changedOn.atStartOfDay(zone).toInstant().toEpochMilli()

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

    /** The goal they were on: 80 kg down to 71, started two months ago, retired at the change. */
    private val dieting = Goal(
        id = 1,
        direction = GoalDirection.LOSE,
        startGrams = 80_000,
        targetGrams = 71_000,
        startDate = today.minusDays(60),
        milestoneStepGrams = 1_000,
        active = false,
        setAtUtcMillis = today.minusDays(60).atStartOfDay(zone).toInstant().toEpochMilli(),
        changedAtUtcMillis = changedAt,
    )

    /**
     * What editing that goal to maintain actually writes.
     *
     * The start date is the old one: `GoalViewModel.save` keeps it so the progress bar does not
     * reset. Only the stamp says the target moved.
     */
    private val holding = Goal(
        id = 2,
        direction = GoalDirection.MAINTAIN,
        startGrams = 80_000,
        targetGrams = 78_400,
        startDate = today.minusDays(60),
        milestoneStepGrams = 1_000,
        active = true,
        setAtUtcMillis = changedAt,
        changedAtUtcMillis = changedAt,
    )

    private fun snapshotWeighing(goal: Goal?, grams: Int = 78_400) =
        ProgressSnapshot.empty(AppSettings()).copy(
            goal = goal,
            entryCount = 14,
            series = TrendSeries(
                listOf(TrendPoint(today, grams.toDouble(), grams)),
                TrendEngine.alphaForWindow(10),
            ),
        )

    private fun corrected(goal: Goal?, goals: List<Goal>) =
        DiaryViewModel.afterAnyGoalChange(measured, snapshotWeighing(goal), goals, zone)

    @Test
    fun `the active goal is read from the list, not from the snapshot beside it`() {
        // Two flows fed by the same table pair up one emission behind each other, so the
        // snapshot can carry the new goal while the list still holds the old one, or the
        // reverse. Whichever way round it lands, the correction has to come out of one of them.
        val stale = DiaryViewModel.afterAnyGoalChange(
            measured,
            snapshotWeighing(dieting.copy(active = true)),
            listOf(holding, dieting),
            zone,
        )

        assertThat(stale).isEqualTo(corrected(holding, listOf(holding, dieting)))
    }

    @Test
    fun `editing a goal to maintain is a change, even though the start date did not move`() {
        // The whole point. This is the shape the app's own save path writes, and reading the
        // start date meant the correction never fired on it once.
        val after = corrected(holding, listOf(holding, dieting))

        assertThat(after.kcalPerDay).isGreaterThan(measured.kcalPerDay)
        val oldRate = AdaptiveExpenditure.rateForGoal(80_000, 71_000, 12.0)
        val newRate = AdaptiveExpenditure.rateForGoal(80_000, 78_400, 12.0)
        val expected = 2_400.0 * (1.0 + 4.0 * (newRate - oldRate) / 78.4)
        assertThat(after.kcalPerDay).isWithin(0.01).of(expected)
    }

    @Test
    fun `a goal abandoned a year ago is not the one today's replaced`() {
        // Gave up, maintained for a year without a goal at all, then set one today. There is no
        // suppressed expenditure to hand back: it came back eleven months ago.
        val abandonedLongAgo = dieting.copy(
            changedAtUtcMillis = today.minusDays(400).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

        val after = corrected(holding, listOf(holding, abandonedLongAgo))

        assertThat(after).isEqualTo(measured)
    }

    @Test
    fun `once the whole window sits after the change the correction goes away`() {
        // Two weeks on. Everything the estimate measured happened under the new target, so the
        // measurement carries the adaptation already and applying it again would double it.
        val settled = measured.copy(from = changedOn, to = changedOn.plusDays(13))

        val after = DiaryViewModel.afterAnyGoalChange(
            settled,
            snapshotWeighing(holding),
            listOf(holding, dieting),
            zone,
        )

        assertThat(after).isEqualTo(settled)
    }

    @Test
    fun `nothing happens without a goal, without one before it, or without a stamp`() {
        assertThat(corrected(null, emptyList())).isEqualTo(measured)
        // Somebody's first ever goal. Nothing for the body to be coming back from.
        assertThat(corrected(holding, listOf(holding))).isEqualTo(measured)
        // A row from before the stamps existed says nothing about when anything changed.
        val unstamped = holding.copy(setAtUtcMillis = 0)
        assertThat(corrected(unstamped, listOf(unstamped, dieting))).isEqualTo(measured)
    }

    @Test
    fun `the goal itself is never mistaken for the one it replaced`() {
        val after = corrected(holding, listOf(holding, holding.copy(active = false), dieting))

        assertThat(after.kcalPerDay).isGreaterThan(measured.kcalPerDay)
    }

    @Test
    fun `tightening a target takes expenditure away instead`() {
        val harder = holding.copy(
            id = 3,
            direction = GoalDirection.LOSE,
            targetGrams = 66_000,
        )

        assertThat(corrected(harder, listOf(harder, dieting)).kcalPerDay)
            .isLessThan(measured.kcalPerDay)
    }

    @Test
    fun `a nonsense body weight cannot turn a four per cent correction into forty`() {
        // The percentage is taken against body mass, and body mass comes off the trend line,
        // which a mistyped reading drags a long way. This feeds a recommendation about what
        // somebody eats, so it is capped.
        val tiny = DiaryViewModel.afterAnyGoalChange(
            measured,
            snapshotWeighing(holding, grams = 4_000),
            listOf(holding, dieting),
            zone,
        )

        val cap = AdaptiveExpenditure.MAX_ADAPTATION_SHIFT
        assertThat(tiny.kcalPerDay).isAtMost(measured.kcalPerDay * (1.0 + cap) + 0.01)
    }
}
