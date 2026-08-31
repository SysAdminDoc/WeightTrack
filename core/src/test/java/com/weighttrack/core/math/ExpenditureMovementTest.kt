package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * Step counts, and what happens when the target changes.
 *
 * Steps are the one thing here that never turns into calories. Step counters disagree with each
 * other by a third, none of them knows what anybody weighs, and a number of calories taken from
 * one is a guess with a decimal point on it. What they do know is whether somebody is doing
 * roughly what they were doing last week, and that is worth knowing: a fortnight that spans a
 * change of habit is a fortnight measuring two different people.
 */
class ExpenditureMovementTest {

    private val today = LocalDate.of(2026, 4, 15)
    private val from = today.minusDays(13)

    /** Fourteen days of the same food. */
    private fun intake(kcal: Double = 2_200.0) =
        (0..13).associate { from.plusDays(it.toLong()) to kcal }

    /**
     * A weigh-in every morning, flat for a week and then falling.
     *
     * What somebody who started moving more looks like on the scale.
     */
    private fun weightsThatSteepen(): TrendSeries {
        val daily = (0..13).map { day ->
            val grams = if (day < 7) 80_000 - day * 20 else 80_000 - 6 * 20 - (day - 6) * 140
            DailyWeight(from.plusDays(day.toLong()), grams)
        }
        return TrendEngine.computeSeries(daily)
    }

    private fun steps(early: Long, late: Long) =
        (0..13).associate { day ->
            from.plusDays(day.toLong()) to if (day < 7) early else late
        }

    @Test
    fun `movement that doubles in week two moves the estimate faster`() {
        val series = weightsThatSteepen()
        val intake = intake()

        val blind = AdaptiveExpenditure.estimate(series, intake, today)!!
        val watching = AdaptiveExpenditure.estimate(
            series,
            intake,
            today,
            stepsByDate = steps(early = 4_000, late = 8_000),
        )!!

        // The same food and the same weights. All that changed is that the app can see the first
        // week was somebody else's week, so it counts the second one more.
        assertThat(watching.kcalPerDay).isGreaterThan(blind.kcalPerDay)
        assertThat(watching.movementChanged).isTrue()
        assertThat(blind.movementChanged).isFalse()
    }

    @Test
    fun `steps never enter the arithmetic`() {
        val series = weightsThatSteepen()
        val intake = intake()

        // The same shape at three wildly different scales. A pedometer, a phone in a pocket that
        // misses half of them, and a watch that counts arm waves. If any of these changed the
        // answer, steps would be in the sum.
        val small = AdaptiveExpenditure.estimate(
            series, intake, today, stepsByDate = steps(2_000, 4_000),
        )!!
        val ordinary = AdaptiveExpenditure.estimate(
            series, intake, today, stepsByDate = steps(4_000, 8_000),
        )!!
        val huge = AdaptiveExpenditure.estimate(
            series, intake, today, stepsByDate = steps(40_000, 80_000),
        )!!

        assertThat(ordinary.kcalPerDay).isEqualTo(small.kcalPerDay)
        assertThat(huge.kcalPerDay).isEqualTo(small.kcalPerDay)
    }

    @Test
    fun `a steady walker is treated exactly as somebody with no step counter`() {
        val series = weightsThatSteepen()
        val intake = intake()

        val blind = AdaptiveExpenditure.estimate(series, intake, today)!!
        val steady = AdaptiveExpenditure.estimate(
            series, intake, today, stepsByDate = steps(7_500, 7_500),
        )!!

        assertThat(steady.kcalPerDay).isEqualTo(blind.kcalPerDay)
        assertThat(steady.movementChanged).isFalse()
    }

    @Test
    fun `an ordinary week of variation is not a change of habit`() {
        val series = weightsThatSteepen()
        val intake = intake()
        // Weekends off, weekdays on. Everybody's step count looks like this.
        val ordinary = (0..13).associate { day ->
            from.plusDays(day.toLong()) to if (day % 7 >= 5) 6_600L else 7_800L
        }

        val watching = AdaptiveExpenditure.estimate(series, intake, today, stepsByDate = ordinary)!!

        assertThat(watching.movementChanged).isFalse()
        assertThat(watching.kcalPerDay)
            .isEqualTo(AdaptiveExpenditure.estimate(series, intake, today)!!.kcalPerDay)
    }

