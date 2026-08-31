package com.weighttrack.core.math

import java.time.DayOfWeek
import java.time.LocalDate

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
     * Average distance between a day's actual reading and the smoothed line, grouped by weekday.
     *
     * A consistently positive Monday is the weekend showing up on the scale. Measuring against
     * the trend rather than against raw weight removes the underlying loss or gain, so what is
     * left is the weekly rhythm on its own.
     */
    fun weekdayEffects(series: TrendSeries, minimumReadingsPerDay: Int = 2): List<WeekdayEffect> {
        val deviations = HashMap<DayOfWeek, MutableList<Double>>()
        series.points.forEach { point ->
            val actual = point.actualGrams ?: return@forEach
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
