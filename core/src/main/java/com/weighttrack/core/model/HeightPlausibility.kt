package com.weighttrack.core.model

/**
 * What can be a person's height, in whole millimetres.
 *
 * Height is typed once and then divides into every weight for ever, so a wrong one is not a wrong
 * number on one screen: it is a wrong BMI, a wrong healthy range and a wrong resting burn, on
 * every screen, until somebody notices. Nothing else in the app has that reach from a single
 * field.
 *
 * The trap is the inches field. Somebody who is six foot types 6, which is 152 mm, and the old
 * guard of "greater than zero" let it through. A real phone was found reading BMI 4805.7 and
 * calling its owner obese, class 3, with a healthy range of 0.9 to 1.3 lb. Nothing about that
 * was flagged, and the settings screen it could have been corrected on was the one crashing.
 *
 * The bounds are deliberately generous rather than typical: the shortest and tallest adults ever
 * recorded are about 55 cm and 272 cm, and this is a refusal of the impossible, not an opinion
 * about who is allowed to use a weight tracker.
 */
object HeightPlausibility {

    const val MIN_MM = 500
    const val MAX_MM = 2_800

    fun isPlausible(heightMm: Int): Boolean = heightMm in MIN_MM..MAX_MM

    /** The height to keep, or null when what arrived cannot be one. */
    fun orNull(heightMm: Int): Int? = heightMm.takeIf { isPlausible(it) }
}
