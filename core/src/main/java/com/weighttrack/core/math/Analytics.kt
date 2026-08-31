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
        val byDate = points.associateBy { it.date }
        val earliest = points.first().date

        val result = ArrayList<WeeklyChange>()
        var weekStart = rule.startOf(points.last().date)
        while (result.size < weeks) {
            // The day before the week began, which is what its change is measured from.
            val before = weekStart.minusDays(1)
            val lastDay = weekStart.plusDays(WeekRule.DAYS_IN_WEEK - 1L)
            if (before < earliest) break
            val start = byDate[before]
            val end = byDate[lastDay]
            if (start != null && end != null) {
                result += WeeklyChange(
                    weekStart = weekStart,
                    weekEnd = end.date,
                    changeGrams = end.trendGrams - start.trendGrams,
                )
            } else if (result.isNotEmpty()) {
                // A hole in the middle, which the series does not produce. The week in progress
                // is the only week that legitimately has no last day, and it is the first one
                // looked at.
                break
            }
            weekStart = weekStart.minusDays(WeekRule.DAYS_IN_WEEK.toLong())
        }
        return result.reversed()
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
