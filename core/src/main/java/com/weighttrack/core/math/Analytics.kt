package com.weighttrack.core.math

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class WeeklyChange(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val changeGrams: Double,
)

data class WeekdayEffect(
    val day: DayOfWeek,
    val averageDeviationGrams: Double,
    val readings: Int,
)

/**
 * Second-order readings of the trend: how each week compared with the last, and whether a
 * particular day of the week reliably reads heavy.
 */
object Analytics {

    /**
     * Change in the smoothed line across each of the most recent complete weeks.
     *
     * Calendar weeks under [rule], not seven-day blocks counted back from the newest reading.
     * Counted back, every bar moved a day whenever a reading arrived, "last week" meant something
     * different each morning, and none of it lined up with the week the person was thinking of.
     *
     * Each week's change is measured from the last day of the week before it, so a bar covers
     * seven days of movement rather than the six between a week's own ends. The week in progress
     * is left out: half a week of change drawn beside whole ones reads as a week that went well.
     */
    fun weeklyChanges(
        series: TrendSeries,
        weeks: Int = 12,
        rule: WeekRule = WeekRule.MONDAY,
    ): List<WeeklyChange> {
        val points = series.points
        if (points.size < 8) return emptyList()
        val earliest = points.first().date
        val latest = points.last().date

        val result = ArrayList<WeeklyChange>()
        // The week the newest reading falls in has not finished unless that reading is on or
        // after its last day. A person who weighs themselves on Friday and stops has a finished
        // week behind them, and asking for the exact Sunday would have dropped it.
        var weekStart = rule.startOf(latest)
        if (rule.endOf(latest) > latest) {
            weekStart = weekStart.minusDays(WeekRule.DAYS_IN_WEEK.toLong())
        }
        while (result.size < weeks && weekStart >= earliest) {
            val weekEnd = weekStart.plusDays(WeekRule.DAYS_IN_WEEK - 1L)
            // Where the line stood the day before the week began, or at the first reading there
            // is. A history that starts mid-week still gets a bar for its first whole week.
            val start = series.trendOnOrBefore(weekStart.minusDays(1))
                ?: points.first().trendGrams
            val end = series.trendOnOrBefore(weekEnd) ?: break
            result += WeeklyChange(
                weekStart = weekStart,
                weekEnd = weekEnd,
                changeGrams = end - start,
            )
            weekStart = weekStart.minusDays(WeekRule.DAYS_IN_WEEK.toLong())
        }
        return result.reversed()
    }

    /**
     * How far the smoothed line has moved since the current week began.
     *
     * What "this week" means on the home screen, the widget and the watch. All three counted
     * seven days back from the newest reading, so they said one thing while the chart beside
     * them and the weekly notification said another, on the same phone on the same day.
     *
     * Null when there is nothing to compare against yet, and null when nothing has been recorded
     * this week at all: zero would read as a week held steady rather than as a week not weighed.
     */
    fun changeSinceWeekStart(
        series: TrendSeries,
        rule: WeekRule = WeekRule.MONDAY,
        today: LocalDate = LocalDate.now(),
    ): Double? {
        val newest = series.points.lastOrNull() ?: return null
        val weekStart = rule.startOf(today)
        if (newest.date < weekStart) return null
        val before = series.trendOnOrBefore(weekStart.minusDays(1)) ?: return null
        return newest.trendGrams - before
    }

    /**
     * How far the trend moved across a window, and across the window of the same length before it.
     *
     * The second half is the part worth having. "Down 1.4 kg over the last month" is a fact
     * nobody can place; "down 1.4 kg, against 0.4 the month before" is the difference between a
     * month that worked and one that did not, and it is the question people open a chart to ask.
     *
     * Both are measured on the smoothed line rather than on the readings at either end, so a
     * heavy Tuesday at one edge of the window cannot invent a result.
     */
    data class RangeComparison(
        /** Null when the window holds nothing to measure across. */
        val changeGrams: Double?,
        /** Null when there is no history before the window, which is the ordinary case at first. */
        val previousChangeGrams: Double?,
        val days: Int,
    )

