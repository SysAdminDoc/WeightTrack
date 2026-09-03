package com.weighttrack.widget

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.math.WeekRule
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The promise the glance mode makes, on the surface it makes it about.
 *
 * Somebody standing next to a person can read their home screen as easily as they can, and the
 * whole point of the mode is that there is no weight on it to read. That is a claim about text,
 * so this reads every word the widget can put up rather than the fields behind them.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetGlanceModeTest {

    private val today = LocalDate.of(2026, 8, 29)

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    /**
     * Thirty days of trend. [latestActualGrams] null means nobody has weighed at all, which is
     * the only way the series has no measured day: a day with no reading is skipped rather than
     * counted, so one gap at the end still leaves yesterday to compare against.
     */
    private fun series(latestActualGrams: Int?) = TrendSeries(
        points = (0 until 30).map { day ->
            val date = today.minusDays((29 - day).toLong())
            TrendPoint(
                date = date,
                trendGrams = 84_000.0 - day * 40,
                actualGrams = when {
                    latestActualGrams == null -> null
                    day == 29 -> latestActualGrams
                    else -> 84_000 - day * 40
                },
            )
        },
        alpha = 0.1,
    )

    private fun data(
        glanceOnly: Boolean,
        latestActualGrams: Int? = 83_500,
        appLockEnabled: Boolean = false,
    ) = buildWidgetData(
        appLockEnabled = appLockEnabled,
        unit = WeightUnit.KG,
        rule = WeekRule.MONDAY,
        series = series(latestActualGrams),
        today = today,
        glanceOnly = glanceOnly,
    )

    /** Any run of digits that could be read as a body weight, in kilograms or in pounds. */
    private fun weightsIn(text: String): List<String> =
        Regex("""\d+(?:[.,]\d+)?""").findAll(text)
            .map { it.value }
            .filter { (it.replace(',', '.').toDoubleOrNull() ?: 0.0) >= 20.0 }
            .toList()

    @Test
    fun `the ordinary widget shows the trend weight`() {
        val lines = widgetLines(context, data(glanceOnly = false))

        assertThat(lines.value).isEqualTo("82.8")
        assertThat(lines.unit).isEqualTo("kg")
    }

    @Test
    fun `the glance widget prints no weight anywhere on it`() {
        val lines = widgetLines(context, data(glanceOnly = true))

        assertThat(lines.all.flatMap { weightsIn(it) }).isEmpty()
    }

    @Test
    fun `the glance widget says which way and by how much`() {
        // Trend 82.84 kg, this morning 83.5, so the reading is above the line.
        val lines = widgetLines(context, data(glanceOnly = true))

        assertThat(lines.value).startsWith("▲")
        assertThat(lines.change).isEqualTo("above your trend")
        assertThat(lines.unit).isEqualTo("kg")
    }

    @Test
    fun `a morning under the line points down`() {
        val lines = widgetLines(context, data(glanceOnly = true, latestActualGrams = 82_000))

        assertThat(lines.value).startsWith("▼")
        assertThat(lines.change).isEqualTo("below your trend")
        assertThat(lines.all.flatMap { weightsIn(it) }).isEmpty()
    }

    @Test
    fun `nothing weighed yet leaves nothing to compare`() {
        val lines = widgetLines(context, data(glanceOnly = true, latestActualGrams = null))

        assertThat(lines.value).isEqualTo("Tap to log")
        assertThat(lines.all.flatMap { weightsIn(it) }).isEmpty()
    }

    @Test
    fun `the app lock still wins over the glance mode`() {
        val lines = widgetLines(context, data(glanceOnly = true, appLockEnabled = true))

        assertThat(lines.value).isEqualTo("Locked")
        assertThat(lines.all.flatMap { weightsIn(it) }).isEmpty()
    }

    @Test
    fun `the absolute figures are gone from the data, not only from the drawing`() {
        // Left in and hidden at the last moment, one branch further on puts a weight back on
        // somebody's home screen. They are not carried at all.
        val glance = data(glanceOnly = true)

        assertThat(glance.trendGrams).isNull()
        assertThat(glance.weekChangeGrams).isNull()
        assertThat(glance.aboveTrendGrams).isNotNull()
    }
}
