package com.weighttrack.domain

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.DailyWeight
import com.weighttrack.core.math.SmoothingMode
import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.MeasurementRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.LocalDate
import kotlin.math.abs

/**
 * The choice has to reach the numbers, not only the chart.
 *
 * A smoothing mode wired into the line but not into the rate would leave somebody reading a
 * projected date worked out from a line they are not looking at, which is worse than not offering
 * the choice at all. Everything on the home screen comes off one series, so this checks that the
 * series it comes off is the one that was asked for.
 */
class SmoothingModeReachesProgressTest {

    private val start = LocalDate.of(2026, 3, 1)
    private val days = 60
    private val gramsPerDay = 70.0

    private val calculator = ProgressCalculator(
        mock(WeightRepository::class.java),
        mock(GoalRepository::class.java),
        mock(MeasurementRepository::class.java),
        mock(SettingsRepository::class.java),
        mock(ProfileRepository::class.java),
    )

    /** A steady loss, weighed daily, from 95 kg towards 85. */
    private val daily = (0 until days).map { day ->
        DailyWeight(start.plusDays(day.toLong()), (95_000 - gramsPerDay * day).toInt())
    }

    private val goal = Goal(
        direction = GoalDirection.LOSE,
        startGrams = 95_000,
        targetGrams = 85_000,
        startDate = start,
        milestoneStepGrams = 1_000,
        active = true,
    )

    private fun snapshotWith(mode: SmoothingMode) = calculator.build(
        daily = daily,
        latestEntry = null,
        goal = goal,
        measurements = emptyMap(),
        settings = AppSettings(smoothingMode = mode),
        today = start.plusDays(days.toLong() - 1),
    )

    @Test
    fun `the weekly rate is read off the mode that was chosen, and is honest either way`() {
        val average = snapshotWith(SmoothingMode.EMA)
        val slope = snapshotWith(SmoothingMode.HOLT)
        val truth = -gramsPerDay * 7

        // The choice reached the rate rather than stopping at the chart.
        assertThat(average.rate.gramsPerWeek).isNotEqualTo(slope.rate.gramsPerWeek)
        // Not a claim that one is better. On a perfectly steady loss the average's line is
        // parallel to the truth, so the slope fitted to it is already right; what it gets wrong
        // is where the line sits, which is what the two tests below are about. Both rates have
        // to stay honest, and the new one is still creeping towards the truth after two months.
        assertThat(abs(average.rate.gramsPerWeek - truth)).isLessThan(50.0)
        assertThat(abs(slope.rate.gramsPerWeek - truth)).isLessThan(50.0)
    }

    @Test
    fun `the current trend weight follows the mode that was chosen`() {
        val average = snapshotWith(SmoothingMode.EMA)
        val slope = snapshotWith(SmoothingMode.HOLT)
        val truth = daily.last().grams.toDouble()

        assertThat(average.series.latestTrendGrams).isNotEqualTo(slope.series.latestTrendGrams)
        // Losing, so an average reads high. The whole complaint, in one assertion.
        assertThat(average.series.latestTrendGrams!!).isGreaterThan(truth)
        assertThat(abs(slope.series.latestTrendGrams!! - truth)).isLessThan(abs(average.series.latestTrendGrams!! - truth))
    }

    @Test
    fun `the projected date follows the mode that was chosen`() {
        val average = snapshotWith(SmoothingMode.EMA).projection!!
        val slope = snapshotWith(SmoothingMode.HOLT).projection!!

        // The average thinks there is more left to lose than there is, so it says later.
        assertThat(average.etaDays).isNotNull()
        assertThat(slope.etaDays).isNotNull()
        assertThat(slope.etaDays!!).isLessThan(average.etaDays!!)
    }
}
