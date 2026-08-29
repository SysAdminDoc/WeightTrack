package com.weighttrack.core.nutrition

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek

class MacroTargetTest {

    private val target = MacroTarget(kcal = 2_000.0, proteinG = 150.0, carbsG = 200.0, fatG = 61.0)

    @Test
    fun `a split is shown as a share of the day`() {
        assertThat(target.proteinPercent!!).isWithin(0.1).of(30.0)
        assertThat(target.carbsPercent!!).isWithin(0.1).of(40.0)
        assertThat(target.fatPercent!!).isWithin(0.5).of(27.45)
        assertThat(target.macroPercent!!).isWithin(1.0).of(97.5)
    }

    @Test
    fun `a share of the day converts to grams and back`() {
        val protein = MacroTarget.gramsFromPercent(2_000.0, 30.0, MacroTarget.KCAL_PER_GRAM_PROTEIN)!!
        val fat = MacroTarget.gramsFromPercent(2_000.0, 30.0, MacroTarget.KCAL_PER_GRAM_FAT)!!

        assertThat(protein).isWithin(1e-9).of(150.0)
        // Fat carries more than twice the energy, so the same share is far fewer grams.
        assertThat(fat).isWithin(1e-9).of(2_000.0 * 0.3 / 9.0)
        assertThat(MacroTarget(kcal = 2_000.0, proteinG = protein).proteinPercent!!)
            .isWithin(1e-9).of(30.0)
    }

    @Test
    fun `a target with no calories cannot be split into shares`() {
        val none = MacroTarget(kcal = 0.0, proteinG = 150.0)

        assertThat(none.proteinPercent).isNull()
        assertThat(none.macroPercent).isNull()
        assertThat(MacroTarget.gramsFromPercent(0.0, 30.0, 4.0)).isNull()
    }

    @Test
    fun `setting two macros decides the third`() {
        // Somebody who sets protein and fat has decided the carbohydrate too, whether or not
        // they say so, and making them work it out is arithmetic the app can do.
        val carbs = MacroTarget.completeCarbs(2_000.0, proteinG = 150.0, fatG = 60.0)!!

        assertThat(carbs).isWithin(1e-9).of((2_000.0 - 150 * 4 - 60 * 9) / 4.0)
        assertThat(MacroTarget(kcal = 2_000.0, proteinG = 150.0, carbsG = carbs, fatG = 60.0).macroPercent!!)
            .isWithin(1e-6).of(100.0)
    }

    @Test
    fun `a split that already uses the whole day leaves no carbohydrate rather than a negative`() {
        val carbs = MacroTarget.completeCarbs(1_000.0, proteinG = 150.0, fatG = 60.0)!!

        assertThat(carbs).isEqualTo(0.0)
    }

    @Test
    fun `what is left of the day is the number people look at`() {
        val eaten = Nutrients(kcal = 1_200.0, proteinG = 90.0, carbsG = 120.0, fatG = 40.0)

        val left = target.remaining(eaten)

        assertThat(left.kcalRounded).isEqualTo(800)
        assertThat(left.proteinG!!).isWithin(1e-9).of(60.0)
        assertThat(left.isOver).isFalse()
    }

    @Test
    fun `going over is a fact, not a telling-off`() {
        val left = target.remaining(Nutrients(kcal = 2_400.0))

        assertThat(left.isOver).isTrue()
        assertThat(left.kcalRounded).isEqualTo(-400)
    }

    @Test
    fun `a macro nobody recorded counts as nothing eaten, not as unknown left`() {
        // A day with one quick-add in it has no protein figure. What is left of the protein
        // target is still the whole of it.
        val left = target.remaining(Nutrients(kcal = 650.0))

        assertThat(left.proteinG!!).isWithin(1e-9).of(150.0)
        assertThat(left.kcalRounded).isEqualTo(1_350)
    }

    @Test
    fun `a day with its own target uses it, and the rest fall back`() {
        // Eating the same on a rest day as on a long run is what per-day targets exist for.
        val heavier = target.copy(kcal = 2_600.0, day = DayOfWeek.SATURDAY)
        val targets = MacroTargets(default = target, byDay = mapOf(DayOfWeek.SATURDAY to heavier))

        assertThat(targets.forDay(DayOfWeek.SATURDAY)!!.kcal).isWithin(1e-9).of(2_600.0)
        assertThat(targets.forDay(DayOfWeek.TUESDAY)!!.kcal).isWithin(1e-9).of(2_000.0)
        assertThat(targets.hasAny).isTrue()
    }

    @Test
    fun `no target at all is a state the screens can read`() {
        assertThat(MacroTargets.NONE.hasAny).isFalse()
        assertThat(MacroTargets.NONE.forDay(DayOfWeek.MONDAY)).isNull()
    }
}
