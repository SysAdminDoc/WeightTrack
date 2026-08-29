package com.weighttrack.wear

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.sync.WearSummary
import org.junit.Test

class WearGlanceTextTest {

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
        assertThat(WearGlanceText.headline(loaded)).isEqualTo("82.5 kg")
        assertThat(WearGlanceText.detail(loaded)).isEqualTo("-0.4 kg this week")
        assertThat(WearGlanceText.hasFigures(loaded)).isTrue()
    }

    @Test
    fun `the app lock leaves nothing readable on a wrist`() {
        val locked = WearSummary(weightUnit = WeightUnit.KG, hidden = true, trendGrams = 82_500)

        // Not "82.5 kg": a weight on a watch face is exactly what the lock exists to hide.
        assertThat(WearGlanceText.headline(locked)).isEqualTo("Locked")
        assertThat(WearGlanceText.detail(locked)).isEqualTo("Unlock on your phone")
        assertThat(WearGlanceText.shortDetail(locked)).isEmpty()
        assertThat(WearGlanceText.hasFigures(locked)).isFalse()
    }

    @Test
    fun `nothing from the phone yet says what to do about it`() {
        assertThat(WearGlanceText.headline(null)).isEqualTo("--")
        assertThat(WearGlanceText.detail(null)).isEqualTo("Open WeightTrack on your phone")
        assertThat(WearGlanceText.hasFigures(null)).isFalse()
    }

    @Test
    fun `a phone with no readings is not the same as no phone`() {
        val empty = WearSummary(weightUnit = WeightUnit.KG, entryCount = 0)

        assertThat(WearGlanceText.headline(empty)).isEqualTo("--")
        assertThat(WearGlanceText.detail(empty)).isEqualTo("No readings yet")
    }

    @Test
    fun `one reading gives a weight but no week`() {
        val single = loaded.copy(weekChangeGrams = null, entryCount = 1)

        assertThat(WearGlanceText.headline(single)).isEqualTo("82.5 kg")
        assertThat(WearGlanceText.detail(single)).isEqualTo("Trend weight")
        assertThat(WearGlanceText.shortDetail(single)).isEmpty()
    }

    @Test
    fun `the figures follow the unit the phone is set to`() {
        val pounds = loaded.copy(weightUnit = WeightUnit.LB)

        assertThat(WearGlanceText.headline(pounds)).isEqualTo("181.9 lb")
        assertThat(WearGlanceText.shortDetail(pounds)).isEqualTo("-0.9 lb")
    }
}
