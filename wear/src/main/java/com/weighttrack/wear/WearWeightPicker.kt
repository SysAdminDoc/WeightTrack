package com.weighttrack.wear

import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.WeightUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The rotary weight picker, as numbers.
 *
 * It counts steps in the person's own unit rather than grams. Winding a crown accumulates, and
 * 0.1 lb rounded to whole grams first would be nearly a pound out after a couple of hundred
 * turns. Grams are worked out once, when the reading is sent.
 */
object WearWeightPicker {

    /** Nobody weighs less than this or more than that, so the crown stops rather than wrapping. */
    const val MIN_GRAMS = 20_000
    const val MAX_GRAMS = 400_000

    /** Where to open when the phone has never sent a reading. */
    const val FALLBACK_GRAMS = 75_000

    private const val PIXELS_PER_STEP = 40f
    private const val MAX_STEPS_PER_EVENT = 10

    fun stepsFor(grams: Int, unit: WeightUnit): Int =
        UnitConverter.stepForGrams(grams.coerceIn(MIN_GRAMS, MAX_GRAMS), unit)

    fun gramsFor(steps: Int, unit: WeightUnit): Int =
        UnitConverter.gramsForStep(steps, unit).coerceIn(MIN_GRAMS, MAX_GRAMS)

    fun clamp(steps: Int, unit: WeightUnit): Int =
        steps.coerceIn(stepsFor(MIN_GRAMS, unit), stepsFor(MAX_GRAMS, unit))

    /**
     * Decimals the step size actually needs.
     *
     * A 0.05 kg step shown to one decimal would sit still for every other turn of the crown.
     */
    fun decimals(unit: WeightUnit): Int = when (unit) {
        WeightUnit.KG -> 2
        WeightUnit.LB, WeightUnit.ST_LB -> 1
    }

    /**
     * Whole picker steps for one turn of the crown.
     *
     * The crown reports scroll pixels, and a slow deliberate turn reports very few, so the
     * smallest movement has to still be worth one step or the control feels dead. A fast spin
     * is capped: someone flicking the bezel means "a long way", not four hundred grams a frame.
     */
    fun rotarySteps(scrollPixels: Float): Int {
        if (scrollPixels == 0f) return 0
        val direction = if (scrollPixels > 0) 1 else -1
        val magnitude = (abs(scrollPixels) / PIXELS_PER_STEP).roundToInt().coerceIn(1, MAX_STEPS_PER_EVENT)
        return direction * magnitude
    }

    /** The reading under the crown, in the person's unit. */
    fun label(steps: Int, unit: WeightUnit): String =
        WeightFormatter.full(gramsFor(steps, unit), unit, decimals(unit))
}
