package com.weighttrack.domain

import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.format.WeightFormatter
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
    val headline: String,
    val detail: String,
)

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
            milestoneReachedThisWeek != null ->
                "Milestone reached: ${WeightFormatter.full(milestoneReachedThisWeek, unit)}"
            abs(change) < MEANINGFUL_CHANGE_GRAMS -> "Steady week"
            change < 0 -> "Down ${WeightFormatter.delta(abs(change), unit).removePrefix("+")} this week"
            else -> "Up ${WeightFormatter.delta(abs(change), unit).removePrefix("+")} this week"
        }

        val movingRight = when (goalDirection) {
            GoalDirection.LOSE -> change < -MEANINGFUL_CHANGE_GRAMS
            GoalDirection.GAIN -> change > MEANINGFUL_CHANGE_GRAMS
            else -> abs(change) < MEANINGFUL_CHANGE_GRAMS
        }

        val detail = buildString {
            append("Trend is ${WeightFormatter.full(trend, unit)}")
            append(" from $daysWeighed ")
            append(if (daysWeighed == 1) "reading" else "readings")
            append(".")
            if (goalDirection != null && goalDirection != GoalDirection.MAINTAIN) {
                append(if (movingRight) " Heading the right way." else " Not moving toward the goal this week.")
            }
        }

        return WeeklySummary(
            changeGrams = change,
            trendGrams = trend,
            daysWeighed = daysWeighed,
            headline = headline,
            detail = detail,
        )
    }

    /** Kilograms per week, for callers that want the raw figure rather than the sentence. */
    fun kilogramsPerWeek(changeGrams: Double): Double = changeGrams / UnitConverter.GRAMS_PER_KG
}
