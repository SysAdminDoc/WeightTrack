package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import kotlin.math.abs

class UnitConverterTest {

    @Test
    fun `kilograms round trip through grams`() {
        assertThat(UnitConverter.kgToGrams(82.45)).isEqualTo(82_450)
        assertThat(UnitConverter.gramsToKg(82_450)).isWithin(1e-9).of(82.45)
    }

    @Test
    fun `pounds use the international avoirdupois definition`() {
        assertThat(UnitConverter.lbToGrams(1.0)).isEqualTo(454) // 453.59237 rounds to 454
        assertThat(UnitConverter.gramsToLb(45_359)).isWithin(0.001).of(100.0)
    }

    @Test
    fun `stones and pounds split correctly`() {
        val grams = UnitConverter.lbToGrams(185.0)
        val (stones, pounds) = UnitConverter.gramsToStoneLb(grams)
        assertThat(stones).isEqualTo(13)
        assertThat(pounds).isWithin(0.01).of(3.0)
    }

    @Test
    fun `stone split never renders fourteen pounds`() {
        // 13.999 lb must carry into the next stone rather than display as "0 st 14.0 lb".
        val grams = UnitConverter.lbToGrams(13.999)
        val (stones, pounds) = UnitConverter.gramsToStoneLb(grams)
        assertThat(stones).isEqualTo(1)
        assertThat(pounds).isWithin(0.01).of(0.0)
    }

    @Test
    fun `stone conversion round trips`() {
        val grams = UnitConverter.stoneLbToGrams(12, 7.5)
        val (stones, pounds) = UnitConverter.gramsToStoneLb(grams)
        assertThat(stones).isEqualTo(12)
        assertThat(pounds).isWithin(0.01).of(7.5)
    }

    @Test
    fun `negative weights keep their sign on the stone component`() {
        val grams = UnitConverter.stoneLbToGrams(2, 0.0)
        val (stones, _) = UnitConverter.gramsToStoneLb(-grams)
        assertThat(stones).isEqualTo(-2)
    }

    @Test
    fun `feet and inches split correctly`() {
        val mm = UnitConverter.feetInchesToMm(5, 11.0)
        val (feet, inches) = UnitConverter.mmToFeetInches(mm)
        assertThat(feet).isEqualTo(5)
        assertThat(inches).isWithin(0.02).of(11.0)
    }

    @Test
    fun `inch split never renders twelve inches`() {
        val mm = UnitConverter.inchesToMm(11.999)
        val (feet, inches) = UnitConverter.mmToFeetInches(mm)
        assertThat(feet).isEqualTo(1)
        assertThat(inches).isWithin(0.01).of(0.0)
    }

    @Test
    fun `centimetres round trip through millimetres`() {
        assertThat(UnitConverter.cmToMm(178.5)).isEqualTo(1785)
        assertThat(UnitConverter.mmToCm(1785)).isWithin(1e-9).of(178.5)
    }

    @Test
    fun `display conversion is symmetric for every weight unit`() {
        val grams = 78_432
        for (unit in WeightUnit.entries) {
            val shown = UnitConverter.gramsToDisplay(grams, unit)
            val back = UnitConverter.displayToGrams(shown, unit)
            // A single rounding at the storage boundary, never more than half a gram of drift.
            assertThat(abs(back - grams)).isAtMost(1)
        }
    }

    @Test
    fun `display conversion is symmetric for every length unit`() {
        val mm = 1_783
        for (unit in LengthUnit.entries) {
            val shown = UnitConverter.mmToDisplay(mm, unit)
            assertThat(abs(UnitConverter.displayToMm(shown, unit) - mm)).isAtMost(1)
        }
    }

    @Test
    fun `snapping rounds to the increment the unit can express`() {
        // 0.05 kg steps
        assertThat(UnitConverter.snapToStep(82_437, WeightUnit.KG)).isEqualTo(82_450)
        assertThat(UnitConverter.snapToStep(82_424, WeightUnit.KG)).isEqualTo(82_400)
        // ~0.1 lb steps
        assertThat(UnitConverter.snapToStep(82_437, WeightUnit.LB)).isEqualTo(82_440)
    }

    @Test
    fun `unit switching does not drift the stored value`() {
        // Switching display units must never rewrite storage, so the gram value is untouched
        // however many times a person flips between kilograms and pounds.
        var grams = 91_170
        repeat(50) {
            grams = UnitConverter.displayToGrams(
                UnitConverter.gramsToDisplay(grams, WeightUnit.LB),
                WeightUnit.LB,
            )
            grams = UnitConverter.displayToGrams(
                UnitConverter.gramsToDisplay(grams, WeightUnit.KG),
                WeightUnit.KG,
            )
        }
        assertThat(grams).isEqualTo(91_170)
    }
}
