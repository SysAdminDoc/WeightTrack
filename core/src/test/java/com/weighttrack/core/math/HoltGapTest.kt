package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * What the slope is allowed to do while nobody is standing on the scale.
 *
 * The first version carried the line forward along the last known slope for as long as the
 * absence lasted, with nothing bounding it. A fortnight's holiday after a run of losses drew the
 * line more than a kilogram below the lowest weight ever recorded and then reported a gain for
 * the week as it snapped back, on somebody who had genuinely gained. Ninety days off drew a
 * negative body weight.
 *
 * A slope measured last month is evidence about last month. Less of it is evidence about today
 * with every day that passes, and none of it is evidence about next year.
 */
class HoltGapTest {

    private val start = LocalDate.of(2026, 6, 1)

    private fun series(daily: List<DailyWeight>) =
        TrendEngine.computeSeries(daily, mode = SmoothingMode.HOLT)

    /** Twenty mornings falling steadily, then a break, then one reading well above where it left. */
    private fun holiday(gapDays: Long): List<DailyWeight> {
        val losing = (0..19).map { DailyWeight(start.plusDays(it.toLong()), 90_000 - it * 150) }
        return losing + DailyWeight(start.plusDays(19 + gapDays), 89_000)
    }

    @Test
    fun `how far the line runs past the readings does not depend on how long the absence was`() {
        // The defect, stated as the thing somebody would notice: a fortnight off and a year off
        // used to draw the line to wildly different places, because the projection grew with the
        // absence. The last slope was 150 g a day and the line may not carry more than
        // MAX_CARRY_DAYS of it, whatever happens afterwards.
        val bound = 150.0 * TrendEngine.MAX_CARRY_DAYS
        val lows = listOf(14L, 30L, 90L, 400L).map { gap ->
            val points = series(holiday(gap)).points
            val lowestMeasured = points.mapNotNull { it.actualGrams }.min().toDouble()
            lowestMeasured - points.minOf { it.trendGrams }
        }

        // To the gram: the two ways of arriving at 6.67 days of slope differ in the last bit.
        lows.forEach { assertThat(it).isAtMost(bound + 1.0) }
        // A fortnight is not quite at the limit and a year is, which is the point: the two
        // differ by well under a tenth of a kilo rather than by kilos, and nothing past the
        // limit differs at all.
        assertThat(lows.max() - lows.min()).isLessThan(150.0)
    }

    @Test
    fun `a gain across a holiday is not reported as a loss`() {
        // 87,150 g before the break, 89,000 g after it. They gained 1.85 kg and nothing about
        // that is ambiguous.
        val gained = series(holiday(14))

        val rate = TrendEngine.rate(gained)

        assertThat(rate.gramsPerDay).isGreaterThan(0.0)
    }

    @Test
    fun `the week's change does not invent kilograms that were never lost`() {
        // Ninety days away and back at the same weight. The line has to sit still, not dive for
        // three months and then report an eight kilo week as it comes back.
        val steady = (0..13).map { DailyWeight(start.plusDays(it.toLong()), 100_000 - it * 200) }
        val away = steady + DailyWeight(start.plusDays(103), steady.last().grams)

        val change = series(away).changeOverDays(7)!!

        assertThat(abs(change)).isLessThan(2_000.0)
    }

    @Test
    fun `one absurd reading does not drag the trend below the reading beside it`() {
        // Somebody typed 8 kg instead of 80. The average shrugs it off, and so must this.
        val typo = listOf(
            DailyWeight(start, 80_000),
            DailyWeight(start.plusDays(1), 8_000),
            DailyWeight(start.plusDays(2), 80_000),
            DailyWeight(start.plusDays(23), 79_000),
        )

        val holt = series(typo)
        val ema = TrendEngine.computeSeries(typo, mode = SmoothingMode.EMA)

        // Within a kilo of the average's answer, rather than two kilos under the real reading.
        assertThat(abs(holt.latestTrendGrams!! - ema.latestTrendGrams!!)).isLessThan(1_000.0)
        assertThat(holt.latestTrendGrams!!).isGreaterThan(77_000.0)
    }

    @Test
    fun `the carried line saturates rather than growing without limit`() {
        // Three months and a year away draw the line to the same place, because a slope from a
        // year ago says nothing more about today than one from three months ago does.
        val aMonthIn = series(holiday(400)).trendOn(start.plusDays(120))!!
        val year = series(holiday(400)).trendOn(start.plusDays(380))!!

        assertThat(abs(year - aMonthIn)).isLessThan(1.0)
    }

    @Test
    fun `a duplicated date does not leave the line seeded from a different reading`() {
        // Two readings filed under one day. The first point's trend and its reading have to be
        // the same number, or the deviation shown beside it is invented.
        val duplicated = listOf(
            DailyWeight(start, 80_000),
            DailyWeight(start, 90_000),
            DailyWeight(start.plusDays(1), 90_100),
        )

        val holt = series(duplicated)

        assertThat(holt.points.first().trendGrams)
            .isEqualTo(holt.points.first().actualGrams!!.toDouble())
    }
}
