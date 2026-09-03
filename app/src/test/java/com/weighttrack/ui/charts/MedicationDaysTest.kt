package com.weighttrack.ui.charts

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.medication.GlpDrug
import com.weighttrack.core.medication.InjectionSite
import com.weighttrack.core.medication.SideEffectKind
import com.weighttrack.core.medication.SideEffectSeverity
import com.weighttrack.data.repo.MedicationDose
import com.weighttrack.data.repo.SideEffect
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * What the chart is allowed to mark.
 *
 * The promise is that somebody who has never turned the injection log on sees exactly the chart
 * they always saw. A toggle that only hides a settings row would leave the marks on the chart of
 * anybody who tried it once and switched it off.
 */
class MedicationDaysTest {

    private val day = LocalDate.of(2026, 8, 29)
    private val at = Instant.parse("2026-08-29T08:00:00Z")

    private val dose = MedicationDose(
        id = 1,
        timestamp = at,
        localDate = day,
        drug = GlpDrug.SEMAGLUTIDE,
        milligrams = 0.5,
        site = InjectionSite.ABDOMEN_LEFT,
        note = null,
    )

    private val effect = SideEffect(
        id = 1,
        timestamp = at.plusSeconds(3_600),
        localDate = day,
        kind = SideEffectKind.NAUSEA,
        severity = SideEffectSeverity.MILD,
        note = null,
    )

    @Test
    fun `nothing is marked while the log is off`() {
        val days = medicationDaysFrom(enabled = false, doses = listOf(dose), effects = listOf(effect))

        assertThat(days.isEmpty).isTrue()
    }

    @Test
    fun `a dose and something felt land on the same day`() {
        val days = medicationDaysFrom(enabled = true, doses = listOf(dose), effects = listOf(effect))

        assertThat(days.doses).containsExactly(day)
        assertThat(days.sideEffects).containsExactly(day)
    }

    @Test
    fun `a side effect on its own is still marked`() {
        // They do not have to arrive together: the worst day of a week is rarely the day of the
        // injection, and a mark that only appeared beside a dose would hide exactly that.
        val days = medicationDaysFrom(
            enabled = true,
            doses = emptyList(),
            effects = listOf(effect.copy(localDate = day.plusDays(3))),
        )

        assertThat(days.doses).isEmpty()
        assertThat(days.sideEffects).containsExactly(day.plusDays(3))
    }
}
