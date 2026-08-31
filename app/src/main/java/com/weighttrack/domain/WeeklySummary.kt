package com.weighttrack.domain

import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.format.WeightFormatter
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The sentence a weekly summary is worth sending.
 *
 * Returning null is a real outcome: a notification that says "no change to report" every
 * Sunday is the kind of nagging that gets an app uninstalled, so the summary only exists when
 * there is something to say.
 */
data class WeeklySummary(
    val changeGrams: Double,
    val trendGrams: Int,
    val daysWeighed: Int,
    /** Which of the four things the week amounted to. */
    val headline: Headline,
    /** The milestone crossed, when [headline] is [Headline.MILESTONE]. */
    val milestoneGrams: Int? = null,
    /** How far the week moved, already made positive, for the headline to quote. */
    val movementGrams: Double = 0.0,
    /**
     * Whether the week went the way the goal wants.
     *
     * Null when there is no goal, or the goal is to hold steady, in which case there is nothing
     * to add and the summary says nothing rather than guessing.
     */
    val movingRight: Boolean? = null,
) {
    enum class Headline { MILESTONE, STEADY, DOWN, UP }
}

object WeeklySummaryBuilder {

    /**
     * The first day of the week a summary sent on [today] describes.
     *
     * The week that has finished, unless today is the last day of the week in progress, in
     * which case that week has finished too and is the one worth reporting.
     */
    fun weekStart(
        today: LocalDate,
        rule: com.weighttrack.core.math.WeekRule,
    ): LocalDate = if (rule.endOf(today) == today) rule.startOf(today) else rule.lastCompleteWeekStart(today)

    /** Below this a week is noise rather than movement. */
    const val MEANINGFUL_CHANGE_GRAMS = 100.0

    /** Fewer readings than this and a weekly figure is not worth quoting. */
    const val MINIMUM_DAYS_WEIGHED = 2

    fun build(
        series: TrendSeries,
        unit: WeightUnit,
        goalDirection: GoalDirection?,
        milestoneReachedThisWeek: Int?,
        today: LocalDate = LocalDate.now(),
        rule: com.weighttrack.core.math.WeekRule = com.weighttrack.core.math.WeekRule.MONDAY,
    ): WeeklySummary? {
        val trend = series.latestTrendGrams?.roundToInt() ?: return null
        // The week that has finished, under the same rule the charts draw. The trailing seven
        // days moved every morning, so a summary sent on a Tuesday and one sent on a Friday
        // covered different weeks and neither was the week the person was thinking of.
        val weekStart = weekStart(today, rule)
        val weekEnd = weekStart.plusDays(com.weighttrack.core.math.WeekRule.DAYS_IN_WEEK - 1L)
        val daysWeighed = series.points.count {
            !it.date.isBefore(weekStart) && !it.date.isAfter(weekEnd) && it.actualGrams != null
        }
        if (daysWeighed < MINIMUM_DAYS_WEIGHED) return null

        // Across that week, from where the trend stood the day before it began. A week's figure
        // has to be about the week, not about the seven days ending whenever this was sent.
        val trendByDate = series.points.associate { it.date to it.trendGrams }
        val before = trendByDate[weekStart.minusDays(1)] ?: return null
        val after = trendByDate[weekEnd] ?: return null
        val change = after - before

        val headline = when {
            milestoneReachedThisWeek != null -> WeeklySummary.Headline.MILESTONE
            abs(change) < MEANINGFUL_CHANGE_GRAMS -> WeeklySummary.Headline.STEADY
            change < 0 -> WeeklySummary.Headline.DOWN
            else -> WeeklySummary.Headline.UP
        }

        val movingRight = when (goalDirection) {
            GoalDirection.LOSE -> change < -MEANINGFUL_CHANGE_GRAMS
            GoalDirection.GAIN -> change > MEANINGFUL_CHANGE_GRAMS
            else -> abs(change) < MEANINGFUL_CHANGE_GRAMS
        }

        return WeeklySummary(
            changeGrams = change,
            trendGrams = trend,
            daysWeighed = daysWeighed,
            headline = headline,
            milestoneGrams = milestoneReachedThisWeek,
            movementGrams = abs(change),
            // Nothing to say when there is no goal, or when the goal is to hold steady and the
            // week already reads as steady or not.
            movingRight = movingRight.takeIf {
                goalDirection != null && goalDirection != GoalDirection.MAINTAIN
            },
        )
    }

    /** Kilograms per week, for callers that want the raw figure rather than the sentence. */
    fun kilogramsPerWeek(changeGrams: Double): Double = changeGrams / UnitConverter.GRAMS_PER_KG
}