    @Test
    fun `a few days of step counts are not enough to reweigh a fortnight`() {
        val series = weightsThatSteepen()
        val intake = intake()
        // Somebody who wore the watch for three days. Nothing there says what a fortnight was.
        val patchy = (11..13).associate { day -> from.plusDays(day.toLong()) to 12_000L }

        val watching = AdaptiveExpenditure.estimate(series, intake, today, stepsByDate = patchy)!!

        assertThat(watching.movementChanged).isFalse()
        assertThat(watching.kcalPerDay)
            .isEqualTo(AdaptiveExpenditure.estimate(series, intake, today)!!.kcalPerDay)
    }

    @Test
    fun `stopping a diet gives the expenditure back before the scale can show it`() {
        val estimate = AdaptiveExpenditure.Estimate(
            kcalPerDay = 2_400.0,
            from = today.minusDays(13),
            to = today,
            days = 14,
            loggedDays = 14,
            weighIns = 14,
            meanIntakeKcal = 1_900.0,
            trendChangeKg = -1.0,
        )

        // 80 kg, losing 0.8 kg a week, which is one percent, and then deciding to hold.
        val holding = AdaptiveExpenditure.afterGoalChange(
            estimate,
            fromKgPerWeek = -0.8,
            toKgPerWeek = 0.0,
            bodyMassKg = 80.0,
        )

        // One percent a week, four times over: four percent more than the deficit was showing.
        assertThat(holding.kcalPerDay).isWithin(0.01).of(2_400.0 * 1.04)
    }

    @Test
    fun `the recommendation moves with it, immediately`() {
        val estimate = AdaptiveExpenditure.Estimate(
            kcalPerDay = 2_400.0,
            from = today.minusDays(13),
            to = today,
            days = 14,
            loggedDays = 14,
            weighIns = 14,
            meanIntakeKcal = 1_900.0,
            trendChangeKg = -1.0,
        )
        val losing = AdaptiveExpenditure.recommendedIntake(estimate, kgPerWeek = -0.8)
        val holding = AdaptiveExpenditure.recommendedIntake(
            AdaptiveExpenditure.afterGoalChange(estimate, -0.8, 0.0, bodyMassKg = 80.0),
            kgPerWeek = 0.0,
        )

        // Both halves show up: the deficit goes, and the expenditure it was suppressing returns.
        val deficit = 0.8 * AdaptiveExpenditure.KCAL_PER_KG / 7.0
        assertThat(holding.kcalPerDay - losing.kcalPerDay)
            .isWithin(0.01)
            .of(deficit + 2_400.0 * 0.04)
    }

    @Test
    fun `starting a diet takes it away by the same rule`() {
        val estimate = AdaptiveExpenditure.Estimate(
            kcalPerDay = 2_400.0,
            from = today.minusDays(13),
            to = today,
            days = 14,
            loggedDays = 14,
            weighIns = 14,
            meanIntakeKcal = 2_400.0,
            trendChangeKg = 0.0,
        )

        val dieting = AdaptiveExpenditure.afterGoalChange(estimate, 0.0, -0.8, bodyMassKg = 80.0)

        assertThat(dieting.kcalPerDay).isWithin(0.01).of(2_400.0 * 0.96)
        // Symmetric, and undone by going back.
        val back = AdaptiveExpenditure.afterGoalChange(dieting, -0.8, 0.0, bodyMassKg = 80.0)
        assertThat(abs(back.kcalPerDay - 2_400.0)).isLessThan(5.0)
    }

    @Test
    fun `no change of target changes nothing, and neither does an unknown body`() {
        val estimate = AdaptiveExpenditure.Estimate(
            kcalPerDay = 2_400.0,
            from = today.minusDays(13),
            to = today,
            days = 14,
            loggedDays = 14,
            weighIns = 14,
            meanIntakeKcal = 2_000.0,
            trendChangeKg = -0.8,
        )

        assertThat(AdaptiveExpenditure.afterGoalChange(estimate, -0.5, -0.5, 80.0))
            .isEqualTo(estimate)
        assertThat(AdaptiveExpenditure.afterGoalChange(estimate, -0.5, 0.0, 0.0))
            .isEqualTo(estimate)
    }
}
