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
    ): WeeklySummary? {
        val trend = series.latestTrendGrams?.roundToInt() ?: return null
        val weekStart = today.minusDays(6)
        val daysWeighed = series.points.count { !it.date.isBefore(weekStart) && it.actualGrams != null }
        if (daysWeighed < MINIMUM_DAYS_WEIGHED) return null

        val change = series.changeOverDays(7) ?: return null

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
