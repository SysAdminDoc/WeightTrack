package com.weighttrack.core.nutrition

/**
 * When something was eaten.
 *
 * Four, and no way to add more. A food log that lets somebody invent meal names ends up with
 * "lunch", "Lunch" and "lunch 2", and nothing groups. Anything that is not one of the three
 * meals is a snack, which is what it is.
 */
enum class Meal {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
    ;

    companion object {
        /** The meal to offer first, given the hour, so the common case needs no tap. */
        fun forHour(hour: Int): Meal = when (hour) {
            in 4..10 -> BREAKFAST
            in 11..15 -> LUNCH
            in 16..21 -> DINNER
            else -> SNACK
        }
    }
}
