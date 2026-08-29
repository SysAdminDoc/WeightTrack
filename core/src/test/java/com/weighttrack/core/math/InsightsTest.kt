package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class InsightsTest {

    private val start = LocalDate.of(2026, 6, 1) // A Monday.

    /** A run of daily weigh-ins, with [bump] added on the given day of the week. */
    private fun series(
        days: Int,
        startGrams: Int = 85_000,
        gramsPerDay: Double = 0.0,
        heavyOn: DayOfWeek? = null,
        bump: Int = 0,
    ): TrendSeries {
        val daily = (0 until days).map { day ->
            val date = start.plusDays(day.toLong())
            val extra = if (date.dayOfWeek == heavyOn) bump else 0
            DailyWeight(date, (startGrams + gramsPerDay * day).toInt() + extra)
        }
        return TrendEngine.computeSeries(daily, TrendEngine.DEFAULT_WINDOW_DAYS)
    }

    // ---- steps and sleep against weight ----

    private fun daily(days: Int, value: (Int) -> Double): Map<LocalDate, Double> =
        (0 until days).associate { start.plusDays(it.toLong()) to value(it) }

    @Test
    fun `more walking alongside more weight coming off reads as an inverse association`() {
        // Built so the two really do move together: the weeks with more steps are the weeks the
        // line falls fastest.
        val weeklySteps = listOf(4_000.0, 12_000.0, 5_000.0, 13_000.0, 4_500.0, 14_000.0, 5_500.0, 15_000.0)
        val daily = mutableListOf<DailyWeight>()
        var grams = 90_000.0
        weeklySteps.forEachIndexed { week, steps ->
            val perDay = -(steps / 1_000.0) * 20
            repeat(7) { day ->
                grams += perDay
                daily += DailyWeight(start.plusDays((week * 7 + day).toLong()), grams.toInt())
            }
        }
        val stepsByDate = (0 until weeklySteps.size * 7).associate {
            start.plusDays(it.toLong()) to weeklySteps[it / 7]
        }

        val association = Insights.weeklyAssociation(
            TrendEngine.computeSeries(daily, TrendEngine.DEFAULT_WINDOW_DAYS),
            stepsByDate,
        )!!

        assertThat(association.isNotable).isTrue()
        assertThat(association.isInverse).isTrue()
        assertThat(association.weeks).isAtLeast(6)
    }

    @Test
    fun `numbers that have nothing to do with each other are not reported`() {
        val series = series(days = 70)
        // Steps that wander with no relation to a weight that never moves.
        val steps = daily(70) { (5_000 + (it * 37) % 4_000).toDouble() }

        val association = Insights.weeklyAssociation(series, steps)

        // Either nothing to correlate, or a coefficient small enough not to be worth a card.
        assertThat(association?.isNotable ?: false).isFalse()
    }

    @Test
    fun `five weeks is not enough to compare two numbers`() {
        val series = series(days = 35, gramsPerDay = -50.0)
        val steps = daily(35) { (8_000 + it * 10).toDouble() }

        assertThat(Insights.weeklyAssociation(series, steps)).isNull()
    }

    /**
     * Daily weights whose weekly rate changes from week to week.
     *
     * The rate has to vary or the weekly change never varies either, and then the correlation is
     * refused for having nothing to correlate rather than for the reason under test. That is how
     * a guard gets a test that passes whether or not the guard is there.
     */
    private fun varyingSeries(weeks: Int, ratePerWeek: (Int) -> Double): List<DailyWeight> {
        var grams = 90_000.0
        val daily = mutableListOf<DailyWeight>()
        repeat(weeks) { week ->
            val perDay = ratePerWeek(week) / 7.0
            repeat(7) { day ->
                grams += perDay
                daily += DailyWeight(start.plusDays((week * 7 + day).toLong()), grams.toInt())
            }
        }
        return daily
    }

    @Test
    fun `a week with only a couple of days of steps in it is not counted`() {
        // A phone left in a drawer five days out of seven. Two step counts is not a week's worth
        // of walking, and averaging them as if it were would put a number on a week nobody
        // measured.
        val daily = varyingSeries(12) { week -> -200.0 - (week % 3) * 300.0 }
        val series = TrendEngine.computeSeries(daily, TrendEngine.DEFAULT_WINDOW_DAYS)
        val patchy = (0 until 84)
            .filter { it % 7 < 2 }
            .associate { start.plusDays(it.toLong()) to (4_000 + (it / 7) * 1_500).toDouble() }

        assertThat(Insights.weeklyAssociation(series, patchy)).isNull()
    }

    @Test
    fun `the same weeks with a full set of step counts are counted`() {
        // The contrast that proves the refusal above is about the gaps and not about the data.
        val daily = varyingSeries(12) { week -> -200.0 - (week % 3) * 300.0 }
        val series = TrendEngine.computeSeries(daily, TrendEngine.DEFAULT_WINDOW_DAYS)
        val full = (0 until 84)
            .associate { start.plusDays(it.toLong()) to (4_000 + (it / 7) * 1_500).toDouble() }

        assertThat(Insights.weeklyAssociation(series, full)).isNotNull()
    }

    @Test
    fun `a week weighed only once is not counted either`() {
        val daily = varyingSeries(12) { week -> -200.0 - (week % 3) * 300.0 }
            .filter { it.date.toEpochDay() % 7 == 0L }
        val series = TrendEngine.computeSeries(daily, TrendEngine.DEFAULT_WINDOW_DAYS)
        val steps = daily(84) { (4_000 + (it / 7) * 1_500).toDouble() }

        // One morning is not a week's average weight. It is one morning, and mostly water.
        assertThat(Insights.weeklyAssociation(series, steps)).isNull()
    }

    @Test
    fun `a step counter stuck on one number is not a discovery`() {
        val series = series(days = 70, gramsPerDay = -50.0)
        val stuck = daily(70) { 10_000.0 }

        // No variation to correlate against. That is a broken counter, not a coefficient of zero.
        assertThat(Insights.weeklyAssociation(series, stuck)).isNull()
    }

    @Test
    fun `the coefficient stays inside the range it is defined on`() {
        val perfect = (1..10).map { it.toDouble() to it.toDouble() * 3 }
        val opposite = (1..10).map { it.toDouble() to -it.toDouble() * 3 }

        assertThat(Insights.correlation(perfect)!!).isWithin(1e-9).of(1.0)
        assertThat(Insights.correlation(opposite)!!).isWithin(1e-9).of(-1.0)
    }

    @Test
    fun `two points is not a correlation`() {
        // Any two points sit on a perfect line. Reporting that would be reporting arithmetic.
        assertThat(Insights.correlation(listOf(1.0 to 2.0, 3.0 to 9.0))).isNull()
    }

    @Test
    fun `a column that never varies has nothing to correlate`() {
        assertThat(Insights.correlation(listOf(1.0 to 5.0, 2.0 to 5.0, 3.0 to 5.0))).isNull()
        assertThat(Insights.correlation(listOf(5.0 to 1.0, 5.0 to 2.0, 5.0 to 3.0))).isNull()
    }

    @Test
    fun `days are named the way people write them`() {
        assertThat(Insights.name(DayOfWeek.SATURDAY)).isEqualTo("Saturday")
    }
}
