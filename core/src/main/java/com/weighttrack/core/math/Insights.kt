package com.weighttrack.core.math

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The patterns in somebody's own numbers.
 *
 * All of it descriptive and none of it advice. What is on offer is "your Mondays read about six
 * hundred grams above your line", which is a fact about their scale and useful for knowing when
 * not to be alarmed. What is not on offer is any claim that one thing caused another, because
 * nothing here could establish that and a few weeks of data from one person could not either.
 *
 * Everything refuses rather than guesses. An insight card that appears in week one, made of four
 * readings, teaches somebody to ignore the insight cards.
 */
object Insights {

    /** Fewest weeks of paired numbers before the two are compared at all. */
    const val MIN_PAIRED_WEEKS = 6

    /**
     * Below this the two numbers are not moving together in any way worth mentioning.
     *
     * Deliberately high. On six or eight weekly points a coefficient of 0.4 turns up by chance
     * often enough that reporting it would mostly be reporting chance.
     */
    const val NOTABLE_CORRELATION = 0.6

    /**
     * Whether two weekly numbers moved together.
     *
     * Weekly rather than daily on purpose. A day's weight against that day's steps is mostly
     * water and yesterday's salt, and the correlation it produces is confident nonsense. A week
     * of steps against the change in that week's average weight is a much quieter number, and
     * there are far fewer of them, which is the honest position to be in.
     */
    data class Association(
        val coefficient: Double,
        val weeks: Int,
    ) {
        /** True only when the two really do move together, on enough weeks to say so. */
        val isNotable: Boolean get() = abs(coefficient) >= NOTABLE_CORRELATION

        /** Which way round it goes: more of the one alongside less of the other, or more. */
        val isInverse: Boolean get() = coefficient < 0
    }

    /**
     * Pairs each week's average of something with the change in that week's average weight.
     *
     * [valuesByDate] can have gaps. A week is used only when most of it is there, because a week
     * with two days of step counts in it is not a week's worth of walking.
     */
    fun weeklyAssociation(
        series: TrendSeries,
        valuesByDate: Map<LocalDate, Double>,
        minWeeks: Int = MIN_PAIRED_WEEKS,
        rule: WeekRule = WeekRule.MONDAY,
    ): Association? {
        if (series.points.size < (minWeeks + 1) * 7) return null
        val actualByDate = series.points
            .filter { it.actualGrams != null }
            .associate { it.date to it.actualGrams!!.toDouble() }
        val start = series.points.first().date
        val end = series.points.last().date

        // Each week's own average weight, from the readings rather than from the smoothed line.
        // The smoothing lags on purpose, which spreads one week's change into the next and blurs
        // exactly the thing being looked for.
        data class Week(val start: LocalDate, val meanGrams: Double, val value: Double)

        val weeks = mutableListOf<Week>()
        // Aligned to the same weeks the charts draw, rather than to whichever day the history
        // happens to begin on. Two people with the same steps and the same weights got
        // different answers depending on when they installed the app.
        var weekStart = rule.startOf(start)
        if (weekStart < start) weekStart = weekStart.plusDays(WeekRule.DAYS_IN_WEEK.toLong())
        while (weekStart.plusDays(6) <= end) {
            val days = (0..6).map { weekStart.plusDays(it.toLong()) }
            val values = days.mapNotNull { valuesByDate[it] }
            val weights = days.mapNotNull { actualByDate[it] }
            // Most of the week for both, not a couple of days of either.
            if (values.size >= DAYS_NEEDED_IN_WEEK && weights.size >= WEIGH_INS_NEEDED_IN_WEEK) {
                weeks += Week(weekStart, weights.average(), values.average())
            }
            weekStart = weekStart.plusDays(7)
        }

        // A week's change only exists next to the week before it, so consecutive weeks are the
        // only ones that can be paired. A gap in the middle costs one pair rather than the lot.
        val pairs = weeks.zipWithNext()
            .filter { (before, after) -> before.start.plusDays(7) == after.start }
            .map { (before, after) -> after.value to (after.meanGrams - before.meanGrams) }

        if (pairs.size < minWeeks) return null
        val coefficient = correlation(pairs) ?: return null
        return Association(coefficient = coefficient, weeks = pairs.size)
    }

    /**
     * Pearson's correlation coefficient.
     *
     * Returns null when either column never varies, which is not a correlation of zero but the
     * absence of anything to correlate. Somebody whose step counter reported the same number
     * every day has a broken step counter, not a discovery.
     */
    fun correlation(pairs: List<Pair<Double, Double>>): Double? {
        if (pairs.size < 3) return null
        val meanX = pairs.sumOf { it.first } / pairs.size
        val meanY = pairs.sumOf { it.second } / pairs.size
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for ((x, y) in pairs) {
            val dx = x - meanX
            val dy = y - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        if (varianceX <= 0.0 || varianceY <= 0.0) return null
        val coefficient = covariance / sqrt(varianceX * varianceY)
        // Rounding in the arithmetic can put it a hair outside the range it is defined on.
        return coefficient.coerceIn(-1.0, 1.0)
    }

    /** "Saturday", not "SATURDAY". */
    fun name(day: DayOfWeek): String = day.name.lowercase().replaceFirstChar { it.uppercase() }

    /** Grams as a rounded, signed number of grams for a sentence. */
    fun roundedGrams(value: Double): Int = value.roundToInt()

    /** A week counts when this much of it has a number. */
    private const val DAYS_NEEDED_IN_WEEK = 5

    /** And when it was weighed on enough mornings for its average to mean anything. */
    private const val WEIGH_INS_NEEDED_IN_WEEK = 4
}
