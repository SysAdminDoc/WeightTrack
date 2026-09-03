package com.weighttrack.data.io

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.weighttrack.R
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.medication.MedicationReport
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.medication.drugLabel
import com.weighttrack.ui.medication.effectLabel
import com.weighttrack.ui.medication.severityLabel
import com.weighttrack.ui.medication.siteLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The report somebody takes to an appointment, as a page.
 *
 * What goes on it was decided in [MedicationReport], which has no Android in it and can therefore
 * be held to the rule that matters: injections, side effects and the weight line, and nothing
 * else. This turns that into paper, in the reader's own language, and adds nothing of its own.
 *
 * Plain text in one column rather than anything clever. It is going to be read on a screen in a
 * consulting room or printed on a black-and-white printer, and both of those want a page that is
 * legible before it is pretty.
 */
@Singleton
class MedicationPdfWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val strings: AppStrings,
) {

    /** One line of the page, and how heavily it is set. */
    data class Line(val text: String, val style: Style)

    enum class Style { TITLE, HEADING, BODY, GAP }

    /**
     * The page as words, before anything is drawn.
     *
     * Split out because `PdfDocument` is native and cannot run off a device, so a test that went
     * through it could only ever check that nothing threw. This is the part with decisions in it:
     * which sections appear, in which order, in whose language, and that nothing else of somebody's
     * gets onto a page they are about to hand across a desk.
     */
    fun lines(content: MedicationReport.Content, unit: WeightUnit): List<Line> {
        val dates = DateTimeFormatter.ISO_LOCAL_DATE
        val lines = mutableListOf<Line>()
        fun title(text: String) = lines.add(Line(text, Style.TITLE))
        fun heading(text: String) = lines.add(Line(text, Style.HEADING))
        fun body(text: String) = lines.add(Line(text, Style.BODY))
        fun gap() = lines.add(Line("", Style.GAP))

        title(strings[R.string.medication_report_title])
        body(
            strings[
                R.string.medication_report_range,
                dates.format(content.from),
                dates.format(content.to),
            ],
        )
        gap()

        heading(strings[R.string.medication_report_doses])
        if (content.doses.isEmpty()) {
            body(strings[R.string.medication_report_none])
        } else {
            content.doses.forEach { dose ->
                body(
                    strings[
                        R.string.medication_report_dose_line,
                        dates.format(dose.date),
                        strings[drugLabel(dose.drug)],
                        trimmed(dose.milligrams),
                        strings[siteLabel(dose.site)],
                    ],
                )
            }
        }
        gap()

        heading(strings[R.string.medication_report_side_effects])
        if (content.effects.isEmpty()) {
            body(strings[R.string.medication_report_none])
        } else {
            content.effects.forEach { effect ->
                body(
                    strings[
                        R.string.medication_report_effect_line,
                        dates.format(effect.date),
                        strings[effectLabel(effect.kind)],
                        strings[severityLabel(effect.severity)],
                    ],
                )
            }
        }
        gap()

        heading(strings[R.string.medication_report_weight])
        if (content.trend.isEmpty()) {
            body(strings[R.string.medication_report_none])
        } else {
            content.trend.forEach { point ->
                body(
                    strings[
                        R.string.medication_report_weight_line,
                        dates.format(point.date),
                        WeightFormatter.full(point.trendGrams, unit),
                    ],
                )
            }
        }
        return lines
    }

    /** Writes the report, and says how many rows it held. */
    suspend fun write(
        uri: Uri,
        content: MedicationReport.Content,
        unit: WeightUnit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val document = PdfDocument()
            try {
                Page(document).use { page ->
                    lines(content, unit).forEach { line ->
                        when (line.style) {
                            Style.TITLE -> page.heading(line.text)
                            Style.HEADING -> page.subheading(line.text)
                            Style.BODY -> page.line(line.text)
                            Style.GAP -> page.gap()
                        }
                    }
                }
                context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
                    ?: error("no output stream for $uri")
            } finally {
                document.close()
            }
            content.doses.size + content.effects.size + content.trend.size
        }
    }
    /** Milligrams without a trailing zero, because 0.5 and 2 are both things a pen says. */
    private fun trimmed(milligrams: Double): String =
        if (milligrams == milligrams.toLong().toDouble()) {
            milligrams.toLong().toString()
        } else {
            milligrams.toString()
        }

    /**
     * One flowing column, spilling onto a new page when it runs out of room.
     *
     * A4 at 72 points to the inch, which is what every PDF reader assumes when nothing says
     * otherwise.
     */
    private class Page(private val document: PdfDocument) : AutoCloseable {
        private val body = Paint().apply { textSize = 10f; color = android.graphics.Color.BLACK }
        private val bold = Paint().apply {
            textSize = 10f
            color = android.graphics.Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val title = Paint().apply {
            textSize = 16f
            color = android.graphics.Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private var page: PdfDocument.Page? = null
        private var y = 0f

        private fun canvas(): android.graphics.Canvas {
            val current = page ?: start()
            return current.canvas
        }

        private fun start(): PdfDocument.Page {
            val started = document.startPage(
                PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, document.pages.size + 1).create(),
            )
            page = started
            y = MARGIN
            return started
        }

        private fun write(text: String, paint: Paint, leading: Float) {
            val target = canvas()
            if (y + leading > HEIGHT - MARGIN) {
                finishPage()
                start()
                write(text, paint, leading)
                return
            }
            target.drawText(text, MARGIN, y, paint)
            y += leading
        }

        fun heading(text: String) = write(text, title, 24f)

        fun subheading(text: String) = write(text, bold, 16f)

        fun line(text: String) = write(text, body, 13f)

        fun gap() {
            y += 8f
        }

        private fun finishPage() {
            page?.let { document.finishPage(it) }
            page = null
        }

        override fun close() = finishPage()

        companion object {
            const val WIDTH = 595
            const val HEIGHT = 842
            const val MARGIN = 40f
        }
    }
}
