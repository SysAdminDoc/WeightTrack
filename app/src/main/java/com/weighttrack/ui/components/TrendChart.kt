package com.weighttrack.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.core.format.WeightFormatter
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class ChartRange(val label: String, val days: Int?) {
    WEEK("1W", 7),
    MONTH("1M", 30),
    QUARTER("3M", 90),
    HALF_YEAR("6M", 180),
    YEAR("1Y", 365),
    ALL("All", null),
}

/** Colours the chart needs, passed in so the drawing code stays free of theme lookups. */
data class TrendChartColors(
    val trendLine: Color,
    val rawPoint: Color,
    val fill: Color,
    val grid: Color,
    val axisText: Color,
    val goalLine: Color,
    val milestone: Color,
    val marker: Color,
    val markerSurface: Color,
    val markerText: Color,
)

/**
 * The trend chart.
 *
 * Raw readings sit faded behind a bold smoothed line, because the noise is the thing people
 * need to stop reacting to and the line is the thing that is actually moving. Drawn directly
 * rather than through a chart library so the two layers, the goal line and the milestone marks
 * can share one coordinate space exactly.
 */
@Composable
fun TrendChart(
    series: TrendSeries,
    unit: WeightUnit,
    colors: TrendChartColors,
    modifier: Modifier = Modifier,
    range: ChartRange = ChartRange.MONTH,
    goalGrams: Int? = null,
    milestoneGrams: List<Int> = emptyList(),
    showRawReadings: Boolean = true,
    height: Dp = 240.dp,
    today: LocalDate = LocalDate.now(),
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Pan and zoom are expressed in days so they survive a rotation or a range change.
    var zoom by remember(range) { mutableFloatStateOf(1f) }
    var panDays by remember(range) { mutableFloatStateOf(0f) }
    var selected by remember(range) { mutableStateOf<Int?>(null) }

    val allPoints = series.points
    if (allPoints.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height))
        return
    }

    val fullSpanDays = ChronoUnit.DAYS.between(allPoints.first().date, allPoints.last().date)
        .toInt()
        .coerceAtLeast(1)
    val baseWindowDays = (range.days ?: (fullSpanDays + 1)).coerceAtMost(fullSpanDays + 1)
    val windowDays = (baseWindowDays / zoom).roundToInt().coerceIn(2, fullSpanDays + 1)

    // The window ends at the newest reading and pans backwards from there.
    val maxPan = (fullSpanDays + 1 - windowDays).coerceAtLeast(0).toFloat()
    val clampedPan = panDays.coerceIn(0f, maxPan)
    val windowEnd = allPoints.last().date.minusDays(clampedPan.roundToLong())
    val windowStart = windowEnd.minusDays((windowDays - 1).toLong())

    val visible = allPoints.filter { !it.date.isBefore(windowStart) && !it.date.isAfter(windowEnd) }
    if (visible.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height))
        return
    }

    val bounds = remember(visible, goalGrams) { valueBounds(visible, goalGrams) }
    val leftGutter = with(density) { 44.dp.toPx() }
    val bottomGutter = with(density) { 22.dp.toPx() }
    val topPadding = with(density) { 12.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(range, fullSpanDays) {
                detectTransformGestures { _, _, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(1f, 8f)
                }
            }
            .pointerInput(range, windowDays) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    // Dragging right walks backwards in time, the way a scrollable list does.
                    val daysPerPixel = windowDays.toFloat() / size.width.coerceAtLeast(1)
                    panDays = (panDays + dragAmount * daysPerPixel).coerceIn(0f, maxPan)
                }
            }
            .pointerInput(visible) {
                detectTapGestures(
                    onTap = { offset ->
                        val plot = Rect(
                            left = leftGutter,
                            top = topPadding,
                            right = size.width.toFloat(),
                            bottom = size.height - bottomGutter,
                        )
                        selected = nearestIndex(offset, visible, plot, windowStart, windowDays)
                            .takeIf { it != selected }
                    },
                )
            },
    ) {
        val plot = Rect(
            left = leftGutter,
            top = topPadding,
            right = size.width,
            bottom = size.height - bottomGutter,
        )
        if (plot.width <= 0f || plot.height <= 0f) return@Canvas

        fun xFor(date: LocalDate): Float {
            val dayOffset = ChronoUnit.DAYS.between(windowStart, date).toFloat()
            val denominator = (windowDays - 1).coerceAtLeast(1).toFloat()
            return plot.left + (dayOffset / denominator) * plot.width
        }

        fun yFor(grams: Double): Float {
            val span = (bounds.endInclusive - bounds.start).coerceAtLeast(1.0)
            return plot.bottom - ((grams - bounds.start) / span * plot.height).toFloat()
        }

        drawGrid(bounds, unit, plot, colors, textMeasurer, ::yFor)

        goalGrams?.let { goal ->
            val goalDouble = goal.toDouble()
            if (goalDouble in bounds) {
                drawLine(
                    color = colors.goalLine,
                    start = Offset(plot.left, yFor(goalDouble)),
                    end = Offset(plot.right, yFor(goalDouble)),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                )
            }
        }

        milestoneGrams.forEach { milestone ->
            val value = milestone.toDouble()
            if (value in bounds) {
                drawLine(
                    color = colors.milestone,
                    start = Offset(plot.left, yFor(value)),
                    end = Offset(plot.right, yFor(value)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 9f)),
                )
            }
        }

        // The trend stays unfilled so raw readings and reference lines remain crisp.
        val trendPath = Path()
        visible.forEachIndexed { index, point ->
            val x = xFor(point.date)
            val y = yFor(point.trendGrams)
            if (index == 0) {
                trendPath.moveTo(x, y)
            } else {
                trendPath.lineTo(x, y)
            }
        }

        if (showRawReadings) {
            visible.forEach { point ->
                point.actualGrams?.let { grams ->
                    drawCircle(
                        color = colors.rawPoint,
                        radius = 2.5.dp.toPx(),
                        center = Offset(xFor(point.date), yFor(grams.toDouble())),
                    )
                }
            }
        }

        drawPath(
            path = trendPath,
            color = colors.trendLine,
            style = Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )

        // The newest trend value gets a dot so the eye lands on "where you are now".
        visible.lastOrNull()?.let { last ->
            val center = Offset(xFor(last.date), yFor(last.trendGrams))
            drawCircle(colors.trendLine.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = center)
            drawCircle(colors.trendLine, radius = 3.5.dp.toPx(), center = center)
        }

        drawDateAxis(visible, plot, colors, textMeasurer, today, ::xFor)

        selected?.let { index ->
            visible.getOrNull(index)?.let { point ->
                drawMarker(point, unit, plot, colors, textMeasurer, xFor(point.date), yFor(point.trendGrams), today)
            }
        }
    }
}

