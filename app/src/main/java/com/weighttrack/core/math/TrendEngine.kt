package com.weighttrack.core.math

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** One calendar day's measured weight, already averaged if the day held several readings. */
data class DailyWeight(
    val date: LocalDate,
    val grams: Int,
)

/**
 * One day of the smoothed series. [actualGrams] is null on days with no reading; the trend
 * still carries forward so charts and rate maths see an evenly spaced daily series.
 */
data class TrendPoint(
    val date: LocalDate,
    val trendGrams: Double,
    val actualGrams: Int?,
)

data class TrendSeries(
    val points: List<TrendPoint>,
    val alpha: Double,
) {
    val isEmpty: Boolean get() = points.isEmpty()
    val latest: TrendPoint? get() = points.lastOrNull()
    val latestTrendGrams: Double? get() = latest?.trendGrams

    /** The most recent day that carried a real reading, ignoring carried-forward days. */
    val lastMeasured: TrendPoint? get() = points.lastOrNull { it.actualGrams != null }

    /** How far today's reading sits above or below the smoothed line. */
    val latestDeviationGrams: Double?
        get() = lastMeasured?.let { it.actualGrams!! - it.trendGrams }
}

/**
 * Slope of the smoothed line, fitted over a recent window.
 *
 * [standardErrorGramsPerDay] is the standard error of the regression slope. It is what turns a
 * single projected date into an honest range instead of a number with false precision.
 */
data class TrendRate(
    val gramsPerDay: Double,
    val standardErrorGramsPerDay: Double,
    val sampleDays: Int,
) {
    val gramsPerWeek: Double get() = gramsPerDay * 7
    val kgPerWeek: Double get() = gramsPerWeek / UnitConverter.GRAMS_PER_KG

    /**
     * Daily energy balance implied by the rate of change, using the conventional
     * 7700 kcal per kilogram of body mass.
     */
    val impliedKcalPerDay: Double
        get() = gramsPerDay / UnitConverter.GRAMS_PER_KG * TrendEngine.KCAL_PER_KG

    /** 95% confidence bounds on the slope. */
    val fastestGramsPerDay: Double
        get() = gramsPerDay - 1.96 * standardErrorGramsPerDay

    val slowestGramsPerDay: Double
        get() = gramsPerDay + 1.96 * standardErrorGramsPerDay

    val hasEnoughData: Boolean get() = sampleDays >= TrendEngine.MIN_RATE_SAMPLE_DAYS
}

/**
 * Hacker's Diet exponentially weighted moving average, made gap-aware.
 *
 * The classic formula is `trend += alpha * (weight - trend)` applied once per daily weigh-in.
 * Applying it per *entry* misbehaves after a break: a reading taken three weeks later moves the
 * line by the same small step as one taken tomorrow, so the trend lags badly and the projected
 * goal date is nonsense. Here the smoothing factor is compounded across the elapsed days, so a
 * reading after a long gap pulls the stale trend most of the way to reality, while consecutive
 * daily readings reduce exactly to the original formula.
 */
object TrendEngine {

    const val DEFAULT_WINDOW_DAYS = 10
    const val MIN_WINDOW_DAYS = 7
    const val MAX_WINDOW_DAYS = 30

    /** Kilocalories per kilogram of body mass, the usual figure for energy-balance estimates. */
    const val KCAL_PER_KG = 7700.0

    const val DEFAULT_RATE_LOOKBACK_DAYS = 14
    const val MIN_RATE_SAMPLE_DAYS = 7

    /** Weekly change below this counts as flat when deciding whether progress has stalled. */
    const val PLATEAU_GRAMS_PER_WEEK = 100.0
    const val PLATEAU_MIN_DAYS = 14

    /**
     * Hacker's Diet expresses smoothing as a time constant in days: alpha 0.1 is a ten day
     * window. Keeping that relationship makes the setting readable as "days", not as a decimal.
     */
    fun alphaForWindow(windowDays: Int): Double =
        1.0 / windowDays.coerceIn(MIN_WINDOW_DAYS, MAX_WINDOW_DAYS)

