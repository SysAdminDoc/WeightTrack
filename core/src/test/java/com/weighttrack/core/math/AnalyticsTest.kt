package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class AnalyticsTest {

    // 2026-01-01 is a Thursday.
    private val day0: LocalDate = LocalDate.of(2026, 1, 1)

    private fun series(values: List<Double>, actuals: Map<Int, Int> = emptyMap()): TrendSeries =
        TrendSeries(
            values.mapIndexed { index, trend ->
                TrendPoint(day0.plusDays(index.toLong()), trend, actuals[index])
            },
            0.1,
        )

    @Test
    fun `weekly changes need at least eight days`() {
        assertThat(Analytics.weeklyChanges(series(List(7) { 90_000.0 }))).isEmpty()
        assertThat(Analytics.weeklyChanges(series(List(8) { 90_000.0 }))).hasSize(1)
    }

    @Test
    fun `each bar covers seven days of the trend`() {
        val values = (0..20).map { 100_000.0 - 100.0 * it }
        val changes = Analytics.weeklyChanges(series(values))
        assertThat(changes).hasSize(2)
        changes.forEach {
            assertThat(it.changeGrams).isWithin(1e-6).of(-700.0)
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(it.weekStart, it.weekEnd))
                .isEqualTo(7)
        }
    }

    @Test
    fun `weekly changes read oldest first and end on the latest day`() {
        val values = (0..20).map { 100_000.0 - 100.0 * it }
        val changes = Analytics.weeklyChanges(series(values))
        assertThat(changes.first().weekStart).isLessThan(changes.last().weekStart)
        assertThat(changes.last().weekEnd).isEqualTo(day0.plusDays(20))
    }

    @Test
    fun `weekly changes are capped at the requested count`() {
        val values = (0..200).map { 100_000.0 - 10.0 * it }
        assertThat(Analytics.weeklyChanges(values.let { series(it) }, weeks = 4)).hasSize(4)
    }

    @Test
    fun `a weekday that reads heavy shows a positive deviation`() {
        // Flat trend, with every Saturday reading a kilogram above the line.
        val values = List(28) { 90_000.0 }
        val actuals = HashMap<Int, Int>()
        for (index in 0 until 28) {
            val date = day0.plusDays(index.toLong())
            actuals[index] = if (date.dayOfWeek == DayOfWeek.SATURDAY) 91_000 else 90_000
        }
        val effects = Analytics.weekdayEffects(series(values, actuals))
        val saturday = effects.first { it.day == DayOfWeek.SATURDAY }
        val others = effects.filter { it.day != DayOfWeek.SATURDAY }

        // The gap between Saturday and the rest is the whole kilogram, which is the number the
        // card talks about. The seven are centred on their own average, so Saturday sits six
        // sevenths of it above and the other six a seventh below: the same picture, without
        // claiming an absolute distance from a line that lags on purpose.
        assertThat(saturday.averageDeviationGrams).isGreaterThan(0.0)
        others.forEach {
            assertThat(saturday.averageDeviationGrams - it.averageDeviationGrams)
                .isWithin(1e-6).of(1_000.0)
        }
        // And the seven still average out to nothing, which is what centred means.
        assertThat(effects.sumOf { it.averageDeviationGrams } / effects.size)
            .isWithin(1e-6).of(0.0)
    }

    @Test
    fun `a weekday with too few readings is left out rather than guessed at`() {
        val values = List(8) { 90_000.0 }
        val effects = Analytics.weekdayEffects(series(values, mapOf(0 to 91_000)))
        assertThat(effects).isEmpty()
    }

    @Test
    fun `consistency counts the days that carried a reading`() {
        val values = List(10) { 90_000.0 }
        val actuals = mapOf(0 to 90_000, 3 to 90_000, 9 to 90_000)
        val (logged, total) = Analytics.loggingConsistency(series(values, actuals), days = 10)
        assertThat(logged).isEqualTo(3)
        assertThat(total).isEqualTo(10)
    }

    @Test
    fun `a streak counts back from the most recent day`() {
        val values = List(10) { 90_000.0 }
        val actuals = mapOf(0 to 90_000, 7 to 90_000, 8 to 90_000, 9 to 90_000)
        assertThat(Analytics.currentStreak(series(values, actuals))).isEqualTo(3)
    }

    @Test
    fun `a gap today means no streak`() {
        val values = List(5) { 90_000.0 }
        assertThat(Analytics.currentStreak(series(values, mapOf(0 to 90_000)))).isEqualTo(0)
    }
}
