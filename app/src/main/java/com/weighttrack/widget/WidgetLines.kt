package com.weighttrack.widget

import android.content.Context
import com.weighttrack.R
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.ui.format.DateFormatters
import kotlin.math.abs

/**
 * Every word the weight widget can put on a home screen.
 *
 * Kept apart from the drawing because of the promise the glance mode makes. "Show a direction and
 * never the weight" is a claim about text, and a claim about text is only worth anything if
 * something can read all of it at once: a branch buried in a Glance composable is a branch nobody
 * can assert over.
 */
internal data class WidgetLines(
    val caption: String,
    val value: String,
    /** Shown small beside [value]. Null when the value is a phrase rather than a figure. */
    val unit: String?,
    val change: String?,
    val logged: String?,
) {
    /** Everything a person would read, in order. */
    val all: List<String> get() = listOfNotNull(caption, value, unit, change, logged)
}

internal fun widgetLines(context: Context, data: WidgetData): WidgetLines {
    fun words(id: Int, vararg arguments: Any) = context.getString(id, *arguments)

    val caption = if (data.glanceOnly && !data.hidden) {
        words(R.string.widget_against_trend)
    } else {
        words(R.string.widget_trend)
    }
    val logged = data.lastLogged
        ?.let { words(R.string.widget_weighed, DateFormatters.sinceDay(context, it)) }

    if (data.hidden) {
        return WidgetLines(
            caption = caption,
            value = words(R.string.widget_locked),
            unit = null,
            change = words(R.string.widget_open_weighttrack),
            logged = null,
        )
    }

    if (data.glanceOnly) {
        val above = data.aboveTrendGrams
            ?: return WidgetLines(caption, words(R.string.widget_tap_to_log), null, null, logged)
        // The arrow carries the direction and the figure is a distance, so nothing here is a
        // number anybody could read as a body weight over a shoulder.
        val arrow = when {
            above > 0 -> words(R.string.widget_arrow_up)
            above < 0 -> words(R.string.widget_arrow_down)
            else -> words(R.string.widget_arrow_level)
        }
        return WidgetLines(
            caption = caption,
            value = arrow + " " + WeightFormatter.value(abs(above).toInt(), data.unit),
            unit = WeightFormatter.unitLabel(data.unit),
            change = when {
                above > 0 -> words(R.string.widget_above_your_trend)
                above < 0 -> words(R.string.widget_below_your_trend)
                else -> words(R.string.widget_on_your_trend)
            },
            logged = logged,
        )
    }

    val trend = data.trendGrams
        ?: return WidgetLines(caption, words(R.string.widget_tap_to_log), null, null, logged)
    return WidgetLines(
        caption = caption,
        value = WeightFormatter.value(trend, data.unit),
        unit = WeightFormatter.unitLabel(data.unit),
        change = data.weekChangeGrams?.let {
            words(R.string.widget_this_week, WeightFormatter.delta(it, data.unit))
        },
        logged = logged,
    )
}
