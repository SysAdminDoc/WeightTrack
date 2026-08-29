package com.weighttrack.notifications

import android.content.Context
import com.weighttrack.R
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.domain.WeeklySummary

/**
 * The words of the weekly summary.
 *
 * Its own thing rather than a pair of private functions inside the receiver, so a test can read
 * what the notification would actually say. The wording is the whole feature here: a summary that
 * reads as a telling-off is a defect however right the arithmetic behind it is.
 *
 * The summary itself carries the facts and nothing else, because the part that works out the
 * numbers has no Context and no business holding English.
 */
object WeeklySummaryText {

    fun headline(context: Context, summary: WeeklySummary, unit: WeightUnit): String =
        when (summary.headline) {
            WeeklySummary.Headline.MILESTONE -> context.getString(
                R.string.summary_milestone,
                WeightFormatter.full(summary.milestoneGrams ?: 0, unit),
            )
            WeeklySummary.Headline.STEADY -> context.getString(R.string.summary_steady)
            WeeklySummary.Headline.DOWN -> context.getString(
                R.string.summary_down,
                WeightFormatter.delta(summary.movementGrams, unit).removePrefix("+"),
            )
            WeeklySummary.Headline.UP -> context.getString(
                R.string.summary_up,
                WeightFormatter.delta(summary.movementGrams, unit).removePrefix("+"),
            )
        }

    fun detail(context: Context, summary: WeeklySummary, unit: WeightUnit): String = buildString {
        append(
            context.resources.getQuantityString(
                R.plurals.summary_trend_from_readings,
                summary.daysWeighed,
                WeightFormatter.full(summary.trendGrams, unit),
                summary.daysWeighed,
            ),
        )
        // Only when there is a direction to judge against. With no goal, or a goal to hold
        // steady, the summary says nothing rather than guessing what somebody wanted.
        summary.movingRight?.let { right ->
            append(" ")
            append(
                context.getString(
                    if (right) R.string.summary_heading_right else R.string.summary_not_toward_goal,
                ),
            )
        }
    }
}
