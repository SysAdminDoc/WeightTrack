package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class AdaptiveExpenditureTest {

    private val today = LocalDate.of(2026, 8, 29)
    private val start = today.minusDays(13)

    /** A fortnight of daily weights falling at a steady rate, smoothed the way the app does. */
    private fun series(startGrams: Int, gramsPerDay: Double, days: Int = 14): TrendSeries {
        val daily = (0 until days).map { day ->
            DailyWeight(start.plusDays(day.toLong()), (startGrams + gramsPerDay * day).toInt())
        }
        return TrendEngine.computeSeries(daily, TrendEngine.DEFAULT_WINDOW_DAYS)
    }

    private fun intake(kcal: Double, days: Int = 14): Map<LocalDate, Double> =
        (0 until days).associate { start.plusDays(it.toLong()) to kcal }

    @Test
    fun `eating below what you burn shows up as the difference`() {
        // Half a kilogram a week is about 71 grams a day, which is roughly 550 kcal a day of
        // deficit. Eating 2000 puts what they burn at about 2550.
        val estimate = AdaptiveExpenditure.estimate(
            series = series(startGrams = 85_000, gramsPerDay = -71.4),
            intakeByDate = intake(2_000.0),
            today = today,
        )!!

        assertThat(estimate.rounded).isWithin(120).of(2_550)
        assertThat(estimate.loggedDays).isEqualTo(14)
        assertThat(estimate.meanIntakeKcal).isWithin(1e-9).of(2_000.0)
        assertThat(estimate.trendChangeKg).isLessThan(0.0)
    }

    @Test
    fun `a steady weight means they are eating exactly what they burn`() {
        val estimate = AdaptiveExpenditure.estimate(
            series = series(startGrams = 80_000, gramsPerDay = 0.0),
            intakeByDate = intake(2_200.0),
            today = today,
        )!!

        assertThat(estimate.rounded).isEqualTo(2_200)
    }

    @Test
    fun `gaining means they are eating more than they burn`() {
        val estimate = AdaptiveExpenditure.estimate(
            series = series(startGrams = 70_000, gramsPerDay = 71.4),
            intakeByDate = intake(3_000.0),
            today = today,
        )!!

        assertThat(estimate.rounded).isLessThan(3_000)
        assertThat(estimate.trendChangeKg).isGreaterThan(0.0)
    }

    @Test
    fun `a few days of food is not a fortnight of evidence`() {
        // Refusing is the honest answer, and it is the whole difference between this and an app
        // that shows a confident number made of nothing.
        val estimate = AdaptiveExpenditure.estimate(
            series = series(startGrams = 85_000, gramsPerDay = -71.4),
            intakeByDate = intake(2_000.0, days = 5),
            today = today,
        )

        assertThat(estimate).isNull()
    }

    @Test
    fun `days with nothing logged do not count as days of eating nothing`() {
        val patchy = intake(2_000.0).toMutableMap()
        // Six days where the person simply did not log. Counting them as zero calories would
        // put the estimate a thousand calories low and tell somebody to eat far too little.
        listOf(0, 1, 2, 3, 4, 5).forEach { patchy[start.plusDays(it.toLong())] = 0.0 }

        val estimate = AdaptiveExpenditure.estimate(
            series = series(startGrams = 85_000, gramsPerDay = -71.4),
            intakeByDate = patchy,
            today = today,
        )

        // Eight logged days is under the floor, so there is nothing honest to say.
        assertThat(estimate).isNull()
    }

    @Test
    fun `no weight history at the start of the window is nothing to measure against`() {
        val estimate = AdaptiveExpenditure.estimate(
            // Weights only for the last few days of the window.
            series = series(startGrams = 85_000, gramsPerDay = -71.4, days = 4),
            intakeByDate = intake(2_000.0),
            today = today,
        )

        assertThat(estimate).isNull()
    }

    @Test
    fun `a target for a chosen rate is the estimate plus the rate`() {
        val estimate = AdaptiveExpenditure.estimate(
            series = series(startGrams = 80_000, gramsPerDay = 0.0),
            intakeByDate = intake(2_500.0),
            today = today,
        )!!

        assertThat(AdaptiveExpenditure.recommendedIntake(estimate, kgPerWeek = 0.0).rounded)
            .isEqualTo(2_500)
        // Half a kilogram a week off is 550 a day off.
        assertThat(AdaptiveExpenditure.recommendedIntake(estimate, kgPerWeek = -0.5).rounded)
            .isWithin(2).of(1_950)
        assertThat(AdaptiveExpenditure.recommendedIntake(estimate, kgPerWeek = 0.25).rounded)
            .isWithin(2).of(2_775)
    }

    @Test
    fun `a goal too aggressive to be safe is capped and says so`() {
        val estimate = AdaptiveExpenditure.estimate(
            series = series(startGrams = 60_000, gramsPerDay = 0.0),
            intakeByDate = intake(1_800.0),
            today = today,
        )!!

        // Below the floor this stops being a diet and becomes a medical decision, which an app
        // has no business making for somebody.
        val recommendation = AdaptiveExpenditure.recommendedIntake(estimate, kgPerWeek = -1.5)

        assertThat(recommendation.cappedAtMinimum).isTrue()
        assertThat(recommendation.rounded).isEqualTo(1_200)
    }

    @Test
    fun `a goal turns into a rate a week`() {
        // Ten kilograms off over twenty weeks is half a kilogram a week.
        assertThat(AdaptiveExpenditure.rateForGoal(90_000, 80_000, weeks = 20.0))
            .isWithin(1e-9).of(-0.5)
        assertThat(AdaptiveExpenditure.rateForGoal(null, 80_000, weeks = 20.0)).isEqualTo(0.0)
        assertThat(AdaptiveExpenditure.rateForGoal(90_000, 80_000, weeks = 0.0)).isEqualTo(0.0)
    }

    @Test
    fun `an estimate says how much of the window it is actually made of`() {
        val patchy = intake(2_000.0).toMutableMap()
        patchy.remove(start)
        patchy.remove(start.plusDays(1))

        val estimate = AdaptiveExpenditure.estimate(
            series = series(startGrams = 85_000, gramsPerDay = -71.4),
            intakeByDate = patchy,
            today = today,
        )!!

        assertThat(estimate.loggedDays).isEqualTo(12)
        assertThat(estimate.coverage).isWithin(1e-9).of(12.0 / 14.0)
        assertThat(AdaptiveExpenditure.isConfident(estimate)).isTrue()
    }

    @Test
    fun `a window shorter than the floor is refused whatever is in it`() {
        assertThat(
            AdaptiveExpenditure.estimate(
                series = series(startGrams = 85_000, gramsPerDay = -71.4),
                intakeByDate = intake(2_000.0),
                today = today,
                windowDays = 7,
            ),
        ).isNull()
    }
}
