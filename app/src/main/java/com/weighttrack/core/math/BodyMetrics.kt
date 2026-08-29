package com.weighttrack.core.math

import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.Sex
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

enum class BmiCategory {
    UNDERWEIGHT,
    HEALTHY,
    OVERWEIGHT,
    OBESE_I,
    OBESE_II,
    OBESE_III,
}

enum class WaistToHeightCategory {
    LOW,
    HEALTHY,
    INCREASED,
    HIGH,
}

/**
 * Body composition and energy maths. Everything here is a published formula with a citation,
 * because the whole point of the app is that these numbers cost nothing to compute and no one
 * should be paying a subscription for them.
 */
object BodyMetrics {

    /** Katch-McArdle constants, used when a real body fat figure is available. */
    private const val KATCH_BASE = 370.0
    private const val KATCH_LEAN_COEFFICIENT = 21.6

    fun bmi(grams: Int, heightMm: Int): Double? {
        if (grams <= 0 || heightMm <= 0) return null
        val metres = heightMm / 1000.0
        return UnitConverter.gramsToKg(grams) / (metres * metres)
    }

    const val BMI_UNDERWEIGHT_CEILING = 18.5
    const val BMI_OVERWEIGHT_FLOOR = 25.0

    fun bmiCategory(bmi: Double): BmiCategory = when {
        bmi < BMI_UNDERWEIGHT_CEILING -> BmiCategory.UNDERWEIGHT
        bmi < BMI_OVERWEIGHT_FLOOR -> BmiCategory.HEALTHY
        bmi < 30.0 -> BmiCategory.OVERWEIGHT
        bmi < 35.0 -> BmiCategory.OBESE_I
        bmi < 40.0 -> BmiCategory.OBESE_II
        else -> BmiCategory.OBESE_III
    }

    /**
     * Weight range that lands inside a healthy BMI for a given height.
     *
     * Both endpoints are snapped inward until they genuinely classify as healthy. Rounding
     * outward, or trusting the raw multiplication, hands back a range whose own boundaries
     * fail the check they came from: 18.5 x 1.8^2 divides back to 18.499999999999996.
     */
    fun healthyWeightRangeGrams(heightMm: Int): IntRange? {
        if (heightMm <= 0) return null
        val metres = heightMm / 1000.0
        val squared = metres * metres
        var lower = ceil(BMI_UNDERWEIGHT_CEILING * squared * UnitConverter.GRAMS_PER_KG).toInt()
        var upper = floor(BMI_OVERWEIGHT_FLOOR * squared * UnitConverter.GRAMS_PER_KG).toInt()
        while (lower < upper && bmi(lower, heightMm)!! < BMI_UNDERWEIGHT_CEILING) lower++
        while (upper > lower && bmi(upper, heightMm)!! >= BMI_OVERWEIGHT_FLOOR) upper--
        return lower..upper
    }

    /**
     * Mifflin-St Jeor basal metabolic rate, the formula with the best measured accuracy for
     * the general population.
     */
    fun basalMetabolicRate(grams: Int, heightMm: Int, ageYears: Int, sex: Sex): Double? {
        if (grams <= 0 || heightMm <= 0 || ageYears <= 0) return null
        val kg = UnitConverter.gramsToKg(grams)
        val cm = heightMm / 10.0
        val base = 10 * kg + 6.25 * cm - 5 * ageYears
        return when (sex) {
            Sex.MALE -> base + 5
            Sex.FEMALE -> base - 161
        }
    }

    /**
     * Katch-McArdle basal metabolic rate. More accurate than Mifflin-St Jeor once body fat is
     * known, because it works from lean mass and so does not need sex or age at all.
     */
    fun basalMetabolicRateFromLeanMass(leanMassGrams: Int): Double? {
        if (leanMassGrams <= 0) return null
        return KATCH_BASE + KATCH_LEAN_COEFFICIENT * UnitConverter.gramsToKg(leanMassGrams)
    }

    fun totalDailyEnergyExpenditure(bmr: Double, activityLevel: ActivityLevel): Double =
        bmr * activityLevel.factor

    /**
     * US Navy circumference body fat estimate, metric form.
     *
     * Needs neck and waist for men, and hips as well for women. Returns null rather than a
     * nonsense figure when the measurements cannot produce one, which happens when the waist
     * is not larger than the neck.
     */
    fun navyBodyFatPercent(
        sex: Sex,
        heightMm: Int,
        neckMm: Int,
        waistMm: Int,
        hipMm: Int? = null,
    ): Double? {
        if (heightMm <= 0 || neckMm <= 0 || waistMm <= 0) return null
        val heightCm = heightMm / 10.0
        val neckCm = neckMm / 10.0
        val waistCm = waistMm / 10.0

        val percent = when (sex) {
            Sex.MALE -> {
                val girth = waistCm - neckCm
                if (girth <= 0) return null
                495.0 / (1.0324 - 0.19077 * log10(girth) + 0.15456 * log10(heightCm)) - 450.0
            }
            Sex.FEMALE -> {
                val hipCm = (hipMm ?: return null) / 10.0
                if (hipMm <= 0) return null
                val girth = waistCm + hipCm - neckCm
                if (girth <= 0) return null
                495.0 / (1.29579 - 0.35004 * log10(girth) + 0.22100 * log10(heightCm)) - 450.0
            }
        }
        if (percent.isNaN() || percent.isInfinite()) return null
        return percent.coerceIn(1.0, 75.0)
    }

    fun fatMassGrams(grams: Int, bodyFatPercent: Double): Int? {
        if (grams <= 0 || bodyFatPercent <= 0 || bodyFatPercent >= 100) return null
        return (grams * bodyFatPercent / 100.0).roundToInt()
    }

    fun leanMassGrams(grams: Int, bodyFatPercent: Double): Int? {
        val fat = fatMassGrams(grams, bodyFatPercent) ?: return null
        return grams - fat
    }

    fun waistToHeightRatio(waistMm: Int, heightMm: Int): Double? {
        if (waistMm <= 0 || heightMm <= 0) return null
        return waistMm.toDouble() / heightMm
    }

    /**
     * Ashwell shape chart boundaries. A waist under half your height is the widely used
     * rule of thumb, and it predicts cardiometabolic risk better than BMI alone.
     */
    fun waistToHeightCategory(ratio: Double): WaistToHeightCategory = when {
        ratio < 0.40 -> WaistToHeightCategory.LOW
        ratio < 0.50 -> WaistToHeightCategory.HEALTHY
        ratio < 0.60 -> WaistToHeightCategory.INCREASED
        else -> WaistToHeightCategory.HIGH
    }

    /**
     * Daily calorie target implied by a desired rate of change, given maintenance needs.
     * A negative [gramsPerDay] is weight loss and produces a target below maintenance.
     */
    fun calorieTargetForRate(tdee: Double, gramsPerDay: Double): Double =
        tdee + gramsPerDay / UnitConverter.GRAMS_PER_KG * TrendEngine.KCAL_PER_KG
}
