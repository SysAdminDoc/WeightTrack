package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.math.pow

class TrendEngineTest {

    private val day0: LocalDate = LocalDate.of(2026, 1, 1)

    private fun daily(vararg pairs: Pair<Int, Int>): List<DailyWeight> =
        pairs.map { (dayOffset, grams) -> DailyWeight(day0.plusDays(dayOffset.toLong()), grams) }

    @Test
    fun `empty input produces an empty series`() {
        val series = TrendEngine.computeSeries(emptyList())
        assertThat(series.isEmpty).isTrue()
        assertThat(series.latestTrendGrams).isNull()
    }

    @Test
    fun `alpha follows the hacker's diet time constant`() {
        assertThat(TrendEngine.alphaForWindow(10)).isWithin(1e-12).of(0.1)
        assertThat(TrendEngine.alphaForWindow(20)).isWithin(1e-12).of(0.05)
    }

    @Test
    fun `window setting is clamped to the supported range`() {
        assertThat(TrendEngine.alphaForWindow(1)).isWithin(1e-12).of(1.0 / TrendEngine.MIN_WINDOW_DAYS)
        assertThat(TrendEngine.alphaForWindow(365)).isWithin(1e-12).of(1.0 / TrendEngine.MAX_WINDOW_DAYS)
    }

    @Test
    fun `a single reading seeds the trend at that reading`() {
        val series = TrendEngine.computeSeries(daily(0 to 80_000))
        assertThat(series.points).hasSize(1)
        assertThat(series.latestTrendGrams).isWithin(1e-9).of(80_000.0)
    }

    @Test
    fun `consecutive days reduce to the classic formula`() {
        val series = TrendEngine.computeSeries(daily(0 to 100_000, 1 to 99_000), windowDays = 10)
        // trend = 100000 + 0.1 * (99000 - 100000)
        assertThat(series.latestTrendGrams).isWithin(1e-6).of(99_900.0)
    }

    @Test
    fun `a gap compounds the smoothing factor over the elapsed days`() {
        val series = TrendEngine.computeSeries(daily(0 to 100_000, 10 to 90_000), windowDays = 10)
        val factor = 1.0 - 0.9.pow(10.0)
        val expected = 100_000 + factor * (90_000 - 100_000)
        assertThat(series.latestTrendGrams).isWithin(1e-6).of(expected)
    }

    @Test
    fun `a reading after a long gap moves the trend much further than a daily reading would`() {
        val afterGap = TrendEngine.computeSeries(daily(0 to 100_000, 30 to 90_000), windowDays = 10)
        val nextDay = TrendEngine.computeSeries(daily(0 to 100_000, 1 to 90_000), windowDays = 10)
        // This is the whole point of the gap-aware form: a stale trend must not survive a month
        // away from the scale.
        assertThat(afterGap.latestTrendGrams!!).isLessThan(nextDay.latestTrendGrams!!)
        assertThat(afterGap.latestTrendGrams!!).isLessThan(91_000.0)
    }

    @Test
    fun `series holds one point per calendar day including days with no reading`() {
        val series = TrendEngine.computeSeries(daily(0 to 100_000, 4 to 99_000))
        assertThat(series.points).hasSize(5)
        assertThat(series.points.map { it.date })
            .containsExactlyElementsIn((0..4).map { day0.plusDays(it.toLong()) })
            .inOrder()
        assertThat(series.points.count { it.actualGrams != null }).isEqualTo(2)
    }

    @Test
    fun `trend carries forward unchanged on days with no reading`() {
        val series = TrendEngine.computeSeries(daily(0 to 100_000, 4 to 99_000))
        val firstFour = series.points.take(4).map { it.trendGrams }
        assertThat(firstFour.distinct()).hasSize(1)
        assertThat(firstFour.first()).isWithin(1e-9).of(100_000.0)
    }

    @Test
    fun `several readings on one day are averaged`() {
        val entries = listOf(
            day0 to 80_000,
            day0 to 81_000,
            day0.plusDays(1) to 79_000,
        )
        val aggregated = TrendEngine.toDailyWeights(entries)
        assertThat(aggregated).hasSize(2)
        assertThat(aggregated.first().grams).isEqualTo(80_500)
    }

    @Test
    fun `daily aggregation sorts by date`() {
        val entries = listOf(
            day0.plusDays(5) to 79_000,
            day0 to 80_000,
        )
        assertThat(TrendEngine.toDailyWeights(entries).map { it.date })
            .containsExactly(day0, day0.plusDays(5))
            .inOrder()
    }

