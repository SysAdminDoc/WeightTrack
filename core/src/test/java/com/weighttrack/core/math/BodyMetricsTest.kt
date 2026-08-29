package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.Sex
import org.junit.Test

class BodyMetricsTest {

    @Test
    fun `bmi is mass over height squared`() {
        assertThat(BodyMetrics.bmi(80_000, 1_800)).isWithin(1e-6).of(80.0 / (1.8 * 1.8))
    }

    @Test
    fun `bmi rejects impossible inputs`() {
        assertThat(BodyMetrics.bmi(0, 1_800)).isNull()
        assertThat(BodyMetrics.bmi(80_000, 0)).isNull()
    }

    @Test
    fun `bmi categories follow the who boundaries`() {
        assertThat(BodyMetrics.bmiCategory(17.0)).isEqualTo(BmiCategory.UNDERWEIGHT)
        assertThat(BodyMetrics.bmiCategory(18.5)).isEqualTo(BmiCategory.HEALTHY)
        assertThat(BodyMetrics.bmiCategory(24.9)).isEqualTo(BmiCategory.HEALTHY)
        assertThat(BodyMetrics.bmiCategory(25.0)).isEqualTo(BmiCategory.OVERWEIGHT)
        assertThat(BodyMetrics.bmiCategory(30.0)).isEqualTo(BmiCategory.OBESE_I)
        assertThat(BodyMetrics.bmiCategory(35.0)).isEqualTo(BmiCategory.OBESE_II)
        assertThat(BodyMetrics.bmiCategory(41.0)).isEqualTo(BmiCategory.OBESE_III)
    }

    @Test
    fun `healthy weight range brackets a healthy bmi`() {
        val range = BodyMetrics.healthyWeightRangeGrams(1_800)!!
        assertThat(BodyMetrics.bmiCategory(BodyMetrics.bmi(range.first, 1_800)!!))
            .isEqualTo(BmiCategory.HEALTHY)
        assertThat(BodyMetrics.bmiCategory(BodyMetrics.bmi(range.last, 1_800)!!))
            .isEqualTo(BmiCategory.HEALTHY)
        assertThat(BodyMetrics.bmiCategory(BodyMetrics.bmi(range.first - 500, 1_800)!!))
            .isEqualTo(BmiCategory.UNDERWEIGHT)
        assertThat(BodyMetrics.bmiCategory(BodyMetrics.bmi(range.last + 500, 1_800)!!))
            .isEqualTo(BmiCategory.OVERWEIGHT)
    }

    @Test
    fun `mifflin st jeor for men`() {
        // 10(80) + 6.25(180) - 5(30) + 5
        assertThat(BodyMetrics.basalMetabolicRate(80_000, 1_800, 30, Sex.MALE))
            .isWithin(1e-6).of(1_780.0)
    }

    @Test
    fun `mifflin st jeor for women`() {
        assertThat(BodyMetrics.basalMetabolicRate(80_000, 1_800, 30, Sex.FEMALE))
            .isWithin(1e-6).of(1_614.0)
    }

    @Test
    fun `basal rate rejects impossible inputs`() {
        assertThat(BodyMetrics.basalMetabolicRate(0, 1_800, 30, Sex.MALE)).isNull()
        assertThat(BodyMetrics.basalMetabolicRate(80_000, 0, 30, Sex.MALE)).isNull()
        assertThat(BodyMetrics.basalMetabolicRate(80_000, 1_800, 0, Sex.MALE)).isNull()
    }

    @Test
    fun `katch mcardle works from lean mass alone`() {
        // 370 + 21.6 * 64
        assertThat(BodyMetrics.basalMetabolicRateFromLeanMass(64_000)).isWithin(1e-6).of(1_752.4)
        assertThat(BodyMetrics.basalMetabolicRateFromLeanMass(0)).isNull()
    }

