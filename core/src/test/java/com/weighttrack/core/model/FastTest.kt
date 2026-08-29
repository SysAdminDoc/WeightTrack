package com.weighttrack.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FastTest {

    private val start: Instant = Instant.parse("2026-01-01T20:00:00Z")

    private fun fast(end: Instant? = null, targetMinutes: Int = 16 * 60) =
        Fast(start = start, end = end, targetMinutes = targetMinutes)

    @Test
    fun `a running fast measures against now`() {
        val running = fast()
        assertThat(running.isRunning).isTrue()
        assertThat(running.elapsed(start.plus(Duration.ofHours(5)))).isEqualTo(Duration.ofHours(5))
    }

    @Test
    fun `a finished fast ignores now`() {
        val finished = fast(end = start.plus(Duration.ofHours(16)))
        assertThat(finished.isRunning).isFalse()
        // Days later, the recorded length must not have grown.
        assertThat(finished.elapsed(start.plus(Duration.ofDays(3)))).isEqualTo(Duration.ofHours(16))
    }

    @Test
    fun `a clock that moves backwards does not produce a negative fast`() {
        // Timezone changes and NTP corrections both do this, and a negative duration would
        // render as nonsense on the timer.
        assertThat(fast().elapsed(start.minus(Duration.ofHours(2)))).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `progress runs from zero to one and stops there`() {
        val running = fast(targetMinutes = 16 * 60)
        assertThat(running.progress(start)).isWithin(1e-6f).of(0f)
        assertThat(running.progress(start.plus(Duration.ofHours(8)))).isWithin(1e-6f).of(0.5f)
        assertThat(running.progress(start.plus(Duration.ofHours(16)))).isWithin(1e-6f).of(1f)
        // Fasting past the target is normal and must not overflow the bar.
        assertThat(running.progress(start.plus(Duration.ofHours(30)))).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `the target is reached exactly on time`() {
        val running = fast(targetMinutes = 16 * 60)
        assertThat(running.reachedTarget(start.plus(Duration.ofHours(15)))).isFalse()
        assertThat(running.reachedTarget(start.plus(Duration.ofHours(16)))).isTrue()
        assertThat(running.reachedTarget(start.plus(Duration.ofHours(17)))).isTrue()
    }

    @Test
    fun `remaining time disappears once the target is passed`() {
        val running = fast(targetMinutes = 16 * 60)
        assertThat(running.remaining(start.plus(Duration.ofHours(10))))
            .isEqualTo(Duration.ofHours(6))
        assertThat(running.remaining(start.plus(Duration.ofHours(16)))).isNull()
        assertThat(running.remaining(start.plus(Duration.ofHours(20)))).isNull()
    }

    @Test
    fun `a zero target does not divide by zero`() {
        val odd = fast(targetMinutes = 0)
        assertThat(odd.progress(start.plus(Duration.ofHours(2)))).isEqualTo(0f)
        assertThat(odd.reachedTarget(start.plus(Duration.ofHours(2)))).isTrue()
    }

    @Test
    fun `presets map back from stored minutes`() {
        assertThat(FastingPreset.forMinutes(16 * 60)).isEqualTo(FastingPreset.SIXTEEN_EIGHT)
        assertThat(FastingPreset.forMinutes(18 * 60)).isEqualTo(FastingPreset.EIGHTEEN_SIX)
        // A custom length is not a preset, and pretending otherwise would relabel someone's fast.
        assertThat(FastingPreset.forMinutes(17 * 60 + 30)).isNull()
    }

    @Test
    fun `every preset has a sane target`() {
        FastingPreset.entries.forEach { preset ->
            assertThat(preset.targetMinutes).isGreaterThan(0)
            assertThat(preset.targetMinutes).isAtMost(24 * 60)
            assertThat(preset.label).isNotEmpty()
        }
    }
}
