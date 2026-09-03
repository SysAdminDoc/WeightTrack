package com.weighttrack.data.io

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.medication.GlpDrug
import com.weighttrack.core.medication.InjectionSite
import com.weighttrack.core.medication.MedicationReport
import com.weighttrack.core.medication.SideEffectKind
import com.weighttrack.core.medication.SideEffectSeverity
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.AppStrings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The words on the report.
 *
 * `PdfDocument` is native and does not run off a device, so drawing itself is exercised on the
 * emulator rather than here. What is here is the part with decisions in it: which sections there
 * are, whose language they are in, and the promise the whole feature rests on, which is that a
 * page somebody hands across a desk carries their injections and nothing else of theirs.
 */
@RunWith(RobolectricTestRunner::class)
class MedicationPdfTest {

    private val march = LocalDate.of(2026, 3, 1)

    private fun writer() = MedicationPdfWriter(
        ApplicationProvider.getApplicationContext(),
        AppStrings(ApplicationProvider.getApplicationContext()),
    )

    private fun content(
        doses: List<MedicationReport.Dose> = listOf(
            MedicationReport.Dose(march, GlpDrug.SEMAGLUTIDE, 0.5, InjectionSite.ABDOMEN_LEFT),
        ),
        effects: List<MedicationReport.Effect> = listOf(
            MedicationReport.Effect(
                march.plusDays(1),
                SideEffectKind.NAUSEA,
                SideEffectSeverity.MILD,
            ),
        ),
        days: Int = 3,
    ) = MedicationReport.build(
        from = march,
        to = march.plusDays(days.toLong()),
        doses = doses,
        effects = effects,
        series = TrendSeries(
            points = (0..days).map {
                TrendPoint(march.plusDays(it.toLong()), 80_000.0 - it * 50, null)
            },
            alpha = 0.1,
        ),
    )

    private fun text(content: MedicationReport.Content) =
        writer().lines(content, WeightUnit.KG).joinToString("\n") { it.text }

    @Test
    fun `the page names the range, the doses, the side effects and the weight`() {
        val page = text(content())

        assertThat(page).contains("2026-03-01 to 2026-03-04")
        assertThat(page).contains("Semaglutide")
        assertThat(page).contains("Left abdomen")
        assertThat(page).contains("Nausea")
        assertThat(page).contains("80.0 kg")
    }

    @Test
    fun `a dose of half a milligram is not written as nought point five nought`() {
        val page = text(content())

        assertThat(page).contains("0.5 mg")
    }

    @Test
    fun `a whole milligram loses its trailing zero`() {
        val page = text(
            content(
                doses = listOf(
                    MedicationReport.Dose(march, GlpDrug.TIRZEPATIDE, 5.0, InjectionSite.THIGH_LEFT),
                ),
            ),
        )

        assertThat(page).contains("5 mg")
        assertThat(page).doesNotContain("5.0 mg")
    }

    @Test
    fun `an empty section says so rather than being left off`() {
        val page = text(content(doses = emptyList(), effects = emptyList()))

        // A page with no "side effects" heading reads as a page that forgot to ask, which is
        // the opposite of what somebody wants to show a clinician.
        assertThat(page).contains("Side effects")
        assertThat(page).contains("None recorded.")
    }

    @Test
    fun `the weight is written in the unit the person reads`() {
        val pounds = writer().lines(content(), WeightUnit.LB).joinToString("\n") { it.text }

        assertThat(pounds).contains("lb")
        assertThat(pounds).doesNotContain("80.0 kg")
    }

    @Test
    fun `every line comes from the doses, the side effects or the weight`() {
        // The promise the feature rests on. Counted rather than eyeballed, so a section added
        // later without being thought about fails this.
        val subject = content(days = 3)
        val lines = writer().lines(subject, WeightUnit.KG)
            .filter { it.style == MedicationPdfWriter.Style.BODY }

        // One range line, one per dose, one per side effect, one per day of trend.
        assertThat(lines).hasSize(1 + subject.doses.size + subject.effects.size + subject.trend.size)
    }
}