    /**
     * The change across [from] to [to] inclusive, beside the same span immediately before it.
     *
     * A window with nothing recorded in it reports null rather than zero: no change and no
     * readings look identical as a number and are not the same thing at all.
     */
    fun changeOverRange(
        series: TrendSeries,
        from: LocalDate,
        to: LocalDate,
    ): RangeComparison {
        val first = minOf(from, to)
        val last = maxOf(from, to)
        val days = (ChronoUnit.DAYS.between(first, last) + 1).toInt()
        fun changeAcross(start: LocalDate, end: LocalDate): Double? {
            val inside = series.points.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            if (inside.isEmpty()) return null
            // Measured from the day before the window where there is one, so a month's change is
            // the whole month rather than the month minus its first day. A single day inside the
            // window is still a change when there is a day in front of it to measure from, which
            // is what somebody who picks today as the start is asking about.
            val opening = series.trendOnOrBefore(start.minusDays(1))
                ?: inside.takeIf { it.size >= 2 }?.first()?.trendGrams
                ?: return null
            return inside.last().trendGrams - opening
        }
        val previousStart = first.minusDays(days.toLong())
        return RangeComparison(
            changeGrams = changeAcross(first, last),
            // Only when the history actually reaches back that far. A shorter stretch reported
            // as the same length flatters whichever window is chosen, and every one of these is
            // read as a comparison of like with like.
            previousChangeGrams = if (
                series.points.firstOrNull()?.date?.isBefore(previousStart) == true
            ) {
                changeAcross(previousStart, first.minusDays(1))
            } else {
                null
            },
            days = days,
        )
    }

    /**
     * Average distance between a day's actual reading and the smoothed line, grouped by weekday.
     *
     * A consistently positive Monday is the weekend showing up on the scale. Measuring against
     * the trend rather than against raw weight removes the underlying loss or gain, so what is
     * left is the weekly rhythm on its own.
     *
     * [excluded] days are left out of the averages entirely. A period puts on about half a
     * kilogram of extracellular water, and because a cycle is not seven days long it lands on a
     * different weekday every month: read as a weekly rhythm it is noise with an opinion, and it
     * would tell somebody their Thursdays are heavy when what is heavy is one week in four.
     */
    fun weekdayEffects(
        series: TrendSeries,
        minimumReadingsPerDay: Int = 2,
        excluded: Set<LocalDate> = emptySet(),
    ): List<WeekdayEffect> {
        val deviations = HashMap<DayOfWeek, MutableList<Double>>()
        series.points.forEach { point ->
            val actual = point.actualGrams ?: return@forEach
            if (point.date in excluded) return@forEach
            deviations.getOrPut(point.date.dayOfWeek) { ArrayList() }
                .add(actual - point.trendGrams)
        }
        val averages = DayOfWeek.entries.mapNotNull { day ->
            val values = deviations[day] ?: return@mapNotNull null
            if (values.size < minimumReadingsPerDay) return@mapNotNull null
            Triple(day, values.average(), values.size)
        }
        if (averages.isEmpty()) return emptyList()
        // Centred across the days there are, so what is shown is the rhythm of the week and not
        // the smoothing. The trend line lags a steady loss by design, which leaves every reading
        // sitting the same distance below it: real, but a fact about the line rather than about
        // Tuesdays, and shown uncentred it tells somebody losing weight that all seven of their
        // days are unusual.
        val overall = averages.sumOf { it.second } / averages.size
        return averages.map { (day, average, count) ->
            WeekdayEffect(day, average - overall, count)
        }
    }

    /** How many of the last [days] carried a reading, for a gentle consistency nudge. */
    fun loggingConsistency(series: TrendSeries, days: Int = 30): Pair<Int, Int> {
        val window = series.points.takeLast(days)
        return window.count { it.actualGrams != null } to window.size
    }

    /** Longest run of consecutive days with a reading, ending at the most recent day. */
    fun currentStreak(series: TrendSeries): Int {
        var streak = 0
        for (point in series.points.asReversed()) {
            if (point.actualGrams == null) break
            streak++
        }
        return streak
    }
}
