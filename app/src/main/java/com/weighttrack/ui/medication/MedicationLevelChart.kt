package com.weighttrack.ui.medication

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.medication.MedicationLevel
import com.weighttrack.data.repo.MedicationDose

/**
 * The level between doses, with a mark where each injection went in.
 *
 * Drawn on a canvas for the same reason the weight chart is: the line and the marks have to share
 * one coordinate space exactly, and a chart library that owns its own axes cannot promise that.
 *
 * No numbers on it on purpose. The height is a rough shape rather than a measurement, and putting
 * milligrams down the side would invite somebody to read it as one.
 */
@Composable
fun MedicationLevelChart(
    points: List<MedicationLevel.Point>,
    doses: List<MedicationDose>,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val line = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.tertiary
    val description = stringResource(R.string.medication_level_heading)

    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val first = points.first().atUtcMillis
        val last = points.last().atUtcMillis
        val span = (last - first).coerceAtLeast(1)
        val peak = points.maxOf { it.milligrams }.coerceAtLeast(1e-9)
        val left = 4.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = 4.dp.toPx()
        val bottom = size.height - 4.dp.toPx()
        if (right <= left || bottom <= top) return@Canvas

        fun x(at: Long) = left + (at - first).toFloat() / span * (right - left)
        fun y(milligrams: Double) = bottom - (milligrams / peak).toFloat() * (bottom - top)

        val path = Path()
        points.forEachIndexed { index, point ->
            val px = x(point.atUtcMillis)
            val py = y(point.milligrams)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color = line, style = Stroke(width = 2.dp.toPx()))

        // One tick per injection, on the same axis as the line, so a dip lines up with the week
        // it was missed rather than with a number in a list somewhere else.
        doses.forEach { dose ->
            val at = dose.timestamp.toEpochMilli()
            if (at < first || at > last) return@forEach
            val px = x(at)
            drawLine(
                color = mark,
                start = Offset(px, bottom),
                end = Offset(px, bottom - 8.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}
