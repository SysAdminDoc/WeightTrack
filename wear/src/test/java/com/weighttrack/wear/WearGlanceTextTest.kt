package com.weighttrack.wear

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.sync.WearSummary
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WearGlanceTextTest {

    /**
     * Resolved through the real resources, so these read what the watch would actually show.
     * The watch was left in English through the translation pass; a padlock on a wrist is no use
     * to somebody who cannot read the word next to it.
     */
    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val loaded = WearSummary(
        trendGrams = 82_500,
        latestGrams = 83_100,
        weekChangeGrams = -420.0,
        weightUnit = WeightUnit.KG,
        lastLoggedEpochDay = 20_000,
        entryCount = 12,
    )

    @Test
    fun `the tile leads with the trend and the week`() {
        assertThat(WearGlanceText.headline(context, loaded)).isEqualTo("82.5 kg")
        assertThat(WearGlanceText.detail(context, loaded)).isEqualTo("-0.4 kg this week")
        assertThat(WearGlanceText.hasFigures(loaded)).isTrue()
    }

    @Test
    fun `the app lock leaves nothing readable on a wrist`() {
        val locked = WearSummary(weightUnit = WeightUnit.KG, hidden = true, trendGrams = 82_500)

        // Not "82.5 kg": a weight on a watch face is exactly what the lock exists to hide.
        assertThat(WearGlanceText.headline(context, locked)).isEqualTo("Locked")
        assertThat(WearGlanceText.detail(context, locked)).isEqualTo("Unlock on your phone")
        assertThat(WearGlanceText.shortDetail(locked)).isEmpty()
        assertThat(WearGlanceText.hasFigures(locked)).isFalse()
    }

    @Test
    fun `nothing from the phone yet says what to do about it`() {
        assertThat(WearGlanceText.headline(context, null)).isEqualTo("--")
        assertThat(WearGlanceText.detail(context, null)).isEqualTo("Open WeightTrack on your phone")
        assertThat(WearGlanceText.hasFigures(null)).isFalse()
    }

    @Test
    fun `a phone with no readings is not the same as no phone`() {
        val empty = WearSummary(weightUnit = WeightUnit.KG, entryCount = 0)

        assertThat(WearGlanceText.headline(context, empty)).isEqualTo("--")
        assertThat(WearGlanceText.detail(context, empty)).isEqualTo("No readings yet")
    }

    @Test
    fun `one reading gives a weight but no week`() {
        val single = loaded.copy(weekChangeGrams = null, entryCount = 1)

        assertThat(WearGlanceText.headline(context, single)).isEqualTo("82.5 kg")
        assertThat(WearGlanceText.detail(context, single)).isEqualTo("Trend weight")
        assertThat(WearGlanceText.shortDetail(single)).isEmpty()
    }

    /** Any run of digits that could be read as a body weight, in kilograms or in pounds. */
    private fun weightsIn(text: String): List<String> =
        Regex("""\d+(?:[.,]\d+)?""").findAll(text)
            .map { it.value }
            .filter { (it.replace(',', '.').toDoubleOrNull() ?: 0.0) >= 20.0 }
            .toList()

    @Test
    fun `the glance mode puts no weight on a wrist`() {
        val glance = loaded.copy(aboveTrendGrams = 600.0, glanceOnly = true)

        val everything = listOf(
            WearGlanceText.headline(context, glance),
            WearGlanceText.detail(context, glance),
            WearGlanceText.shortDetail(glance),
        )

        assertThat(everything.flatMap { weightsIn(it) }).isEmpty()
    }

    @Test
    fun `the glance mode says which way and by how much`() {
        val above = loaded.copy(aboveTrendGrams = 600.0, glanceOnly = true)
        val below = loaded.copy(aboveTrendGrams = -300.0, glanceOnly = true)

        assertThat(WearGlanceText.headline(context, above)).isEqualTo("▲ 0.6 kg")
        assertThat(WearGlanceText.detail(context, above)).isEqualTo("above your trend")
        assertThat(WearGlanceText.headline(context, below)).isEqualTo("▼ 0.3 kg")
        assertThat(WearGlanceText.detail(context, below)).isEqualTo("below your trend")
    }

    @Test
    fun `the glance mode with nothing to compare says so rather than guessing`() {
        val nothing = loaded.copy(aboveTrendGrams = null, glanceOnly = true)

        assertThat(WearGlanceText.headline(context, nothing)).isEqualTo("--")
        assertThat(weightsIn(WearGlanceText.detail(context, nothing))).isEmpty()
    }

    @Test
    fun `a summary from a phone that has never heard of the glance mode reads as before`() {
        // Every field defaults off, so an older phone's summary draws the tile it always did.
        assertThat(loaded.glanceOnly).isFalse()
        assertThat(WearGlanceText.headline(context, loaded)).isEqualTo("82.5 kg")
    }

    @Test
    fun `the figures follow the unit the phone is set to`() {
        val pounds = loaded.copy(weightUnit = WeightUnit.LB)

        assertThat(WearGlanceText.headline(context, pounds)).isEqualTo("181.9 lb")
        assertThat(WearGlanceText.shortDetail(pounds)).isEqualTo("-0.9 lb")
    }
}