    @Test
    fun `last measured ignores carried forward days`() {
        val series = TrendEngine.computeSeries(daily(0 to 100_000, 2 to 99_000, 9 to 98_000))
        assertThat(series.lastMeasured!!.date).isEqualTo(day0.plusDays(9))
        assertThat(series.points.last().date).isEqualTo(day0.plusDays(9))
    }

    @Test
    fun `deviation reports how far the last reading sat from the line`() {
        val series = TrendEngine.computeSeries(daily(0 to 100_000, 1 to 99_000), windowDays = 10)
        // Reading 99000 against a trend of 99900 leaves the scale 900 g below the line.
        assertThat(series.latestDeviationGrams).isWithin(1e-6).of(-900.0)
    }

    @Test
    fun `regression recovers the slope of a straight line`() {
        val points = (0..13).map {
            TrendPoint(day0.plusDays(it.toLong()), 100_000.0 - 100.0 * it, null)
        }
        val rate = TrendEngine.rate(TrendSeries(points, 0.1))
        assertThat(rate.gramsPerDay).isWithin(1e-6).of(-100.0)
        assertThat(rate.standardErrorGramsPerDay).isWithin(1e-6).of(0.0)
        assertThat(rate.sampleDays).isEqualTo(14)
    }

    @Test
    fun `rate converts to weekly and calorie terms`() {
        val rate = TrendRate(gramsPerDay = -100.0, standardErrorGramsPerDay = 0.0, sampleDays = 14)
        assertThat(rate.gramsPerWeek).isWithin(1e-9).of(-700.0)
        assertThat(rate.kgPerWeek).isWithin(1e-9).of(-0.7)
        // 100 g/day is a tenth of a kilogram, so 770 kcal/day below maintenance.
        assertThat(rate.impliedKcalPerDay).isWithin(1e-6).of(-770.0)
    }

    @Test
    fun `noisy data widens the standard error`() {
        val clean = (0..13).map { TrendPoint(day0.plusDays(it.toLong()), 100_000.0 - 100.0 * it, null) }
        val noisy = (0..13).map {
            val wobble = if (it % 2 == 0) 400.0 else -400.0
            TrendPoint(day0.plusDays(it.toLong()), 100_000.0 - 100.0 * it + wobble, null)
        }
        val cleanError = TrendEngine.rate(TrendSeries(clean, 0.1)).standardErrorGramsPerDay
        val noisyError = TrendEngine.rate(TrendSeries(noisy, 0.1)).standardErrorGramsPerDay
        assertThat(noisyError).isGreaterThan(cleanError)
    }

    @Test
    fun `rate needs at least two points`() {
        val single = TrendSeries(listOf(TrendPoint(day0, 100_000.0, 100_000)), 0.1)
        assertThat(TrendEngine.rate(single).gramsPerDay).isEqualTo(0.0)
        assertThat(TrendEngine.rate(single).hasEnoughData).isFalse()
    }

    @Test
    fun `a flat fortnight counts as a plateau`() {
        val points = (0..20).map { TrendPoint(day0.plusDays(it.toLong()), 90_000.0, null) }
        val series = TrendSeries(points, 0.1)
        assertThat(TrendEngine.isPlateau(series, TrendEngine.rate(series))).isTrue()
    }

    @Test
    fun `steady loss is not a plateau`() {
        val points = (0..20).map { TrendPoint(day0.plusDays(it.toLong()), 90_000.0 - 80.0 * it, null) }
        val series = TrendSeries(points, 0.1)
        assertThat(TrendEngine.isPlateau(series, TrendEngine.rate(series))).isFalse()
    }

    @Test
    fun `a short flat stretch is not yet a plateau`() {
        val points = (0..9).map { TrendPoint(day0.plusDays(it.toLong()), 90_000.0, null) }
        val series = TrendSeries(points, 0.1)
        assertThat(TrendEngine.isPlateau(series, TrendEngine.rate(series))).isFalse()
    }

    @Test
    fun `trend follows a sustained loss downward`() {
        // Ten weeks of losing roughly half a kilogram a week, with daily water-weight noise.
        val readings = (0..69).map { day ->
            val underlying = 100_000.0 - 71.4 * day
            val noise = listOf(-800, 400, 1_200, -300, 0, 600, -1_100)[day % 7]
            (day to (underlying + noise).toInt())
        }
        val series = TrendEngine.computeSeries(daily(*readings.toTypedArray()))
        val rate = TrendEngine.rate(series, lookbackDays = 28)
        // The smoothed slope should land close to the real underlying rate despite the noise.
        assertThat(rate.gramsPerDay).isWithin(15.0).of(-71.4)
        assertThat(series.latestTrendGrams!!).isWithin(1_500.0).of(100_000.0 - 71.4 * 69)
    }
}
