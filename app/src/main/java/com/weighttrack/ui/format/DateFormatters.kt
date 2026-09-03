package com.weighttrack.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.weighttrack.R
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
    @Composable
    fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days == 0L -> stringResource(R.string.common_today)
            days == 1L -> stringResource(R.string.common_yesterday)
            days in 2..6 -> stringResource(R.string.common_days_ago, days)
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
    @Composable
    fun projection(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            days <= 0L -> stringResource(R.string.common_any_day_now)
            days == 1L -> stringResource(R.string.common_tomorrow)
            days < 14L -> stringResource(R.string.common_in_days, days)
            days < 60L -> stringResource(R.string.common_in_weeks, days / 7)
            else -> shortDate(date, today)
        }
    }

    /** How long ago a reading was taken, for the "last weighed" line. */
    @Composable
    fun sinceDay(date: LocalDate, today: LocalDate = LocalDate.now()): String =
        sinceDay(androidx.compose.ui.platform.LocalContext.current, date, today)

    /**
     * The same words, somewhere there is no composition to read them from.
     *
     * The home screen widget is drawn by Glance, which has no `stringResource`, and it has to say
     * the same thing as the app rather than a second version of it that drifts.
     */
    fun sinceDay(
        context: android.content.Context,
        date: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days <= 0L -> context.getString(R.string.common_today_2)
            days == 1L -> context.getString(R.string.common_yesterday_2)
            // Handed over as text, because the resources say %s. A Long there formats the same
            // way, and lint is right that a resource and its argument disagreeing is a thing to
            // notice rather than to leave for a translator to trip over.
            days < 7L -> context.getString(R.string.common_days_ago, days.toString())
            days < 14L -> context.getString(R.string.common_a_week_ago)
            days < 60L -> context.getString(R.string.common_weeks_ago, (days / 7).toString())
            else -> context.getString(R.string.common_months_ago, (days / 30).toString())
        }
    }
}
