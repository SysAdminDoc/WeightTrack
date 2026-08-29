package com.weighttrack.core.nutrition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `the allowance runs out and comes back a minute later`() {
        val limiter = RateLimiter(3)

        repeat(3) { assertThat(limiter.tryAcquire(0)).isTrue() }
        assertThat(limiter.tryAcquire(0)).isFalse()
        assertThat(limiter.waitMillis(0)).isEqualTo(RateLimiter.WINDOW_MILLIS)

        assertThat(limiter.tryAcquire(RateLimiter.WINDOW_MILLIS - 1)).isFalse()
        assertThat(limiter.tryAcquire(RateLimiter.WINDOW_MILLIS)).isTrue()
    }

    @Test
    fun `the window slides rather than emptying on the minute`() {
        // A bucket that resets on the minute lets a caller spend the whole allowance at 59
        // seconds and the whole next one at 61, which is twice the rate the limit means.
        val limiter = RateLimiter(3)
        repeat(3) { assertThat(limiter.tryAcquire(59_000)).isTrue() }

        assertThat(limiter.tryAcquire(61_000)).isFalse()
        assertThat(limiter.remaining(61_000)).isEqualTo(0)
        // Only once those three are a full minute old is there room again.
        assertThat(limiter.tryAcquire(119_000)).isTrue()
    }

    @Test
    fun `permits are freed one at a time, as each ages out`() {
        val limiter = RateLimiter(2)
        limiter.tryAcquire(0)
        limiter.tryAcquire(30_000)

        assertThat(limiter.remaining(59_000)).isEqualTo(0)
        assertThat(limiter.remaining(60_000)).isEqualTo(1)
        assertThat(limiter.remaining(90_000)).isEqualTo(2)
    }

    @Test
    fun `the published limits are the ones used`() {
        assertThat(RateLimiter.PRODUCT_READS_PER_MINUTE).isEqualTo(15)
        assertThat(RateLimiter.SEARCHES_PER_MINUTE).isEqualTo(10)
    }
}
