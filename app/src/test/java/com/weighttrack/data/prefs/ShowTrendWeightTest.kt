package com.weighttrack.data.prefs

import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Whether the figure at the top of Home is the trend or the reading.
 *
 * The trend sits above the scale for as long as somebody is losing, which is the whole point of
 * it and also the thing that makes it read as the app arguing with the number they just stood
 * on. Anybody who would rather see the reading can say so, and it has to stick: a preference
 * about the first thing on the first screen is not one to re-answer on every launch.
 */
class ShowTrendWeightTest {

    @Test
    fun `the trend is what Home shows until somebody says otherwise`() = runTest {
        val settings = testSettingsRepository()

        assertThat(settings.settings.first().showTrendWeight).isTrue()
    }

    @Test
    fun `turning it off is remembered`() = runTest {
        val settings = testSettingsRepository()

        settings.setShowTrendWeight(false)

        assertThat(settings.settings.first().showTrendWeight).isFalse()
    }

    @Test
    fun `turning it back on is remembered too`() = runTest {
        // The positive control. Without it the test above would pass against a setting that
        // could only ever go one way.
        val settings = testSettingsRepository()

        settings.setShowTrendWeight(false)
        settings.setShowTrendWeight(true)

        assertThat(settings.settings.first().showTrendWeight).isTrue()
    }

    /**
     * It says nothing about the person, so it must not travel.
     *
     * The stamped writes are the ones sync compares and copies between devices. Which figure
     * somebody wants at the top of their phone is a fact about that phone, and stamping it would
     * let a change made here reach across and rearrange the other device's home screen.
     */
    @Test
    fun `it is not stamped, so it does not sync`() = runTest {
        val settings = testSettingsRepository()
        val before = settings.settings.first().updatedAtUtcMillis

        settings.setShowTrendWeight(false)

        assertThat(settings.settings.first().updatedAtUtcMillis).isEqualTo(before)
    }

    @Test
    fun `a stamped setting still stamps`() = runTest {
        // The control for the test above: proves updatedAtUtcMillis moves at all.
        val settings = testSettingsRepository()
        val before = settings.settings.first().updatedAtUtcMillis

        settings.setTrendWindowDays(21)

        assertThat(settings.settings.first().updatedAtUtcMillis).isGreaterThan(before)
    }
}
