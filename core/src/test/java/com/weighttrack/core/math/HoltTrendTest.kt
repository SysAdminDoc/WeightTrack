package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * What the second smoothing mode is for.
 *
 * An average of the recent past sits behind a steady climb or fall for as long as it lasts. That
 * is not a flaw in the average, it is what an average is, and it is also the case somebody
 * watching a diet is in every single day. Holt carries a slope alongside the level and catches up.
 */
class HoltTrendTest {

    private val start = LocalDate.of(2026, 1, 1)

    /** A steady loss, weighed every morning, with no noise to argue about. */
    private fun steadyLoss(days: Int, gramsPerDay: Double, from: Int = 90_000) =
        (0 until days).map { day ->
            DailyWeight(start.plusDays(day.toLong()), (from - gramsPerDay * day).toInt())
        }

    @Test
    fun `on a steady half kilo a week Holt keeps up and the average does not`() {
        // 0.5 kg per week is the rate every plan in the world is written around.
        val gramsPerDay = 500.0 / 7
        val daily = steadyLoss(days = 31, gramsPerDay = gramsPerDay)
        val truth = daily.last().grams.toDouble()

        val holt = TrendEngine.computeSeries(daily, mode = SmoothingMode.HOLT)
        val ema = TrendEngine.computeSeries(daily, mode = SmoothingMode.EMA)

        val holtLag = abs(holt.latestTrendGrams!! - truth)
        val emaLag = abs(ema.latestTrendGrams!! - truth)

        assertThat(holtLag).isLessThan(100.0)
        assertThat(emaLag).isGreaterThan(100.0)
        // Not merely better: a different order of thing. The average is still most of a week
        // behind after a month, which is where the projected goal date came from.
        assertThat(emaLag).isGreaterThan(holtLag * 4)
    }

    @Test
    fun `the average is left exactly as it was`() {
        // Every install has been reading this line for its whole history. The new mode may not
        // move it by a gram, and the default may not change.
        val daily = steadyLoss(days = 40, gramsPerDay = 40.0).filterIndexed { i, _ -> i % 3 != 1 }

        val explicit = TrendEngine.computeSeries(daily, mode = SmoothingMode.EMA)
        val default = TrendEngine.computeSeries(daily)

        assertThat(default.mode).isEqualTo(SmoothingMode.EMA)
        assertThat(default.points.map { it.trendGrams })
            .isEqualTo(explicit.points.map { it.trendGrams })
    }

    @Test
    fun `a gap is carried along the slope rather than held flat`() {
        // Two weeks of daily readings, then a fortnight away, then one more.
        val fortnight = steadyLoss(days = 14, gramsPerDay = 60.0)
        val daily = fortnight + DailyWeight(start.plusDays(27), (90_000 - 60.0 * 27).toInt())

        val holt = TrendEngine.computeSeries(daily, mode = SmoothingMode.HOLT)
        val ema = TrendEngine.computeSeries(daily, mode = SmoothingMode.EMA)

        val duringGap = holt.trendOn(start.plusDays(20))!!
        val beforeGap = holt.trendOn(start.plusDays(13))!!
        assertThat(duringGap).isLessThan(beforeGap)
        // The average has nothing to carry, so its line is a flat shelf across the whole gap.
        assertThat(ema.trendOn(start.plusDays(20))).isEqualTo(ema.trendOn(start.plusDays(13)))
    }

    @Test
    fun `one reading projects nothing`() {
        val holt = TrendEngine.computeSeries(
            listOf(DailyWeight(start, 82_000)),
            mode = SmoothingMode.HOLT,
        )

        assertThat(holt.points).hasSize(1)
        assertThat(holt.latestTrendGrams).isEqualTo(82_000.0)
    }

    @Test
    fun `a plateau does not drift`() {
        // Somebody who was losing and then stops. The slope has to give up, or the line walks
        // off the bottom of the chart and the goal date stays a fiction.
        val losing = steadyLoss(days = 30, gramsPerDay = 70.0)
        val settled = losing.last().grams
        val flat = (1..60).map { DailyWeight(start.plusDays(29L + it), settled) }

        val holt = TrendEngine.computeSeries(losing + flat, mode = SmoothingMode.HOLT)

        assertThat(abs(holt.latestTrendGrams!! - settled)).isLessThan(100.0)
    }

    @Test
    fun `the rate read off the Holt line matches the loss it was built from`() {
        // Milestones, the weekly figure and the projected date all come off this, so the mode
        // has to change the answer they give, not just the line on the chart.
        // Three months. The slope is smoothed too, so it approaches the truth rather than
        // arriving at it, and a month in it is still a few grams a day short.
        val gramsPerDay = 80.0
        val holt = TrendEngine.computeSeries(
            steadyLoss(days = 90, gramsPerDay = gramsPerDay),
            mode = SmoothingMode.HOLT,
        )

        val rate = TrendEngine.rate(holt)

        assertThat(abs(rate.gramsPerDay + gramsPerDay)).isLessThan(4.0)
    }

    @Test
    fun `noise does not throw the slope around`() {
        // Water weight is the whole reason this app smooths anything. A day three kilos up must
        // not turn into a projection.
        val gramsPerDay = 50.0
        val clean = steadyLoss(days = 60, gramsPerDay = gramsPerDay)
        val noisy = clean.mapIndexed { day, reading ->
            if (day % 7 == 3) reading.copy(grams = reading.grams + 3_000) else reading
        }

        val steady = TrendEngine.rate(TrendEngine.computeSeries(clean, mode = SmoothingMode.HOLT))
        val jumpy = TrendEngine.rate(TrendEngine.computeSeries(noisy, mode = SmoothingMode.HOLT))

        assertThat(abs(jumpy.gramsPerDay - steady.gramsPerDay)).isLessThan(15.0)
    }
}
