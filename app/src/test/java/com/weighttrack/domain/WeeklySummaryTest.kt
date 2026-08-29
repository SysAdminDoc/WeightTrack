package com.weighttrack.domain

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class WeeklySummaryTest {

    private val today: LocalDate = LocalDate.of(2026, 6, 15)

    /** [weighedDays] is how many of the last eight days carried a real reading. */
    private fun series(
        startGrams: Double,
        endGrams: Double,
        weighedDays: Int = 8,
        days: Int = 8,
    ): TrendSeries {
        val step = if (days > 1) (endGrams - startGrams) / (days - 1) else 0.0
        val points = (0 until days).map { index ->
            val date = today.minusDays((days - 1 - index).toLong())
            TrendPoint(
                date = date,
                trendGrams = startGrams + step * index,
                actualGrams = if (index >= days - weighedDays) (startGrams + step * index).toInt() else null,
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
}
