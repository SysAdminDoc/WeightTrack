package com.weighttrack.wear

import android.content.Context
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.sync.WearSummary
import kotlin.math.abs
import kotlin.math.roundToInt

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
        // A direction and a distance, and no weight anywhere in it. Somebody standing beside this
        // person can read a wrist as easily as they can, which is the whole point.
        summary.glanceOnly -> summary.aboveTrendGrams
            ?.let { above ->
                arrow(context, above) + " " +
                    WeightFormatter.full(abs(above).roundToInt(), summary.weightUnit)
            }
            ?: context.getString(R.string.wear_no_value)
        else -> summary.startingGrams
            ?.let { WeightFormatter.full(it, summary.weightUnit) }
            ?: context.getString(R.string.wear_no_value)
    }

    /** The line under it: the week's change, or what to do about there being nothing to show. */
    fun detail(context: Context, summary: WearSummary?): String = when {
        summary == null -> context.getString(R.string.wear_open_on_phone)
        summary.hidden -> context.getString(R.string.wear_unlock_on_phone)
        !summary.hasData -> context.getString(R.string.wear_no_readings)
        // The week's change is a weight too, so it goes with everything else in this mode.
        summary.glanceOnly -> summary.aboveTrendGrams
            ?.let { context.getString(against(it)) }
            ?: context.getString(R.string.wear_no_readings)
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
        // The headline already carries the arrow and the distance, and there is nothing else
        // this mode is allowed to say.
        summary.glanceOnly -> ""
        else -> summary.weekChangeGrams
            ?.let { WeightFormatter.delta(it, summary.weightUnit) }
            .orEmpty()
    }

    private fun arrow(context: Context, aboveTrendGrams: Double): String = context.getString(
        when {
            aboveTrendGrams > 0 -> R.string.wear_arrow_up
            aboveTrendGrams < 0 -> R.string.wear_arrow_down
            else -> R.string.wear_arrow_level
        },
    )

    private fun against(aboveTrendGrams: Double): Int = when {
        aboveTrendGrams > 0 -> R.string.wear_above_trend
        aboveTrendGrams < 0 -> R.string.wear_below_trend
        else -> R.string.wear_on_trend
    }

    /** Whether a glanceable surface has anything worth drawing. */
    fun hasFigures(summary: WearSummary?): Boolean =
        summary != null && summary.hasData && summary.startingGrams != null
}
