package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Where a week begins, and what that decides.
 *
 * The year boundary is the case that catches a rule built out of week numbers rather than out of
 * days: the last days of December and the first of January belong to one week, and which week
 * that is depends entirely on where the week starts.
 */
class WeekRuleTest {

    @Test
    fun `the phone's own region decides, and the two big regions differ`() {
        assertThat(WeekRule.forLocale(Locale.US).firstDay).isEqualTo(DayOfWeek.SUNDAY)
        assertThat(WeekRule.forLocale(Locale.GERMANY).firstDay).isEqualTo(DayOfWeek.MONDAY)
    }

    @Test
    fun `a week spanning new year is one week, and which one depends on the rule`() {
        // Thursday 2026-01-01. In the United States its week began on Sunday 2025-12-28; in
        // Germany it began on Monday 2025-12-29.
        val newYear = LocalDate.of(2026, 1, 1)

        val american = WeekRule.forLocale(Locale.US)
        assertThat(american.startOf(newYear)).isEqualTo(LocalDate.of(2025, 12, 28))
        assertThat(american.endOf(newYear)).isEqualTo(LocalDate.of(2026, 1, 3))

        val german = WeekRule.forLocale(Locale.GERMANY)
        assertThat(german.startOf(newYear)).isEqualTo(LocalDate.of(2025, 12, 29))
        assertThat(german.endOf(newYear)).isEqualTo(LocalDate.of(2026, 1, 4))
    }

    @Test
    fun `the Sunday a German week ends on is the Sunday an American week begins`() {
        val sunday = LocalDate.of(2025, 12, 28)

        assertThat(WeekRule.SUNDAY.startOf(sunday)).isEqualTo(sunday)
        assertThat(WeekRule.MONDAY.endOf(sunday)).isEqualTo(sunday)
        // And the two disagree about whether that Sunday and the Monday after it are one week.
        val monday = sunday.plusDays(1)
        assertThat(WeekRule.SUNDAY.sameWeek(sunday, monday)).isTrue()
        assertThat(WeekRule.MONDAY.sameWeek(sunday, monday)).isFalse()
    }

    @Test
    fun `the start of a week is the week's own first day`() {
        DayOfWeek.entries.forEach { first ->
            val rule = WeekRule(first)
            (0..13).forEach { offset ->
                val day = LocalDate.of(2026, 1, 1).plusDays(offset.toLong())
                val start = rule.startOf(day)
                assertThat(start.dayOfWeek).isEqualTo(first)
                assertThat(start).isAtMost(day)
                assertThat(rule.endOf(day)).isEqualTo(start.plusDays(6))
            }
        }
    }

    @Test
    fun `the last complete week is the one before the week in progress`() {
        // Wednesday 2026-01-07, mid-week under either rule.
        val wednesday = LocalDate.of(2026, 1, 7)

        assertThat(WeekRule.MONDAY.lastCompleteWeekStart(wednesday))
            .isEqualTo(LocalDate.of(2025, 12, 29))
        assertThat(WeekRule.SUNDAY.lastCompleteWeekStart(wednesday))
            .isEqualTo(LocalDate.of(2025, 12, 28))
    }
}
