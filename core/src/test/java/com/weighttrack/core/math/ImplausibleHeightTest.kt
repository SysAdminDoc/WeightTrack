package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.HeightPlausibility
import com.weighttrack.core.model.Sex
import org.junit.Test

/**
 * What the body figures do when the height cannot be a person's.
 *
 * Found on a real phone, not reasoned about: it read BMI 4805.7, called its owner obese class 3,
 * and offered a healthy weight range of 0.9 to 1.3 lb. The stored height was 152 mm, which is
 * what six foot becomes when the 6 is typed into a field labelled inches. The only guard was
 * "greater than zero", so the impossible number went through and every figure derived from
 * height was wrong from then on, with nothing on any screen suggesting so.
 *
 * Saying nothing is the right answer here. A figure the app cannot stand behind is worse than a
 * blank, because somebody acts on it.
 */
class ImplausibleHeightTest {

    /** Six foot, typed into the inches field. This is the exact value that was found. */
    private val sixFootAsInches = 152

    private val elevenStone = 70_000

    @Test
    fun `the height that was actually found is refused`() {
        assertThat(HeightPlausibility.isPlausible(sixFootAsInches)).isFalse()
        assertThat(HeightPlausibility.orNull(sixFootAsInches)).isNull()
    }

    @Test
    fun `no BMI is offered for a height nobody has`() {
        assertThat(BodyMetrics.bmi(elevenStone, sixFootAsInches)).isNull()
    }

    @Test
    fun `no healthy range is offered for a height nobody has`() {
        // This is the one that printed "0.9 to 1.3 lb" at somebody.
        assertThat(BodyMetrics.healthyWeightRangeGrams(sixFootAsInches)).isNull()
    }

    @Test
    fun `no resting burn is offered for a height nobody has`() {
        assertThat(
            BodyMetrics.basalMetabolicRate(elevenStone, sixFootAsInches, 40, Sex.MALE),
        ).isNull()
    }

    @Test
    fun `a real height still answers`() {
        // The positive control. Without it every test above would pass with the maths
        // returning null for everybody.
        val sixFoot = 1_829

        assertThat(HeightPlausibility.isPlausible(sixFoot)).isTrue()
        assertThat(BodyMetrics.bmi(elevenStone, sixFoot)).isNotNull()
        assertThat(BodyMetrics.healthyWeightRangeGrams(sixFoot)).isNotNull()
        assertThat(BodyMetrics.basalMetabolicRate(elevenStone, sixFoot, 40, Sex.MALE)).isNotNull()
    }

    @Test
    fun `the bounds admit the shortest and tallest people there have been`() {
        // Not an opinion about who may use a weight tracker: the record holders are about
        // 55 cm and 272 cm, and both sit inside.
        assertThat(HeightPlausibility.isPlausible(550)).isTrue()
        assertThat(HeightPlausibility.isPlausible(2_720)).isTrue()
        assertThat(HeightPlausibility.isPlausible(HeightPlausibility.MIN_MM)).isTrue()
        assertThat(HeightPlausibility.isPlausible(HeightPlausibility.MAX_MM)).isTrue()
        assertThat(HeightPlausibility.isPlausible(HeightPlausibility.MIN_MM - 1)).isFalse()
        assertThat(HeightPlausibility.isPlausible(HeightPlausibility.MAX_MM + 1)).isFalse()
    }

    @Test
    fun `an unset height is still simply unset`() {
        assertThat(BodyMetrics.bmi(elevenStone, 0)).isNull()
        assertThat(HeightPlausibility.orNull(0)).isNull()
    }
}
