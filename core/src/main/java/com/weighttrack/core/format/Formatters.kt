package com.weighttrack.ui.format

import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.VolumeUnit
import com.weighttrack.core.model.WeightUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object WeightFormatter {

    fun unitLabel(unit: WeightUnit): String = when (unit) {
        WeightUnit.KG -> "kg"
        WeightUnit.LB -> "lb"
        WeightUnit.ST_LB -> "st"
    }

    /** The bare number, with no unit, for places that show the unit separately. */
    fun value(grams: Int, unit: WeightUnit, decimals: Int = 1): String = when (unit) {
        WeightUnit.KG -> decimal(UnitConverter.gramsToKg(grams), decimals)
        WeightUnit.LB -> decimal(UnitConverter.gramsToLb(grams), decimals)
        WeightUnit.ST_LB -> {
            val (stones, pounds) = UnitConverter.gramsToStoneLb(grams)
            "$stones st ${decimal(pounds, decimals)}"
        }
    }

    fun full(grams: Int, unit: WeightUnit, decimals: Int = 1): String = when (unit) {
        WeightUnit.ST_LB -> "${value(grams, unit, decimals)} lb"
        else -> "${value(grams, unit, decimals)} ${unitLabel(unit)}"
    }

    /**
     * A change, always carrying its sign. Stones and pounds fall back to plain pounds here:
     * "minus 0 st 3.2 lb" reads badly and nobody talks that way about a week's change.
     */
    fun delta(grams: Double, unit: WeightUnit, decimals: Int = 1): String {
        val magnitude = when (unit) {
            WeightUnit.KG -> abs(grams) / UnitConverter.GRAMS_PER_KG
            WeightUnit.LB, WeightUnit.ST_LB -> abs(grams) / UnitConverter.GRAMS_PER_LB
        }
        val unitText = if (unit == WeightUnit.KG) "kg" else "lb"
        val sign = when {
            grams > 0.5 -> "+"
            grams < -0.5 -> "-"
            else -> ""
        }
        return "$sign${decimal(magnitude, decimals)} $unitText"
    }

    /** Weekly rate of change, the figure people actually compare against their target. */
    fun ratePerWeek(gramsPerWeek: Double, unit: WeightUnit, decimals: Int = 2): String =
        "${delta(gramsPerWeek, unit, decimals)}/week"

    fun calories(kcal: Double): String = "${kcal.roundToInt()} kcal"

    /** Signed energy balance, as in "480 kcal/day below maintenance". */
    fun energyBalance(kcalPerDay: Double): String {
        val rounded = abs(kcalPerDay).roundToInt()
        return when {
            kcalPerDay < -20 -> "$rounded kcal/day below maintenance"
            kcalPerDay > 20 -> "$rounded kcal/day above maintenance"
            else -> "About level with maintenance"
        }
    }

    private fun decimal(value: Double, decimals: Int): String =
        String.format(Locale.getDefault(), "%.${decimals}f", value)
}

object LengthFormatter {

    fun unitLabel(unit: LengthUnit): String = when (unit) {
        LengthUnit.CM -> "cm"
        LengthUnit.IN -> "in"
    }

    fun value(mm: Int, unit: LengthUnit, decimals: Int = 1): String =
        String.format(Locale.getDefault(), "%.${decimals}f", UnitConverter.mmToDisplay(mm, unit))

    fun full(mm: Int, unit: LengthUnit, decimals: Int = 1): String =
        "${value(mm, unit, decimals)} ${unitLabel(unit)}"

    /** Height reads as feet and inches for anyone using imperial units. */
    fun height(mm: Int, unit: LengthUnit): String = when (unit) {
        LengthUnit.CM -> String.format(Locale.getDefault(), "%.0f cm", UnitConverter.mmToCm(mm))
        LengthUnit.IN -> {
            val (feet, inches) = UnitConverter.mmToFeetInches(mm)
            String.format(Locale.getDefault(), "%d ft %.1f in", feet, inches)
        }
    }
}

object VolumeFormatter {

    fun unitLabel(unit: VolumeUnit): String = when (unit) {
        VolumeUnit.ML -> "ml"
        VolumeUnit.FL_OZ -> "fl oz"
    }

    /** Millilitres read as whole numbers; fluid ounces need a decimal to stay useful. */
    fun value(millilitres: Int, unit: VolumeUnit): String = when (unit) {
        VolumeUnit.ML -> millilitres.toString()
        VolumeUnit.FL_OZ -> String.format(Locale.getDefault(), "%.1f", UnitConverter.mlToFlOz(millilitres))
    }

    fun full(millilitres: Int, unit: VolumeUnit): String =
        "${value(millilitres, unit)} ${unitLabel(unit)}"
}
