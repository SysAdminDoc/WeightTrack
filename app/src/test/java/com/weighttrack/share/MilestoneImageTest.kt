package com.weighttrack.share

import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.share.MilestoneCard
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The image itself.
 *
 * A card that draws nothing, or draws its own headline off the edge, is a thing somebody finds
 * out about after they have sent it to their family.
 */
@RunWith(RobolectricTestRunner::class)
// Without this Robolectric draws nothing at all: every Canvas call is a no-op and getPixel
// answers zero for the whole bitmap. Two of these tests passed that way without touching a
// single pixel of the real renderer.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MilestoneImageTest {

    private fun content(
        headline: String = "5.0 kg down",
        subhead: String = "in 90 days",
        footer: String = "29 August 2026",
        shape: List<Double> = (0..59).map { 1.0 - it / 59.0 },
    ) = MilestoneCard.Content(
        headline = headline,
        subhead = subhead,
        footer = footer,
        shape = shape,
        line = "$headline $subhead.",
    )

    @Test
    fun `the card is the size it says it is`() {
        val bitmap = MilestoneImage.render(content())
        try {
            assertThat(bitmap.width).isEqualTo(MilestoneImage.WIDTH)
            assertThat(bitmap.height).isEqualTo(MilestoneImage.HEIGHT)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `something is actually drawn on it`() {
        val bitmap = MilestoneImage.render(content())
        try {
            val background = MilestoneImage.Colours().background
            var different = 0
            // Sampled rather than walked pixel by pixel, which is a million reads for no more
            // certainty than this gives.
            for (x in 0 until MilestoneImage.WIDTH step 8) {
                for (y in 0 until MilestoneImage.HEIGHT step 8) {
                    if (bitmap.getPixel(x, y) != background) different++
                }
            }
            assertThat(different).isGreaterThan(100)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `a headline that fits is left at full size`() {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        val size = MilestoneImage.fittedTextSize(paint, "5.0 kg down", MilestoneImage.TEXT_WIDTH)

        assertThat(size).isEqualTo(MilestoneImage.HEADLINE_SIZE)
    }

    @Test
    fun `a headline too wide for the card is shrunk until it fits`() {
        // A card that runs its own headline off the edge is worthless, and stones and pounds are
        // a good deal wider than kilograms.
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val wide = "18 st 11 lb down and still going"

        val size = MilestoneImage.fittedTextSize(paint, wide, MilestoneImage.TEXT_WIDTH)

        assertThat(size).isLessThan(MilestoneImage.HEADLINE_SIZE)
        val measuring = android.graphics.Paint(paint).apply { textSize = size }
        assertThat(measuring.measureText(wide)).isAtMost(MilestoneImage.TEXT_WIDTH)
    }

    @Test
    fun `shrinking stops before the headline stops being readable`() {
        // Something absurd. A card that looks a little tight beats one nobody can read.
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        val size = MilestoneImage.fittedTextSize(paint, "x".repeat(400), MilestoneImage.TEXT_WIDTH)

        assertThat(size).isAtLeast(MilestoneImage.MIN_HEADLINE_SIZE)
    }

    @Test
    fun `fitting does not disturb the paint it was asked about`() {
        val paint = android.graphics.Paint().apply { textSize = 42f }

        MilestoneImage.fittedTextSize(paint, "18 st 11 lb down and still going", 100f)

        assertThat(paint.textSize).isEqualTo(42f)
    }

    @Test
    fun `a card with no line to draw still renders`() {
        // Somebody sharing after a fortnight has a shape; somebody with two readings does not.
        val bitmap = MilestoneImage.render(content(shape = emptyList()))
        try {
            assertThat(bitmap.width).isEqualTo(MilestoneImage.WIDTH)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `the colours do not follow the phone's theme`() {
        // The card is shared into somebody else's app, where it has to look deliberate on its
        // own. One that changed colour because the phone was in light mode would not.
        val colours = MilestoneImage.Colours()
        assertThat(Color.alpha(colours.background)).isEqualTo(255)
        assertThat(colours.background).isNotEqualTo(colours.text)
        assertThat(colours.accent).isNotEqualTo(colours.background)
    }
}
