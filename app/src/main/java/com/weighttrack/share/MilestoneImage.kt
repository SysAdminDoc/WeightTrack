package com.weighttrack.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import com.weighttrack.core.share.MilestoneCard

/**
 * Draws a card to an image, on the phone, with nothing sent anywhere.
 *
 * Plain Canvas rather than a Compose screenshot. What is wanted is a fixed size that looks the
 * same on every device, not a picture of this phone's screen at this phone's density, and drawing
 * it directly means the result can be checked in a test rather than eyeballed.
 */
object MilestoneImage {

    const val WIDTH = 1080
    const val HEIGHT = 1350

    /** Renders the card. The caller owns the bitmap and should recycle it when finished. */
    fun render(content: MilestoneCard.Content, colours: Colours = Colours()): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colours.background)

        // A band of colour down one side rather than behind the text. Text on a gradient is the
        // first thing to become unreadable when somebody's messaging app recompresses the image.
        val band = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, HEIGHT.toFloat(),
                colours.accent, colours.accentEnd, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, BAND_WIDTH, HEIGHT.toFloat(), band)

        val headline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colours.text
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = HEADLINE_SIZE
        }
        headline.textSize = fittedTextSize(headline, content.headline, TEXT_WIDTH)
        canvas.drawText(content.headline, TEXT_LEFT, HEADLINE_BASELINE, headline)

        val subhead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colours.muted
            textSize = SUBHEAD_SIZE
        }
        canvas.drawText(content.subhead, TEXT_LEFT, SUBHEAD_BASELINE, subhead)

        drawShape(canvas, content.shape, colours)

        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colours.muted
            textSize = FOOTER_SIZE
        }
        canvas.drawText(content.footer, TEXT_LEFT, FOOTER_BASELINE, footer)

        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colours.accent
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = FOOTER_SIZE
        }
        canvas.drawText("WeightTrack", TEXT_LEFT, MARK_BASELINE, mark)

        return bitmap
    }

    /**
     * The largest size at which [text] still fits inside [maxWidth].
     *
     * Shrunk rather than clipped. "12 st 11 lb down" is a good deal wider than "5 kg down", and a
     * card that runs its own headline off the edge is worthless. There is a floor: below it the
     * headline is too small to be the headline, and the right answer is a card that looks a
     * little tight rather than one nobody can read.
     */
    fun fittedTextSize(
        paint: Paint,
        text: String,
        maxWidth: Float,
        start: Float = HEADLINE_SIZE,
        minimum: Float = MIN_HEADLINE_SIZE,
    ): Float {
        val measuring = Paint(paint)
        var size = start
        measuring.textSize = size
        while (measuring.measureText(text) > maxWidth && size > minimum) {
            size -= 2f
            measuring.textSize = size
        }
        return size
    }

    /**
     * The trend, as a shape and only a shape.
     *
     * No axis, no numbers, no gridlines. The values never leave the phone: a card that carried
     * them would tell everybody what the person weighs whatever the footer said.
     */
    private fun drawShape(canvas: Canvas, shape: List<Double>, colours: Colours) {
        if (shape.size < 2) return
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colours.accent
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        val width = TEXT_WIDTH
        shape.forEachIndexed { index, value ->
            val x = TEXT_LEFT + width * index / (shape.size - 1)
            // One is the top of the range, so it is drawn at the top.
            val y = SHAPE_TOP + SHAPE_HEIGHT * (1f - value.toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
    }

    /**
     * The card's colours.
     *
     * Its own set rather than the app's theme. The card is shared into somebody else's messaging
     * app, where it has to look deliberate on its own, and a card that changed colour because the
     * phone was in light mode that afternoon would not.
     */
    data class Colours(
        val background: Int = Color.parseColor("#12151C"),
        val text: Int = Color.parseColor("#F2F4F8"),
        val muted: Int = Color.parseColor("#9AA3B2"),
        val accent: Int = Color.parseColor("#5BC98C"),
        val accentEnd: Int = Color.parseColor("#2E7D5B"),
    )

    private const val BAND_WIDTH = 24f
    private const val TEXT_LEFT = 96f
    const val TEXT_WIDTH = WIDTH - TEXT_LEFT - 96f
    const val HEADLINE_SIZE = 128f
    const val MIN_HEADLINE_SIZE = 64f
    private const val HEADLINE_BASELINE = 340f
    private const val SUBHEAD_SIZE = 56f
    private const val SUBHEAD_BASELINE = 430f
    private const val SHAPE_TOP = 620f
    private const val SHAPE_HEIGHT = 380f
    private const val FOOTER_SIZE = 44f
    private const val FOOTER_BASELINE = 1180f
    private const val MARK_BASELINE = 1260f
}
