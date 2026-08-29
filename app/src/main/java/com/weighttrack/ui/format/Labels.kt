package com.weighttrack.ui.format

import androidx.annotation.StringRes
import com.weighttrack.R
import com.weighttrack.core.nutrition.Meal
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * What to call the things the pure module knows about.
 *
 * `:core` has no resources and no Context, which is the point of it, so the names of its enums
 * live here instead. They used to be English strings on the enum constants themselves, which is
 * invisible to a translator and to the test that looks for untranslated text.
 */
object Labels {

    @StringRes
    fun of(meal: Meal): Int = when (meal) {
        Meal.BREAKFAST -> R.string.meal_breakfast
        Meal.LUNCH -> R.string.meal_lunch
        Meal.DINNER -> R.string.meal_dinner
        Meal.SNACK -> R.string.meal_snacks
    }

    /**
     * A weekday in the reader's language.
     *
     * Taken from the platform rather than from the enum constant's name, which is always English
     * and always capitalised the English way.
     */
    fun of(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        day.getDisplayName(TextStyle.FULL, locale)
}
