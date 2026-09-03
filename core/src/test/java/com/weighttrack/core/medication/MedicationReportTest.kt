package com.weighttrack.core.medication

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendSeries
import org.junit.Test
import java.time.LocalDate

class MedicationReportTest {

    private val march = LocalDate.of(2026, 3, 1)

    private fun dose(dayOfMonth: Int) = MedicationReport.Dose(
        date = march.withDayOfMonth(dayOfMonth),
        drug = GlpDrug.SEMAGLUTIDE,
        milligrams = 0.5,
        site = InjectionSite.ABDOMEN_LEFT,
    )

    private fun effect(dayOfMonth: Int) = MedicationReport.Effect(
        date = march.withDayOfMonth(dayOfMonth),
        kind = SideEffectKind.NAUSEA,
        severity = SideEffectSeverity.MILD,
    )

    private fun series(days: IntRange) = TrendSeries(
        points = days.map {
            TrendPoint(march.withDayOfMonth(it), 80_000.0 - it * 100, 80_000 - it * 100)
        },
        alpha = 0.1,
    )

    @Test
    fun `the range includes both the day it starts and the day it ends`() {
        val content = MedicationReport.build(
            from = march.withDayOfMonth(5),
            to = march.withDayOfMonth(10),
            doses = listOf(dose(4), dose(5), dose(10), dose(11)),
            effects = emptyList(),
            series = series(1..28),
        )

        assertThat(content.doses.map { it.date.dayOfMonth }).containsExactly(5, 10).inOrder()
        assertThat(content.trend.map { it.date.dayOfMonth }).containsExactly(5, 6, 7, 8, 9, 10).inOrder()
    }

    @Test
    fun `a range picked backwards is read the way it was meant`() {
        val content = MedicationReport.build(
            from = march.withDayOfMonth(10),
            to = march.withDayOfMonth(5),
            doses = listOf(dose(7)),
            effects = listOf(effect(8)),
            series = series(1..28),
        )

        assertThat(content.from).isEqualTo(march.withDayOfMonth(5))
        assertThat(content.doses).hasSize(1)
        assertThat(content.effects).hasSize(1)
    }

    @Test
    fun `side effects and doses share the range and the order`() {
        val content = MedicationReport.build(
            from = march.withDayOfMonth(1),
            to = march.withDayOfMonth(28),
            doses = listOf(dose(15), dose(1), dose(8)),
            effects = listOf(effect(16), effect(2)),
            series = series(1..28),
        )

        assertThat(content.doses.map { it.date.dayOfMonth }).isInOrder()
        assertThat(content.effects.map { it.date.dayOfMonth }).isInOrder()
    }

    @Test
    fun `the weight column is the trend and not the readings`() {
        // Deliberate. The raw line invites a conversation about one heavy Tuesday, and a person
        // is handing this across a desk rather than reading it themselves.
        val jumpy = TrendSeries(
            points = listOf(
                TrendPoint(march.withDayOfMonth(1), 80_000.0, 83_000),
                TrendPoint(march.withDayOfMonth(2), 79_900.0, null),
            ),
            alpha = 0.1,
        )

        val content = MedicationReport.build(march, march.plusDays(1), emptyList(), emptyList(), jumpy)

        assertThat(content.trend.map { it.trendGrams }).containsExactly(80_000, 79_900).inOrder()
    }

    @Test
    fun `a range with nothing in it says so rather than reaching for the rest`() {
        val content = MedicationReport.build(
            from = march.minusYears(1),
            to = march.minusYears(1).plusDays(3),
            doses = listOf(dose(5)),
            effects = listOf(effect(5)),
            series = series(1..28),
        )

        assertThat(content.isEmpty).isTrue()
    }
}
