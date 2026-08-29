package com.weighttrack.ui.components

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.TrendPoint
import org.junit.Test
import java.time.LocalDate

class TrendChartBoundsTest {

    private val day0: LocalDate = LocalDate.of(2026, 1, 1)

    /** Readings spanning 83.6 kg to 87.6 kg, a four kilogram spread. */
    private fun points(): List<TrendPoint> = listOf(
        TrendPoint(day0, 87_600.0, 87_600),
        TrendPoint(day0.plusDays(1), 85_600.0, 85_600),
        TrendPoint(day0.plusDays(2), 83_600.0, 83_600),
    )

    @Test
    fun `bounds cover every reading and the trend`() {
        val bounds = valueBounds(points(), goalGrams = null)
        assertThat(bounds.start).isLessThan(83_600.0)
        assertThat(bounds.endInclusive).isGreaterThan(87_600.0)
    }

    @Test
    fun `a goal within reach is pulled into view`() {
        // 82 kg is 1.6 kg below the lowest reading, well inside the allowed growth.
        val bounds = valueBounds(points(), goalGrams = 82_000)
        assertThat(bounds.start).isLessThan(82_000.0)
    }

    @Test
    fun `a distant goal is left off rather than squashing the trend`() {
        // 78 kg would stretch a 4 kg spread to 9.6 kg and push the readings into the top
        // third of the chart, which is exactly the detail the chart exists to show.
        val bounds = valueBounds(points(), goalGrams = 78_000)
        assertThat(bounds.start).isGreaterThan(80_000.0)
        assertThat(bounds.start).isLessThan(83_600.0)
    }

    @Test
    fun `including a distant goal never grows the range past the limit`() {
        val withoutGoal = valueBounds(points(), null)
        val withGoal = valueBounds(points(), goalGrams = 60_000)
        assertThat(withGoal.endInclusive - withGoal.start)
            .isWithin(1e-6)
            .of(withoutGoal.endInclusive - withoutGoal.start)
    }

    @Test
    fun `a flat series still gets a visible band`() {
        val flat = (0..5).map { TrendPoint(day0.plusDays(it.toLong()), 80_000.0, 80_000) }
        val bounds = valueBounds(flat, null)
        assertThat(bounds.endInclusive - bounds.start).isAtLeast(600.0)
    }

    @Test
    fun `no points yields a usable range rather than an inverted one`() {
        val bounds = valueBounds(emptyList(), null)
        assertThat(bounds.start).isLessThan(bounds.endInclusive)
    }
}
