package com.weighttrack.core.medication

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MedicationLevelTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000
    private val semaglutide = GlpDrug.SEMAGLUTIDE.halfLifeHours!!

    @Test
    fun `a dose is all there the moment it goes in`() {
        val dose = MedicationLevel.Dose(now, 1.0)

        assertThat(MedicationLevel.at(listOf(dose), now, semaglutide)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `half of it is left after one half-life`() {
        val dose = MedicationLevel.Dose(now, 2.0)

        val later = MedicationLevel.at(
            listOf(dose),
            now + (semaglutide * hour).toLong(),
            semaglutide,
        )

        assertThat(later).isWithin(1e-6).of(1.0)
    }

    @Test
    fun `a dose that has not happened yet counts for nothing`() {
        // Recorded ahead, or a phone with the date set wrong. An unsigned decay would run the
        // exponential backwards and report more of the drug present than was ever injected.
        val future = MedicationLevel.Dose(now + 10 * hour, 5.0)

        assertThat(MedicationLevel.at(listOf(future), now, semaglutide)).isEqualTo(0.0)
    }

    @Test
    fun `weekly doses build up and then settle`() {
        val week = 7 * 24 * hour
        fun troughAfter(injections: Int): Double {
            val doses = (0 until injections).map { MedicationLevel.Dose(now + it * week, 1.0) }
            return MedicationLevel.at(doses, now + injections * week, semaglutide)
        }

        val first = troughAfter(1)
        val fourth = troughAfter(4)
        val twelfth = troughAfter(12)

        // The reason a week feels different at twelve weeks than at four, with the same pen.
        assertThat(fourth).isGreaterThan(first)
        assertThat(twelfth).isGreaterThan(fourth)
        // And it does settle rather than climbing forever.
        assertThat(twelfth - troughAfter(11)).isLessThan(fourth - troughAfter(3))
    }

    @Test
    fun `a missed week shows as a dip`() {
        val week = 7 * 24 * hour
        val kept = (0..4).map { MedicationLevel.Dose(now + it * week, 1.0) }
        val missed = kept.filterIndexed { index, _ -> index != 3 }

        val at = now + 4 * week
        assertThat(MedicationLevel.at(missed, at, semaglutide))
            .isLessThan(MedicationLevel.at(kept, at, semaglutide))
    }

    @Test
    fun `a curve runs from one end of the range to the other`() {
        val doses = listOf(MedicationLevel.Dose(now, 1.0))

        val curve = MedicationLevel.curve(doses, now, now + 7 * 24 * hour, semaglutide, stepHours = 24.0)

        assertThat(curve.first().atUtcMillis).isEqualTo(now)
        assertThat(curve.last().atUtcMillis).isEqualTo(now + 7 * 24 * hour)
        assertThat(curve.map { it.milligrams }).isInOrder(compareByDescending<Double> { it })
    }

    @Test
    fun `a range nobody could draw is still bounded`() {
        val decade = 10L * 365 * 24 * hour

        val curve = MedicationLevel.curve(emptyList(), now, now + decade, semaglutide, stepHours = 0.1)

        assertThat(curve.size).isAtMost(2_001)
    }

    @Test
    fun `a drug with no published half-life draws nothing`() {
        assertThat(GlpDrug.OTHER.halfLifeHours).isNull()
        assertThat(MedicationLevel.at(listOf(MedicationLevel.Dose(now, 1.0)), now, 0.0)).isEqualTo(0.0)
        assertThat(MedicationLevel.curve(emptyList(), now, now + hour, 0.0)).isEmpty()
    }
}
