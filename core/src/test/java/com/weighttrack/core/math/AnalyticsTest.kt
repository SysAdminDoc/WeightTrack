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
    fun `a bar needs a whole week and the day the week is measured from`() {
        // The series starts on a Thursday, so the first Monday-to-Sunday week it can cover
        // whole is 2026-01-05 to 2026-01-11, and its change is measured from the Sunday before.
        // Eight days from a Thursday reaches neither.
        assertThat(Analytics.weeklyChanges(series(List(8) { 90_000.0 }), rule = WeekRule.MONDAY))
            .isEmpty()
        // Thursday 2026-01-01 plus fourteen days is Wednesday 2026-01-14, which covers
        // 2026-01-04 through 2026-01-11 and nothing else whole.
        val fortnight = Analytics.weeklyChanges(series(List(14) { 90_000.0 }), rule = WeekRule.MONDAY)
        assertThat(fortnight).hasSize(1)
        assertThat(fortnight.single().weekStart).isEqualTo(LocalDate.of(2026, 1, 5))
    }

    @Test
    fun `each bar covers seven days of the trend`() {
        val values = (0..27).map { 100_000.0 - 100.0 * it }
        val changes = Analytics.weeklyChanges(series(values), rule = WeekRule.MONDAY)
        // Thursday 2026-01-01 through Wednesday 2026-01-28: three whole Monday weeks in it.
        assertThat(changes.map { it.weekStart }).containsExactly(
            LocalDate.of(2026, 1, 5),
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 19),
        ).inOrder()
        changes.forEach {
            assertThat(it.changeGrams).isWithin(1e-6).of(-700.0)
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(it.weekStart, it.weekEnd))
                .isEqualTo(6)
        }
    }

    @Test
    fun `weekly changes read oldest first and only cover finished weeks`() {
        val values = (0..27).map { 100_000.0 - 100.0 * it }
        val changes = Analytics.weeklyChanges(series(values), rule = WeekRule.MONDAY)
        assertThat(changes.first().weekStart).isLessThan(changes.last().weekStart)
        // The series ends on 2026-01-28, a Wednesday, so the week in progress is left out and
        // the newest bar ends on the Sunday before it.
        assertThat(changes.last().weekEnd).isEqualTo(LocalDate.of(2026, 1, 25))
        changes.forEach { assertThat(it.weekStart.dayOfWeek).isEqualTo(DayOfWeek.MONDAY) }
    }

    @Test
    fun `the week a bar covers depends on where the week starts`() {
        val values = (0..27).map { 100_000.0 - 100.0 * it }
        val monday = Analytics.weeklyChanges(series(values), rule = WeekRule.MONDAY)
        val sunday = Analytics.weeklyChanges(series(values), rule = WeekRule.SUNDAY)

        assertThat(monday.map { it.weekStart }).containsExactly(
            LocalDate.of(2026, 1, 5),
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 19),
        ).inOrder()
        // Same history, same steady loss, boundaries a day apart. Counted back from the newest
        // reading, both would have produced the same arbitrary blocks.
        assertThat(sunday.map { it.weekStart }).containsExactly(
            LocalDate.of(2026, 1, 4),
            LocalDate.of(2026, 1, 11),
            LocalDate.of(2026, 1, 18),
        ).inOrder()
    }

    @Test
    fun `bars do not move as the week goes on`() {
        // The bug this replaced: every bar shifted a day each morning, so "last week" meant
        // something different every time somebody looked at it.
        val wednesday = (0..27).map { 100_000.0 - 100.0 * it }
        val thursday = (0..28).map { 100_000.0 - 100.0 * it }

        val before = Analytics.weeklyChanges(series(wednesday), rule = WeekRule.MONDAY)
        val after = Analytics.weeklyChanges(series(thursday), rule = WeekRule.MONDAY)

        assertThat(after.map { it.weekStart }).isEqualTo(before.map { it.weekStart })
    }

    @Test
    fun `weekly changes are capped at the requested count`() {
        val values = (0..200).map { 100_000.0 - 10.0 * it }
        assertThat(Analytics.weeklyChanges(values.let { series(it) }, weeks = 4)).hasSize(4)
    }

    @Test
    fun `a history that stops mid-week still shows the week that finished`() {
        // Twelve contiguous days, Monday 2025-12-29 through Friday 2026-01-09. The week
        // 2026-01-05 to 01-11 is not over, but 2025-12-29 to 01-04 is, and asking for its exact
        // last day would have dropped it along with everything else and drawn an empty card.
        val start = LocalDate.of(2025, 12, 29)
        val points = (0..11).map { offset ->
            TrendPoint(start.plusDays(offset.toLong()), 90_000.0 - 100.0 * offset, null)
        }

        val changes = Analytics.weeklyChanges(TrendSeries(points, 0.1), rule = WeekRule.MONDAY)

        assertThat(changes.map { it.weekStart }).containsExactly(start)
    }

    @Test
    fun `this week counts from the day the week began, not seven days back`() {
        // Thursday 2026-01-01 through Wednesday 2026-01-14, losing a hundred grams a day.
        val values = (0..13).map { 90_000.0 - 100.0 * it }
        val series = series(values)

        // Under a Monday rule the week began on the twelfth, measured from the Sunday before it:
        // Monday, Tuesday and Wednesday, so three days of loss.
        assertThat(
            Analytics.changeSinceWeekStart(series, WeekRule.MONDAY, LocalDate.of(2026, 1, 14)),
        ).isWithin(1e-6).of(-300.0)
        // Under a Sunday rule it began a day earlier, so four.
        assertThat(
            Analytics.changeSinceWeekStart(series, WeekRule.SUNDAY, LocalDate.of(2026, 1, 14)),
        ).isWithin(1e-6).of(-400.0)
        // And neither is the seven days the old reading gave, which is what the two surfaces
        // showing "this week" disagreed with the chart about.
        assertThat(series.changeOverDays(7)).isWithin(1e-6).of(-700.0)
    }

    @Test
    fun `there is nothing to say about this week before the week began`() {
        val values = (0..13).map { 90_000.0 - 100.0 * it }

        // A day inside the first week the history covers has nothing before it to measure from.
        assertThat(
            Analytics.changeSinceWeekStart(series(values), WeekRule.MONDAY, day0),
        ).isNull()
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