    /** Collapses several readings on one calendar day into that day's mean. */
    fun toDailyWeights(entries: List<Pair<LocalDate, Int>>): List<DailyWeight> =
        entries
            .groupBy({ it.first }, { it.second })
            .map { (date, grams) -> DailyWeight(date, grams.average().toInt()) }
            .sortedBy { it.date }

    /**
     * Builds the daily smoothed series. The result has one point per calendar day between the
     * first and last reading, so downstream charts and regressions never have to reason about
     * uneven spacing.
     */
    fun computeSeries(
        daily: List<DailyWeight>,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): TrendSeries {
        val alpha = alphaForWindow(windowDays)
        if (daily.isEmpty()) return TrendSeries(emptyList(), alpha)

        val sorted = daily.sortedBy { it.date }
        val byDate = sorted.associateBy { it.date }
        val first = sorted.first().date
        val last = sorted.last().date

        val points = ArrayList<TrendPoint>(ChronoUnit.DAYS.between(first, last).toInt() + 1)
        // Seeding the line with the first reading avoids a long artificial ramp from zero.
        var trend = sorted.first().grams.toDouble()
        var lastMeasuredDate = first

        var date = first
        while (!date.isAfter(last)) {
            val measured = byDate[date]
            if (measured != null) {
                val gapDays = ChronoUnit.DAYS.between(lastMeasuredDate, date).toInt().coerceAtLeast(0)
                if (gapDays > 0) {
                    // Compounding alpha over the gap: one day reduces to plain `alpha`, and a
                    // long absence lets the reading dominate the stale trend as it should.
                    val factor = 1.0 - (1.0 - alpha).pow(gapDays.toDouble())
                    trend += factor * (measured.grams - trend)
                }
                lastMeasuredDate = date
            }
            points += TrendPoint(date, trend, measured?.grams)
            date = date.plusDays(1)
        }
        return TrendSeries(points, alpha)
    }

    /**
     * Least-squares slope of the trend line over the most recent [lookbackDays].
     *
     * Fitting the smoothed values rather than the raw readings is what makes the slope stable
     * enough to project from; fitting raw weights produces a rate that swings with water weight.
     */
    fun rate(
        series: TrendSeries,
        lookbackDays: Int = DEFAULT_RATE_LOOKBACK_DAYS,
    ): TrendRate {
        val window = series.points.takeLast(lookbackDays.coerceAtLeast(2))
        val n = window.size
        if (n < 2) return TrendRate(0.0, 0.0, n)

        val xs = DoubleArray(n) { it.toDouble() }
        val ys = DoubleArray(n) { window[it].trendGrams }
        val meanX = xs.average()
        val meanY = ys.average()

        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            sxx += dx * dx
            sxy += dx * (ys[i] - meanY)
        }
        if (sxx == 0.0) return TrendRate(0.0, 0.0, n)

        val slope = sxy / sxx
        val intercept = meanY - slope * meanX

        val standardError = if (n > 2) {
            var residualSumSquares = 0.0
            for (i in 0 until n) {
                val predicted = intercept + slope * xs[i]
                val residual = ys[i] - predicted
                residualSumSquares += residual * residual
            }
            sqrt((residualSumSquares / (n - 2)) / sxx)
        } else {
            0.0
        }
        return TrendRate(slope, standardError, n)
    }

    /**
     * True when the smoothed line has been flat for long enough to call it a plateau rather
     * than a slow week.
     */
    fun isPlateau(series: TrendSeries, rate: TrendRate): Boolean =
        series.points.size >= PLATEAU_MIN_DAYS &&
            rate.sampleDays >= PLATEAU_MIN_DAYS &&
            abs(rate.gramsPerWeek) < PLATEAU_GRAMS_PER_WEEK
}
