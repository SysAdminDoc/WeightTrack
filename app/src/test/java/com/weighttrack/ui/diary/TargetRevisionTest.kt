package com.weighttrack.ui.diary

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.nutrition.MacroBasis
import com.weighttrack.core.nutrition.MacroTarget
import org.junit.Test
import java.time.DayOfWeek

class TargetRevisionTest {

    private val saturday = MacroTarget(
        kcal = 2_000.0,
        proteinG = 150.0,
        carbsG = 200.0,
        fatG = 67.0,
        basis = MacroBasis.GRAMS,
    )

    @Test
    fun `a day with its own target keeps the change to itself`() {
        // The long-run Saturday. Writing this into the everyday row would replace the target the
        // other six days were using, and leave Saturday showing exactly what it showed before,
        // so the button would look broken while quietly doing damage elsewhere.
        assertThat(TargetRevision.rowFor(DayOfWeek.SATURDAY, dayHasItsOwn = true))
            .isEqualTo(DayOfWeek.SATURDAY)
    }

    @Test
    fun `a day that is only using the everyday target changes the everyday target`() {
        assertThat(TargetRevision.rowFor(DayOfWeek.SATURDAY, dayHasItsOwn = false)).isNull()
    }

    @Test
    fun `the split keeps its proportions when the calories change`() {
        val revised = TargetRevision.revised(saturday, recommendedKcal = 2_400.0)

        assertThat(revised.kcal).isWithin(1e-9).of(2_400.0)
        // Twenty per cent more of everything, so the parts still add up to the whole.
        assertThat(revised.proteinG!!).isWithin(1e-9).of(180.0)
        assertThat(revised.carbsG!!).isWithin(1e-9).of(240.0)
        assertThat(revised.fatG!!).isWithin(1e-9).of(80.4)
    }

    @Test
    fun `the parts still come to about the whole afterwards`() {
        // The real point of scaling. Protein and carbohydrate are 4 kcal a gram, fat is 9.
        val revised = TargetRevision.revised(saturday, recommendedKcal = 2_400.0)
        val fromParts = revised.proteinG!! * 4 + revised.carbsG!! * 4 + revised.fatG!! * 9

        assertThat(fromParts).isWithin(30.0).of(revised.kcal)
    }

    @Test
    fun `a smaller target scales down rather than staying put`() {
        val revised = TargetRevision.revised(saturday, recommendedKcal = 1_000.0)

        assertThat(revised.proteinG!!).isWithin(1e-9).of(75.0)
        assertThat(revised.fatG!!).isWithin(1e-9).of(33.5)
    }

    @Test
    fun `having no target yet gives a plain one`() {
        val revised = TargetRevision.revised(null, recommendedKcal = 2_400.0)

        assertThat(revised.kcal).isWithin(1e-9).of(2_400.0)
        assertThat(revised.proteinG).isNull()
        assertThat(revised.basis).isEqualTo(MacroBasis.GRAMS)
    }

    @Test
    fun `a target with no calories in it does not divide by nothing`() {
        val empty = MacroTarget(kcal = 0.0, proteinG = 100.0, basis = MacroBasis.PERCENT)
        val revised = TargetRevision.revised(empty, recommendedKcal = 2_400.0)

        assertThat(revised.proteinG!!).isWithin(1e-9).of(100.0)
        // How it is shown is the person's choice and is not something to revise for them.
        assertThat(revised.basis).isEqualTo(MacroBasis.PERCENT)
    }

    @Test
    fun `a target with no split stays without one`() {
        val calories = MacroTarget(kcal = 2_000.0, basis = MacroBasis.GRAMS)
        val revised = TargetRevision.revised(calories, recommendedKcal = 2_400.0)

        assertThat(revised.proteinG).isNull()
        assertThat(revised.carbsG).isNull()
        assertThat(revised.fatG).isNull()
    }
}
