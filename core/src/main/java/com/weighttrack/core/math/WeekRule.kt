package com.weighttrack.core.math

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Where a week begins, in one place.
 *
 * Weekly figures used to be counted back seven days at a time from the newest reading, so "this
 * week" meant the seven days ending today and moved every morning. Two people looking at the same
 * history on a Tuesday and a Friday saw different weeks, and neither matched the week either of
 * them meant. Which day a week starts on is also not a fact about weight: it is Monday across most
 * of Europe and Sunday across most of North America, and the phone already knows which.
 *
 * The rule decides boundaries and nothing else. It never touches a stored date: a reading is
 * filed on the day it was taken, and changing where weeks begin changes only how they are
 * gathered up afterwards.
 */
@JvmInline
value class WeekRule(val firstDay: DayOfWeek) {

    /** The first day of the week [date] falls in. */
    fun startOf(date: LocalDate): LocalDate {
        val shift = (date.dayOfWeek.value - firstDay.value + DAYS_IN_WEEK) % DAYS_IN_WEEK
        return date.minusDays(shift.toLong())
    }

    /** The last day of the week [date] falls in. */
    fun endOf(date: LocalDate): LocalDate = startOf(date).plusDays(DAYS_IN_WEEK - 1L)

    /** Whether two days belong to the same week. */
    fun sameWeek(first: LocalDate, second: LocalDate): Boolean = startOf(first) == startOf(second)

    /** The last week that has finished, as its first day. */
    fun lastCompleteWeekStart(today: LocalDate): LocalDate =
        startOf(today).minusDays(DAYS_IN_WEEK.toLong())

    companion object {
        const val DAYS_IN_WEEK = 7

        /** What the phone's own region says, which is what a person expects to see. */
        fun forLocale(locale: Locale = Locale.getDefault()): WeekRule =
            WeekRule(WeekFields.of(locale).firstDayOfWeek)

        val MONDAY = WeekRule(DayOfWeek.MONDAY)
        val SUNDAY = WeekRule(DayOfWeek.SUNDAY)
    }
}