/** How far the goal line may stretch the visible range before it is left off the chart. */
internal const val MAX_GOAL_RANGE_GROWTH = 1.6

internal fun valueBounds(points: List<TrendPoint>, goalGrams: Int?): ClosedFloatingPointRange<Double> {
    var minimum = Double.MAX_VALUE
    var maximum = -Double.MAX_VALUE
    points.forEach { point ->
        minimum = min(minimum, point.trendGrams)
        maximum = max(maximum, point.trendGrams)
        point.actualGrams?.let {
            minimum = min(minimum, it.toDouble())
            maximum = max(maximum, it.toDouble())
        }
    }
    if (minimum > maximum) return 0.0..1.0

    // The goal line is worth showing while it is within reach, and actively harmful once it
    // is not: a target 6 kg below a 4 kg spread of readings triples the axis and squashes the
    // trend into the top third of the chart, which is the detail people came to look at.
    goalGrams?.let { goal ->
        val dataSpan = (maximum - minimum).coerceAtLeast(1000.0)
        val withGoal = max(maximum, goal.toDouble()) - min(minimum, goal.toDouble())
        if (withGoal <= dataSpan * MAX_GOAL_RANGE_GROWTH) {
            minimum = min(minimum, goal.toDouble())
            maximum = max(maximum, goal.toDouble())
        }
    }

    // A flat stretch would otherwise collapse to a zero-height band.
    val padding = ((maximum - minimum) * 0.12).coerceAtLeast(300.0)
    return (minimum - padding)..(maximum + padding)
}

private fun DrawScope.drawGrid(
    bounds: ClosedFloatingPointRange<Double>,
    unit: WeightUnit,
    plot: Rect,
    colors: TrendChartColors,
    textMeasurer: TextMeasurer,
    yFor: (Double) -> Float,
) {
    val labelStyle = TextStyle(color = colors.axisText, fontSize = 10.sp)
    val ticks = gridValues(bounds, unit)
    ticks.forEach { value ->
        val y = yFor(value)
        if (y < plot.top - 1 || y > plot.bottom + 1) return@forEach
        drawLine(
            color = colors.grid,
            start = Offset(plot.left, y),
            end = Offset(plot.right, y),
            strokeWidth = 1f,
        )
        val label = WeightFormatter.value(value.roundToInt(), unit, decimals = if (unit == WeightUnit.ST_LB) 0 else 1)
        val measured = textMeasurer.measure(label, labelStyle)
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                x = (plot.left - measured.size.width - 6f).coerceAtLeast(0f),
                y = y - measured.size.height / 2f,
            ),
        )
    }
}

/**
 * Grid lines land on round numbers in the unit being displayed, so the labels read as
 * "82.0, 82.5, 83.0" rather than whatever the data range happened to produce.
 */