    @Test
    fun `expenditure scales the basal rate by activity`() {
        assertThat(BodyMetrics.totalDailyEnergyExpenditure(2_000.0, ActivityLevel.SEDENTARY))
            .isWithin(1e-6).of(2_400.0)
        assertThat(BodyMetrics.totalDailyEnergyExpenditure(2_000.0, ActivityLevel.VERY_ACTIVE))
            .isWithin(1e-6).of(3_800.0)
    }

    @Test
    fun `navy body fat for men`() {
        val percent = BodyMetrics.navyBodyFatPercent(
            sex = Sex.MALE, heightMm = 1_800, neckMm = 380, waistMm = 900,
        )!!
        assertThat(percent).isWithin(0.15).of(19.81)
    }

    @Test
    fun `navy body fat for women uses the hip measurement`() {
        val percent = BodyMetrics.navyBodyFatPercent(
            sex = Sex.FEMALE, heightMm = 1_650, neckMm = 320, waistMm = 750, hipMm = 950,
        )!!
        assertThat(percent).isWithin(0.15).of(27.43)
    }

    @Test
    fun `navy estimate declines when the waist is not larger than the neck`() {
        assertThat(
            BodyMetrics.navyBodyFatPercent(Sex.MALE, 1_800, neckMm = 400, waistMm = 400),
        ).isNull()
    }

    @Test
    fun `navy estimate for women needs a hip measurement`() {
        assertThat(
            BodyMetrics.navyBodyFatPercent(Sex.FEMALE, 1_650, neckMm = 320, waistMm = 750),
        ).isNull()
    }

    @Test
    fun `a larger waist reads as a higher body fat percentage`() {
        val slimmer = BodyMetrics.navyBodyFatPercent(Sex.MALE, 1_800, 380, 850)!!
        val larger = BodyMetrics.navyBodyFatPercent(Sex.MALE, 1_800, 380, 1_000)!!
        assertThat(larger).isGreaterThan(slimmer)
    }

    @Test
    fun `fat and lean mass split the total`() {
        val fat = BodyMetrics.fatMassGrams(80_000, 20.0)!!
        val lean = BodyMetrics.leanMassGrams(80_000, 20.0)!!
        assertThat(fat).isEqualTo(16_000)
        assertThat(lean).isEqualTo(64_000)
        assertThat(fat + lean).isEqualTo(80_000)
    }

    @Test
    fun `mass split rejects impossible percentages`() {
        assertThat(BodyMetrics.fatMassGrams(80_000, 0.0)).isNull()
        assertThat(BodyMetrics.fatMassGrams(80_000, 100.0)).isNull()
    }

    @Test
    fun `waist to height ratio and its categories`() {
        assertThat(BodyMetrics.waistToHeightRatio(900, 1_800)).isWithin(1e-9).of(0.5)
        assertThat(BodyMetrics.waistToHeightCategory(0.38)).isEqualTo(WaistToHeightCategory.LOW)
        assertThat(BodyMetrics.waistToHeightCategory(0.45)).isEqualTo(WaistToHeightCategory.HEALTHY)
        assertThat(BodyMetrics.waistToHeightCategory(0.50)).isEqualTo(WaistToHeightCategory.INCREASED)
        assertThat(BodyMetrics.waistToHeightCategory(0.65)).isEqualTo(WaistToHeightCategory.HIGH)
    }

    @Test
    fun `calorie target reflects the desired rate of change`() {
        // Losing 100 g a day is 770 kcal a day below maintenance.
        assertThat(BodyMetrics.calorieTargetForRate(2_500.0, -100.0)).isWithin(1e-6).of(1_730.0)
        assertThat(BodyMetrics.calorieTargetForRate(2_500.0, 0.0)).isWithin(1e-6).of(2_500.0)
        assertThat(BodyMetrics.calorieTargetForRate(2_500.0, 50.0)).isWithin(1e-6).of(2_885.0)
    }
}
