package com.weighttrack.wear

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class WearWeightPickerTest {

    @Test
    fun `a kilogram step is five hundredths, exactly`() {
        val steps = WearWeightPicker.stepsFor(82_500, WeightUnit.KG)

        assertThat(WearWeightPicker.gramsFor(steps, WeightUnit.KG)).isEqualTo(82_500)
        assertThat(WearWeightPicker.gramsFor(steps + 1, WeightUnit.KG)).isEqualTo(82_550)
        assertThat(WearWeightPicker.gramsFor(steps - 1, WeightUnit.KG)).isEqualTo(82_450)
    }

    @Test
    fun `winding the crown a long way in pounds does not drift`() {
        // The whole reason the picker counts steps instead of adding grams: 0.1 lb rounded to
        // 45 g is short by a third of a gram a turn, which is most of a pound over 1,000 turns.
        val unit = WeightUnit.LB
        val start = WearWeightPicker.stepsFor(82_000, unit)
        val startLb = WearWeightPicker.gramsFor(start, unit) / 453.59237

        val after = WearWeightPicker.gramsFor(start + 1_000, unit) / 453.59237

        // Only the two endpoints round to whole grams, so the error stays under a gram no
        // matter how far the crown is wound.
        assertThat(after - startLb).isWithin(0.005).of(100.0)

        // What a picker that added grams each turn would land on, for contrast.
        val naive = (WearWeightPicker.gramsFor(start, unit) + 1_000 * UnitConverter.stepGrams(unit)) /
            453.59237
        assertThat(abs(naive - startLb - 100.0)).isGreaterThan(0.5)
    }

    @Test
    fun `the crown stops at a weight a person could be`() {
        val unit = WeightUnit.KG

        val tooLow = WearWeightPicker.clamp(-100_000, unit)
        val tooHigh = WearWeightPicker.clamp(100_000, unit)

        assertThat(WearWeightPicker.gramsFor(tooLow, unit)).isEqualTo(WearWeightPicker.MIN_GRAMS)
        assertThat(WearWeightPicker.gramsFor(tooHigh, unit)).isEqualTo(WearWeightPicker.MAX_GRAMS)
    }

    @Test
    fun `every step changes the number on screen`() {
        // A 0.05 kg step shown to one decimal would sit still for every other turn.
        WeightUnit.entries.forEach { unit ->
            val steps = WearWeightPicker.stepsFor(82_000, unit)
            assertThat(WearWeightPicker.label(steps, unit))
                .isNotEqualTo(WearWeightPicker.label(steps + 1, unit))
        }
    }

    @Test
    fun `a stored weight opens the picker within half a step of itself`() {
        WeightUnit.entries.forEach { unit ->
            listOf(20_000, 54_321, 82_500, 99_999, 180_000).forEach { grams ->
                val reopened = WearWeightPicker.gramsFor(WearWeightPicker.stepsFor(grams, unit), unit)
                assertThat(abs(reopened - grams)).isLessThan(30)
            }
        }
    }

    @Test
    fun `staleness reads the way a person would say it`() {
        val today = LocalDate.of(2026, 8, 29)
        fun at(daysAgo: Long) = staleness(today.minusDays(daysAgo).toEpochDay(), today)

        assertThat(at(0)).isEqualTo("logged today")
        assertThat(at(1)).isEqualTo("logged yesterday")
        assertThat(at(3)).isEqualTo("logged 3 days ago")
        assertThat(at(9)).isEqualTo("logged last week")
        assertThat(at(30)).isEqualTo("logged 4 weeks ago")
        // A phone whose clock is behind the watch must not produce "logged -1 days ago".
        assertThat(at(-2)).isEqualTo("logged today")
    }
}
