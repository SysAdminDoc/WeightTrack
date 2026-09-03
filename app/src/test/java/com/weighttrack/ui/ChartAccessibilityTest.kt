package com.weighttrack.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.TrendChart
import com.weighttrack.ui.components.TrendChartColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * What a screen reader is told about the chart.
 *
 * A `Canvas` is a single node with nothing inside it, so before this the main thing on the Charts
 * screen announced nothing at all: somebody using TalkBack was told there was a picture and
 * nothing about what it showed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun series(): TrendSeries {
        val start = LocalDate.of(2026, 8, 1)
        val points = (0 until 20).map { day ->
            val grams = 84_000.0 - day * 50
            TrendPoint(
                date = start.plusDays(day.toLong()),
                trendGrams = grams,
                actualGrams = grams.toInt(),
            )
        }
        return TrendSeries(points, 0.1)
    }

    private fun content() {
        compose.setContent {
            MaterialTheme {
                TrendChart(
                    series = series(),
                    unit = WeightUnit.KG,
                    colors = TrendChartColors(
                        trendLine = Color.Green,
                        rawPoint = Color.Gray,
                        fill = Color.Green,
                        grid = Color.DarkGray,
                        axisText = Color.White,
                        goalLine = Color.Blue,
                        milestone = Color.Yellow,
                        marker = Color.White,
                        markerSurface = Color.Black,
                        markerText = Color.White,
                        waterBand = Color.Magenta,
                    ),
                )
            }
        }
    }

    @Test
    fun `the chart tells a screen reader what it shows`() {
        content()

        val tree = compose.onRoot().printToString(maxDepth = 20)
        assertThat(tree).contains("ContentDescription")
        assertThat(tree).contains("Weight trend chart")
    }

    @Test
    fun `the description carries the figures somebody would want read out`() {
        content()

        val tree = compose.onRoot().printToString(maxDepth = 20)
        // The latest trend and the range it covers: what a person would say if asked to
        // describe the picture in one sentence.
        assertThat(tree).contains("Latest trend")
        assertThat(tree).contains("kg")
        assertThat(tree).contains("20 days shown")
    }

    @Test
    fun `the described node is findable, which is what a reader walks`() {
        content()

        compose.onNodeWithContentDescription("Weight trend chart", substring = true)
            .assertExists()
    }
}
