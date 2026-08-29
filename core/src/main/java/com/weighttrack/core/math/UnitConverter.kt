package com.weighttrack.core.math

import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.VolumeUnit
import com.weighttrack.core.model.WeightUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Conversions between stored grams/millimetres and the units a person actually reads.
 *
 * Storage is integral (grams, millimetres) so a value survives any number of unit switches
 * unchanged. Rounding happens once, at the display boundary.
 */
object UnitConverter {

    const val GRAMS_PER_KG = 1000.0
    const val GRAMS_PER_LB = 453.59237
    const val LB_PER_STONE = 14
    const val MM_PER_CM = 10.0
    /** US fluid ounce, which is what a person logging water in the US means. */
    const val ML_PER_FL_OZ = 29.5735295625
    const val MM_PER_INCH = 25.4
    const val INCHES_PER_FOOT = 12

    /** Smallest increment a person can enter, expressed in grams, per unit. */
    fun stepGrams(unit: WeightUnit): Int = when (unit) {
        WeightUnit.KG -> 50 // 0.05 kg
        WeightUnit.LB, WeightUnit.ST_LB -> 45 // ~0.1 lb
    }

    /**
     * The increment a picker moves by, in the display unit itself: 0.05 kg or 0.1 lb.
     *
     * [stepGrams] rounds the same increment to whole grams, which is right for snapping one
     * value but wrong for a control that accumulates. 0.1 lb rounded to 45 g is short by about
     * a third of a gram a turn, so a watch crown wound through a couple of hundred steps would
     * land nearly a pound off. A picker counts steps and converts once, here.
     */
    fun stepDisplayValue(unit: WeightUnit): Double = when (unit) {
        WeightUnit.KG -> 0.05
        WeightUnit.LB, WeightUnit.ST_LB -> 0.1
    }

    /** Grams for a whole number of picker steps. */
    fun gramsForStep(steps: Int, unit: WeightUnit): Int =
        (steps * stepDisplayValue(unit) * gramsPerDisplayUnit(unit)).roundToInt()

    /** The nearest picker step to a stored weight. */
    fun stepForGrams(grams: Int, unit: WeightUnit): Int =
        (grams / (stepDisplayValue(unit) * gramsPerDisplayUnit(unit))).roundToInt()

    private fun gramsPerDisplayUnit(unit: WeightUnit): Double = when (unit) {
        WeightUnit.KG -> GRAMS_PER_KG
        WeightUnit.LB, WeightUnit.ST_LB -> GRAMS_PER_LB
    }

    fun gramsToKg(grams: Int): Double = grams / GRAMS_PER_KG

    fun kgToGrams(kg: Double): Int = (kg * GRAMS_PER_KG).roundToInt()

    fun gramsToLb(grams: Int): Double = grams / GRAMS_PER_LB

    fun lbToGrams(lb: Double): Int = (lb * GRAMS_PER_LB).roundToInt()

    /** Whole stones plus the remaining pounds. Negative input keeps the sign on the stones. */
    fun gramsToStoneLb(grams: Int): Pair<Int, Double> {
        val totalLb = gramsToLb(grams)
        val sign = if (totalLb < 0) -1 else 1
        val magnitude = abs(totalLb)
        var stones = (magnitude / LB_PER_STONE).toInt()
        var pounds = magnitude - stones * LB_PER_STONE
        // A value such as 13.999 lb must not render as "0 st 14.0 lb".
        if (round1(pounds) >= LB_PER_STONE.toDouble()) {
            stones += 1
            pounds = 0.0
        }
        return (sign * stones) to pounds
    }

    fun stoneLbToGrams(stones: Int, pounds: Double): Int =
        lbToGrams(stones * LB_PER_STONE + pounds)

    /** Converts stored grams into the numeric value shown for [unit]. */
    fun gramsToDisplay(grams: Int, unit: WeightUnit): Double = when (unit) {
        WeightUnit.KG -> gramsToKg(grams)
        WeightUnit.LB, WeightUnit.ST_LB -> gramsToLb(grams)
    }

    fun displayToGrams(value: Double, unit: WeightUnit): Int = when (unit) {
        WeightUnit.KG -> kgToGrams(value)
        WeightUnit.LB, WeightUnit.ST_LB -> lbToGrams(value)
    }

    fun mlToFlOz(millilitres: Int): Double = millilitres / ML_PER_FL_OZ

    fun flOzToMl(fluidOunces: Double): Int = (fluidOunces * ML_PER_FL_OZ).roundToInt()

    fun mlToDisplay(millilitres: Int, unit: VolumeUnit): Double = when (unit) {
        VolumeUnit.ML -> millilitres.toDouble()
        VolumeUnit.FL_OZ -> mlToFlOz(millilitres)
    }

    fun displayToMl(value: Double, unit: VolumeUnit): Int = when (unit) {
        VolumeUnit.ML -> value.roundToInt()
        VolumeUnit.FL_OZ -> flOzToMl(value)
    }

    fun mmToCm(mm: Int): Double = mm / MM_PER_CM

    fun cmToMm(cm: Double): Int = (cm * MM_PER_CM).roundToInt()

    fun mmToInches(mm: Int): Double = mm / MM_PER_INCH

    fun inchesToMm(inches: Double): Int = (inches * MM_PER_INCH).roundToInt()

    /** Whole feet plus the remaining inches, with the same carry guard as stones and pounds. */
    fun mmToFeetInches(mm: Int): Pair<Int, Double> {
        val totalInches = mmToInches(mm)
        val sign = if (totalInches < 0) -1 else 1
        val magnitude = abs(totalInches)
        var feet = (magnitude / INCHES_PER_FOOT).toInt()
        var inches = magnitude - feet * INCHES_PER_FOOT
        if (round1(inches) >= INCHES_PER_FOOT.toDouble()) {
            feet += 1
            inches = 0.0
        }
        return (sign * feet) to inches
    }

    fun feetInchesToMm(feet: Int, inches: Double): Int =
        inchesToMm(feet * INCHES_PER_FOOT + inches)

    fun mmToDisplay(mm: Int, unit: LengthUnit): Double = when (unit) {
        LengthUnit.CM -> mmToCm(mm)
        LengthUnit.IN -> mmToInches(mm)
    }

    fun displayToMm(value: Double, unit: LengthUnit): Int = when (unit) {
        LengthUnit.CM -> cmToMm(value)
        LengthUnit.IN -> inchesToMm(value)
    }

    /**
     * Rounds a stored gram value to the nearest increment the current unit can express.
     * Keeps a scale reading and a typed value comparable after a unit switch.
     */
    fun snapToStep(grams: Int, unit: WeightUnit): Int {
        val step = stepGrams(unit)
        return ((grams.toDouble() / step).roundToLong() * step).toInt()
    }

    private fun round1(value: Double): Double = (value * 10).roundToInt() / 10.0
}
