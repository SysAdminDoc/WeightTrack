package com.weighttrack.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object DateFormatters {

    private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val timeOnly: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    /** "Today", "Yesterday", "4 days ago", then an actual date once that stops being useful. */
    fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days in 2..6 -> "$days days ago"
            date.year == today.year -> dayMonth.format(date)
            else -> dayMonthYear.format(date)
        }
    }

    fun shortDate(date: LocalDate, today: LocalDate = LocalDate.now()): String =
        if (date.year == today.year) dayMonth.format(date) else dayMonthYear.format(date)

    fun fullDate(date: LocalDate): String = dayMonthYear.format(date)

    fun time(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        timeOnly.format(instant.atZone(zone))

    /** A projected date, phrased as a distance when that is easier to grasp than a calendar day. */
    fun projection(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            days <= 0L -> "Any day now"
            days == 1L -> "Tomorrow"
            days < 14L -> "In $days days"
            days < 60L -> "In ${days / 7} weeks"
            else -> shortDate(date, today)
        }
    }

    /** How long ago a reading was taken, for the "last weighed" line. */
    fun sinceDay(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            days < 7L -> "$days days ago"
            days < 14L -> "a week ago"
            days < 60L -> "${days / 7} weeks ago"
            else -> "${days / 30} months ago"
        }
    }
}
