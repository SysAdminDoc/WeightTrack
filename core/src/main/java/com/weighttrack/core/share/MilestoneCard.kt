package com.weighttrack.core.share

import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.math.Milestone
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.model.WeightUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * What a shareable card says, worked out apart from how it is drawn.
 *
 * Separated because the wording is the part that matters and the part that can be got wrong. A
 * card that puts somebody's actual weight in front of their family group chat when they only
 * meant to say they had lost a stone is a privacy failure, not a layout bug, and it deserves to
 * be checked in a test rather than looked at on a screen.
 */
object MilestoneCard {

    /**
     * Everything that goes on the card.
     *
     * [line] is the plain sentence for anybody sharing this as text rather than an image, so the
     * two never say different things.
     */
    data class Content(
        val headline: String,
        val subhead: String,
        val footer: String,
        /** The trend, scaled to nothing in particular: only its shape is drawn. */
        val shape: List<Double>,
        val line: String,
    )

    /**
     * The card for reaching a milestone.
     *
     * [includeWeight] is off by default and stays off unless somebody asks for it. The whole
     * point of the card is the distance travelled, which can be said without ever naming what
     * anybody weighs.
     */
    fun forMilestone(
        milestone: Milestone,
        startGrams: Int,
        series: TrendSeries,
        unit: WeightUnit,
        includeWeight: Boolean = false,
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(),
    ): Content {
        val changeGrams = milestone.grams - startGrams
        val headline = "${WeightFormatter.full(abs(changeGrams), unit)} ${direction(changeGrams)}"
        val reached = milestone.reachedOn ?: today
        val days = reached.toEpochDay() - startOf(series, reached).toEpochDay()
        return Content(
            headline = headline,
            subhead = when {
                days >= 1 -> "in ${days.toInt()} ${if (days == 1L) "day" else "days"}"
                else -> "so far"
            },
            footer = if (includeWeight) {
                "${WeightFormatter.full(milestone.grams, unit)} on ${date(reached, locale)}"
            } else {
                // Nothing that names what they weigh. Only when they have asked for it.
                date(reached, locale)
            },
            shape = shapeOf(series),
            line = "$headline ${
                if (days >= 1) "in ${days.toInt()} ${if (days == 1L) "day" else "days"}" else "so far"
            }.",
        )
    }

    /** The card for a stretch of progress, when there is no goal and so no milestones. */
    fun forProgress(
        fromGrams: Int,
        toGrams: Int,
        days: Int,
        series: TrendSeries,
        unit: WeightUnit,
        includeWeight: Boolean = false,
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(),
    ): Content {
        val changeGrams = toGrams - fromGrams
        val headline = "${WeightFormatter.full(abs(changeGrams), unit)} ${direction(changeGrams)}"
        val subhead = "in $days ${if (days == 1) "day" else "days"}"
        return Content(
            headline = headline,
            subhead = subhead,
            footer = if (includeWeight) {
                "${WeightFormatter.full(toGrams, unit)} on ${date(today, locale)}"
            } else {
                date(today, locale)
            },
            shape = shapeOf(series),
            line = "$headline $subhead.",
        )
    }

    /**
     * Down, up, or neither.
     *
     * "Steady" rather than "0 kg lost", because somebody who has held their weight for three
     * months has done something worth a card and calling it nothing would be wrong.
     */
    private fun direction(changeGrams: Int): String = when {
        changeGrams < 0 -> "down"
        changeGrams > 0 -> "up"
        else -> "steady"
    }

    /**
     * The trend line as a run of numbers between zero and one.
     *
     * Only the shape travels, never the values. A card that carried the axis would be a card that
     * told everybody what the person weighs, whatever the footer said.
     */
    fun shapeOf(series: TrendSeries, points: Int = SHAPE_POINTS): List<Double> {
        val trend = series.points.map { it.trendGrams }
        if (trend.size < 2) return emptyList()
        val sampled = if (trend.size <= points) {
            trend
        } else {
            // Evenly spaced, always including the last day, because the end of the line is the
            // part somebody is sharing.
            val step = (trend.size - 1).toDouble() / (points - 1)
            (0 until points).map { trend[(it * step).toInt().coerceAtMost(trend.size - 1)] }
        }
        val min = sampled.min()
        val max = sampled.max()
        // A perfectly flat line has no range to scale by. Drawn down the middle rather than
        // divided by zero.
        if (max - min <= 0.0) return sampled.map { 0.5 }
        return sampled.map { (it - min) / (max - min) }
    }

    private fun startOf(series: TrendSeries, fallback: LocalDate): LocalDate =
        series.points.firstOrNull()?.date ?: fallback

    private fun date(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))

    /** Enough to show the shape of a line, few enough to draw cleanly at any size. */
    const val SHAPE_POINTS = 60
}
