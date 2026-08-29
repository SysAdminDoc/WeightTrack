package com.weighttrack.wear

import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.sync.WearSummary

/**
 * The words the tile and the complication use.
 *
 * Both surfaces say the same thing in different amounts of space, and both are drawn from the
 * cached summary rather than the phone, so what they may say is decided once and tested once.
 */
object WearGlanceText {

    /** The big number: the trend weight, or a short reason there is not one. */
    fun headline(summary: WearSummary?): String = when {
        summary == null -> "--"
        // The app lock is on. A weight on a wrist is exactly what it exists to hide.
        summary.hidden -> "Locked"
        !summary.hasData -> "--"
        else -> summary.startingGrams
            ?.let { WeightFormatter.full(it, summary.weightUnit) }
            ?: "--"
    }

    /** The line under it: the week's change, or what to do about there being nothing to show. */
    fun detail(summary: WearSummary?): String = when {
        summary == null -> "Open WeightTrack on your phone"
        summary.hidden -> "Unlock on your phone"
        !summary.hasData -> "No readings yet"
        else -> summary.weekChangeGrams
            ?.let { WeightFormatter.delta(it, summary.weightUnit) + " this week" }
            ?: "Trend weight"
    }

    /** Short enough for a complication's second line, which is a handful of characters. */
    fun shortDetail(summary: WearSummary?): String = when {
        summary == null || summary.hidden || !summary.hasData -> ""
        else -> summary.weekChangeGrams
            ?.let { WeightFormatter.delta(it, summary.weightUnit) }
            .orEmpty()
    }

    /** Whether a glanceable surface has anything worth drawing. */
    fun hasFigures(summary: WearSummary?): Boolean =
        summary != null && summary.hasData && summary.startingGrams != null
}
