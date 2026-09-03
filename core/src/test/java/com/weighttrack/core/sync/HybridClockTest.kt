package com.weighttrack.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HybridClockTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `a stamp reads as the phone's own time while the phone's time is sane`() {
        val clock = HybridClock()

        assertThat(clock.next(now)).isEqualTo(now)
    }

    @Test
    fun `two stamps in the same millisecond are still in order`() {
        val clock = HybridClock()

        val first = clock.next(now)
        val second = clock.next(now)

        assertThat(second).isGreaterThan(first)
    }

    @Test
    fun `a clock that goes backwards does not`() {
        val clock = HybridClock()
        val before = clock.next(now)

        // A network time update, or somebody setting the date by hand. Without this the next
        // edit would sort before the one it is correcting, on the same phone.
        val after = clock.next(now - 60_000)

        assertThat(after).isGreaterThan(before)
    }

    @Test
    fun `an edit made after reading somebody else's is stamped after it`() {
        // The whole point. A phone ten minutes slow would otherwise correct a reading with an
        // edit that loses to the version it is correcting, forever.
        val slow = HybridClock()
        slow.observe(now, now - 600_000)

        assertThat(slow.next(now - 600_000)).isGreaterThan(now)
    }

    @Test
    fun `a peer with a wildly wrong clock is not followed`() {
        val clock = HybridClock()
        val decadeAhead = now + 10L * 365 * 24 * 60 * 60 * 1000

        clock.observe(decadeAhead, now)

        // Followed as far as the cap and no further, so one broken phone cannot stamp every
        // edit made here for the next ten years with a date nobody can explain.
        assertThat(clock.next(now)).isAtMost(now + HybridClock.MAX_DRIFT_MILLIS + 1)
        assertThat(clock.state()).isAtLeast(now)
    }

    @Test
    fun `a stamp already ahead of the clock leaves the clock behind it`() {
        val clock = HybridClock(initialState = now - 5_000)

        clock.observe(now - 10_000, now)

        assertThat(clock.state()).isEqualTo(now - 5_000)
    }
}