private fun gridValues(bounds: ClosedFloatingPointRange<Double>, unit: WeightUnit): List<Double> {
    val span = bounds.endInclusive - bounds.start
    if (span <= 0) return emptyList()
    val gramsPerDisplayUnit = when (unit) {
        WeightUnit.KG -> UnitConverter.GRAMS_PER_KG
        WeightUnit.LB, WeightUnit.ST_LB -> UnitConverter.GRAMS_PER_LB
    }
    val targetLines = 5
    val rawStep = span / targetLines / gramsPerDisplayUnit
    // Every candidate is a whole number of tenths, so the labels stay evenly spaced once they
    // are rounded to one decimal. A 0.25 step renders as 84.0, 84.3, 84.5 and looks broken.
    val niceStep = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
        .firstOrNull { it >= rawStep } ?: 200.0
    val stepGrams = niceStep * gramsPerDisplayUnit
    val first = ceil(bounds.start / stepGrams) * stepGrams
    val values = ArrayList<Double>()
    var value = first
    while (value <= bounds.endInclusive && values.size < 12) {
        values += value
        value += stepGrams
    }
    return values
}

private fun DrawScope.drawDateAxis(
    visible: List<TrendPoint>,
    plot: Rect,
    colors: TrendChartColors,
    textMeasurer: TextMeasurer,
    today: LocalDate,
    xFor: (LocalDate) -> Float,
) {
    val labelStyle = TextStyle(color = colors.axisText, fontSize = 10.sp)
    val candidates = listOfNotNull(
        visible.firstOrNull()?.date,
        visible.getOrNull(visible.size / 2)?.date,
        visible.lastOrNull()?.date,
    ).distinct()

    candidates.forEachIndexed { index, date ->
        val label = DateFormatters.shortDate(date, today)
        val measured = textMeasurer.measure(label, labelStyle)
        val centre = xFor(date)
        val x = when (index) {
            0 -> centre
            candidates.lastIndex -> centre - measured.size.width
            else -> centre - measured.size.width / 2f
        }
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                x = x.coerceIn(plot.left, plot.right - measured.size.width),
                y = plot.bottom + 4f,
            ),
        )
    }
}

private fun DrawScope.drawMarker(
    point: TrendPoint,
    unit: WeightUnit,
    plot: Rect,
    colors: TrendChartColors,
    textMeasurer: TextMeasurer,
    x: Float,
    y: Float,
    today: LocalDate,
) {
    drawLine(
        color = colors.marker,
        start = Offset(x, plot.top),
        end = Offset(x, plot.bottom),
        strokeWidth = 1.dp.toPx(),
    )
    drawCircle(colors.trendLine, radius = 4.dp.toPx(), center = Offset(x, y))

    val trendText = "Trend ${WeightFormatter.full(point.trendGrams.roundToInt(), unit)}"
    val readingText = point.actualGrams?.let { "Weighed ${WeightFormatter.full(it, unit)}" }
    val lines = listOfNotNull(DateFormatters.shortDate(point.date, today), trendText, readingText)
    val style = TextStyle(color = colors.markerText, fontSize = 11.sp)
    val measured = lines.map { textMeasurer.measure(it, style) }

    val boxWidth = measured.maxOf { it.size.width }.toFloat() + 20f
    val boxHeight = measured.sumOf { it.size.height }.toFloat() + 16f
    val boxLeft = (x + 12f).coerceAtMost(plot.right - boxWidth).coerceAtLeast(plot.left)
    val boxTop = plot.top + 4f

    drawRoundRect(
        color = colors.markerSurface,
        topLeft = Offset(boxLeft, boxTop),
        size = Size(boxWidth, boxHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
    )
    var textY = boxTop + 8f
    measured.forEach { layout ->
        drawText(textLayoutResult = layout, topLeft = Offset(boxLeft + 10f, textY))
        textY += layout.size.height
    }
}

private fun nearestIndex(
    tap: Offset,
    visible: List<TrendPoint>,
    plot: Rect,
    windowStart: LocalDate,
    windowDays: Int,
): Int? {
    if (visible.isEmpty() || plot.width <= 0f) return null
    val relative = ((tap.x - plot.left) / plot.width).coerceIn(0f, 1f)
    val dayOffset = (relative * (windowDays - 1)).roundToInt()
    val target = windowStart.plusDays(dayOffset.toLong())
    return visible.indices.minByOrNull {
        abs(ChronoUnit.DAYS.between(visible[it].date, target))
    }
}

/**
 * A compact line with no axes or labels, for cards and the home screen widget.
 */
@Composable
fun Sparkline(
    series: TrendSeries,
    color: Color,
    modifier: Modifier = Modifier,
    days: Int = 30,
) {
    val points = remember(series, days) { series.points.takeLast(days) }
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val minimum = points.minOf { it.trendGrams }
        val maximum = points.maxOf { it.trendGrams }
        val span = (maximum - minimum).coerceAtLeast(1.0)
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = index.toFloat() / (points.size - 1) * size.width
            val y = size.height - ((point.trendGrams - minimum) / span * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
