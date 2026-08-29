package com.weighttrack.wear

import android.content.Context
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.sync.WearSummary

/**
 * The words the tile and the complication use.
 *
 * Both surfaces say the same thing in different amounts of space, and both are drawn from the
 * cached summary rather than the phone, so what they may say is decided once and tested once.
 *
 * Takes a Context because the words live in the resource file like the rest of the app's. The
 * watch was left in English through the translation pass; a padlock on a wrist is no use to
 * somebody who cannot read the word beside it.
 */
object WearGlanceText {

    /** The big number: the trend weight, or a short reason there is not one. */
    fun headline(context: Context, summary: WearSummary?): String = when {
        summary == null -> context.getString(R.string.wear_no_value)
        // The app lock is on. A weight on a wrist is exactly what it exists to hide.
        summary.hidden -> context.getString(R.string.wear_locked)
        !summary.hasData -> context.getString(R.string.wear_no_value)
        else -> summary.startingGrams
            ?.let { WeightFormatter.full(it, summary.weightUnit) }
            ?: context.getString(R.string.wear_no_value)
    }

    /** The line under it: the week's change, or what to do about there being nothing to show. */
    fun detail(context: Context, summary: WearSummary?): String = when {
        summary == null -> context.getString(R.string.wear_open_on_phone)
        summary.hidden -> context.getString(R.string.wear_unlock_on_phone)
        !summary.hasData -> context.getString(R.string.wear_no_readings)
        else -> summary.weekChangeGrams
            ?.let {
                context.getString(
                    R.string.wear_this_week,
                    WeightFormatter.delta(it, summary.weightUnit),
                )
            }
            ?: context.getString(R.string.wear_trend_weight)
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
