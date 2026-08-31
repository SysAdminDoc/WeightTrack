package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * The rate the goal date is divided by.
 *
 * A goal date is the distance left divided by this, so an error here is an error in months. The
 * first version started the slope at zero and let it catch up, and catching up overshoots: at
 * thirty days the fitted rate came out twenty-six per cent fast, which on a year-long goal is a
 * date three months early. Seeding the slope from the opening readings removes the transient
 * rather than waiting it out.
 */
class HoltRateTest {

    private val start = LocalDate.of(2026, 2, 1)

    private fun steadyLoss(days: Int, gramsPerDay: Double) =
        (0 until days).map { day ->
            DailyWeight(start.plusDays(day.toLong()), (90_000 - gramsPerDay * day).toInt())
        }

    @Test
    fun `the fitted rate is honest from the first month, not only after three`() {
        val truth = 500.0 / 7

        listOf(20, 30, 45, 90).forEach { days ->
            val holt = TrendEngine.computeSeries(
                steadyLoss(days, truth),
                mode = SmoothingMode.HOLT,
            )
            val fitted = TrendEngine.rate(holt).gramsPerDay

            // Within five per cent of the loss it was built from, at every horizon.
            assertThat(abs(fitted + truth) / truth).isLessThan(0.05)
        }
    }

    @Test
    fun `it does not overshoot the way an unseeded slope does`() {
        // Specifically the thirty day case the goal date is read at.
        val truth = 500.0 / 7
        val holt = TrendEngine.computeSeries(steadyLoss(30, truth), mode = SmoothingMode.HOLT)

        val fitted = TrendEngine.rate(holt).gramsPerDay

        // Faster than the truth is the dangerous direction: it promises a date that will not
        // arrive. Whatever else, it may not read fast.
        assertThat(fitted).isAtLeast(-truth * 1.02)
    }

    @Test
    fun `seeding does not invent a slope from a single reading`() {
        val one = TrendEngine.computeSeries(
            listOf(DailyWeight(start, 82_000)),
            mode = SmoothingMode.HOLT,
        )

        assertThat(one.points.single().trendGrams).isEqualTo(82_000.0)
    }

    @Test
    fun `seeding does not read a slope off two readings on the same morning`() {
        val sameDay = TrendEngine.computeSeries(
            listOf(DailyWeight(start, 82_000), DailyWeight(start, 82_400)),
            mode = SmoothingMode.HOLT,
        )

        assertThat(sameDay.points).hasSize(1)
        assertThat(sameDay.points.single().trendGrams).isEqualTo(82_400.0)
    }

    @Test
    fun `a wild opening pair does not seed a slope that outlives it`() {
        // Two readings a day apart differing by three kilos of water. Seeded literally that is
        // 3 kg a day, and the line would leave the chart before the third reading corrects it.
        val watery = listOf(
            DailyWeight(start, 85_000),
            DailyWeight(start.plusDays(1), 82_000),
        ) + (2..40).map { DailyWeight(start.plusDays(it.toLong()), 82_000 - it * 20) }

        val holt = TrendEngine.computeSeries(watery, mode = SmoothingMode.HOLT)

        val drawn = holt.points.map { it.trendGrams }
        assertThat(drawn.min()).isGreaterThan(78_000.0)
        assertThat(drawn.max()).isLessThan(86_000.0)
    }
}
