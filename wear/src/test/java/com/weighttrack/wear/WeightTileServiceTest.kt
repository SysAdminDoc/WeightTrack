package com.weighttrack.wear

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.sync.WearSummary
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tile, built for real.
 *
 * The protolayout builders throw on a malformed tile, and a tile that throws renders as an empty
 * card in the carousel with nothing in the log to say why. The proto is read as bytes rather
 * than by casting down the element hierarchy, so the test asserts what the tile says instead of
 * how it is nested.
 */
@RunWith(RobolectricTestRunner::class)
class WeightTileServiceTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun tileText(summary: WearSummary?): String = weightTile(
        headline = WearGlanceText.headline(context, summary),
        detail = WearGlanceText.detail(context, summary),
        packageName = "com.weighttrack",
        tapToLog = context.getString(R.string.wear_tap_to_log),
    ).tileTimeline!!.timelineEntries.single().layout!!.toByteArray().decodeToString()

    @Test
    fun `the tile carries the trend and the week`() {
        val text = tileText(
            WearSummary(
                trendGrams = 82_500,
                weekChangeGrams = -420.0,
                weightUnit = WeightUnit.KG,
                entryCount = 12,
            ),
        )

        assertThat(text).contains("82.5 kg")
        assertThat(text).contains("-0.4 kg this week")
        assertThat(text).contains("Tap to log")
        // Tapping anywhere on the card has to reach the picker.
        assertThat(text).contains(WearMainActivity::class.java.name)
        assertThat(text).contains("com.weighttrack")
    }

    @Test
    fun `a locked phone leaves no weight on the tile`() {
        val text = tileText(WearSummary(trendGrams = 82_500, hidden = true))

        assertThat(text).doesNotContain("82.5")
        assertThat(text).contains("Locked")
    }

    @Test
    fun `a tile with nothing to show still builds`() {
        // The carousel renders this before the phone has ever sent anything.
        val text = tileText(null)

        assertThat(text).contains("Open WeightTrack on your phone")
    }
}
