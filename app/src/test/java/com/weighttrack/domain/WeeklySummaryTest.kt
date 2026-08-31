package com.weighttrack.domain

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.weighttrack.core.math.WeekRule
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class WeeklySummaryTest {

    /** A Monday, so the week being reported is the Monday-to-Sunday one that just finished. */
    private val today: LocalDate = LocalDate.of(2026, 6, 15)
    private val reportedStart: LocalDate = LocalDate.of(2026, 6, 8)

    /**
     * A history covering the week the summary reports, plus the day it is measured from.
     *
     * The trend moves from [startGrams] on the Sunday before the week to [endGrams] on the
     * Sunday that ends it, which is the change the summary is about. [weighedDays] is how many
     * days of that week carried a real reading; days outside it never count towards it.
     */
    private fun series(
        startGrams: Double,
        endGrams: Double,
        weighedDays: Int = 7,
    ): TrendSeries {
        val measuredFrom = reportedStart.minusDays(1)
        val step = (endGrams - startGrams) / 7.0
        val points = (0..8).map { index ->
            val inWeek = index in 1..7
            val trend = startGrams + step * minOf(index, 7)
            TrendPoint(
                date = measuredFrom.plusDays(index.toLong()),
                trendGrams = trend,
                actualGrams = if (inWeek && index > 7 - weighedDays) trend.toInt() else null,
            )
        }
        return TrendSeries(points, 0.1)
    }

    private fun build(
        series: TrendSeries,
        direction: GoalDirection? = GoalDirection.LOSE,
        milestone: Int? = null,
    ) = WeeklySummaryBuilder.build(series, WeightUnit.KG, direction, milestone, today)

    /**
     * What the notification would actually say.
     *
     * Resolved through the real resources, so these assertions are about the English that ships
     * rather than about a sentence built in the test.
     */
    private val context: android.content.Context
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private fun WeeklySummary.headlineText(): String =
        com.weighttrack.notifications.WeeklySummaryText.headline(context, this, WeightUnit.KG)

    private fun WeeklySummary.detailText(): String =
        com.weighttrack.notifications.WeeklySummaryText.detail(context, this, WeightUnit.KG)

    @Test
    fun `a week of losing reports the drop`() {
        val summary = build(series(85_000.0, 84_300.0))!!
        assertThat(summary.headlineText()).contains("Down")
        assertThat(summary.headlineText()).contains("0.7")
        assertThat(summary.detailText()).contains("Heading the right way")
    }

    @Test
    fun `a week of gaining against a loss goal says so plainly`() {
        val summary = build(series(84_000.0, 84_800.0))!!
        assertThat(summary.headlineText()).contains("Up")
        assertThat(summary.detailText()).contains("Not moving toward the goal")
    }

    @Test
    fun `a flat week reads as steady rather than as a failure`() {
        val summary = build(series(84_000.0, 84_050.0))!!
        assertThat(summary.headlineText()).isEqualTo("Steady week")
    }

    @Test
    fun `a milestone takes the headline`() {
        val summary = build(series(85_000.0, 84_300.0), milestone = 84_000)!!
        assertThat(summary.headlineText()).contains("Milestone reached")
        assertThat(summary.headlineText()).contains("84.0")
    }

    @Test
    fun `too few readings means no summary at all`() {
        // Sending "here is your week" off one reading would be inventing a trend, and a
        // notification with nothing to say is what gets an app muted.
        assertThat(build(series(85_000.0, 84_300.0, weighedDays = 1))).isNull()
    }

    @Test
    fun `no readings at all means no summary`() {
        assertThat(build(series(85_000.0, 84_300.0, weighedDays = 0))).isNull()
    }

    @Test
    fun `an empty series produces nothing`() {
        assertThat(build(TrendSeries(emptyList(), 0.1))).isNull()
    }

    @Test
    fun `a gain goal treats gaining as the right direction`() {
        val summary = build(series(84_000.0, 84_800.0), direction = GoalDirection.GAIN)!!
        assertThat(summary.detailText()).contains("Heading the right way")
    }

    @Test
    fun `with no goal the summary does not judge the direction`() {
        val summary = build(series(85_000.0, 84_300.0), direction = null)!!
        assertThat(summary.detailText()).doesNotContain("right way")
        assertThat(summary.detailText()).doesNotContain("Not moving")
    }

    @Test
    fun `the detail counts the readings behind it`() {
        val summary = build(series(85_000.0, 84_300.0, weighedDays = 4))!!
        assertThat(summary.daysWeighed).isEqualTo(4)
        assertThat(summary.detailText()).contains("4 readings")
    }

    @Test
    fun `one reading is described in the singular when it still qualifies`() {
        val summary = build(series(85_000.0, 84_300.0, weighedDays = 2))!!
        assertThat(summary.detailText()).contains("2 readings")
    }

    @Test
    fun `the week reported is the one that finished, whatever day the summary is sent`() {
        val history = series(85_000.0, 84_300.0)

        // Sent on the Monday, and again on the Thursday of the same week. Both describe the
        // week that finished, so the figure does not change under the person between them.
        val monday = WeeklySummaryBuilder.build(
            history,
            WeightUnit.KG,
            GoalDirection.LOSE,
            null,
            today,
            WeekRule.MONDAY,
        )!!
        val thursday = WeeklySummaryBuilder.build(
            history,
            WeightUnit.KG,
            GoalDirection.LOSE,
            null,
            today.plusDays(3),
            WeekRule.MONDAY,
        )!!

        assertThat(thursday.changeGrams).isWithin(1e-6).of(monday.changeGrams)
        assertThat(monday.changeGrams).isWithin(1e-6).of(-700.0)
    }

    @Test
    fun `where the week starts decides which week is reported`() {
        assertThat(WeeklySummaryBuilder.weekStart(today, WeekRule.MONDAY))
            .isEqualTo(LocalDate.of(2026, 6, 8))
        // Under a Sunday rule, Monday the fifteenth is the second day of a week in progress, so
        // the week that finished began on Sunday the seventh.
        assertThat(WeeklySummaryBuilder.weekStart(today, WeekRule.SUNDAY))
            .isEqualTo(LocalDate.of(2026, 6, 7))
    }

    @Test
    fun `a summary sent on the last day of a week reports that week`() {
        // Sunday the fourteenth is the end of the Monday week, which has therefore finished.
        assertThat(WeeklySummaryBuilder.weekStart(LocalDate.of(2026, 6, 14), WeekRule.MONDAY))
            .isEqualTo(LocalDate.of(2026, 6, 8))
    }
}