package com.weighttrack.core.nutrition

/**
 * Holds a caller to a number of requests a minute.
 *
 * Open Food Facts publishes hard limits and bans addresses that ignore them, so this is not a
 * nicety. It is a sliding window rather than a bucket that empties on the minute: a bucket lets
 * a caller spend the whole allowance at 59 seconds and the whole next allowance at 61, which is
 * twice the rate the limit means.
 *
 * Time is passed in rather than read, so it can be tested without waiting a minute.
 */
class RateLimiter(private val permitsPerMinute: Int) {

    private val taken = ArrayDeque<Long>()

    /** Takes a permit if one is free. */
    fun tryAcquire(nowMillis: Long): Boolean {
        expire(nowMillis)
        if (taken.size >= permitsPerMinute) return false
        taken.addLast(nowMillis)
        return true
    }

    /** How long until a permit frees up. Zero when one is free now. */
    fun waitMillis(nowMillis: Long): Long {
        expire(nowMillis)
        if (taken.size < permitsPerMinute) return 0
        val oldest = taken.first()
        return (oldest + WINDOW_MILLIS - nowMillis).coerceAtLeast(0)
    }

    /** Permits left in the current window. */
    fun remaining(nowMillis: Long): Int {
        expire(nowMillis)
        return (permitsPerMinute - taken.size).coerceAtLeast(0)
    }

    private fun expire(nowMillis: Long) {
        while (taken.isNotEmpty() && nowMillis - taken.first() >= WINDOW_MILLIS) {
            taken.removeFirst()
        }
    }

    companion object {
        const val WINDOW_MILLIS = 60_000L

        /** What Open Food Facts allows for reading one product. */
        const val PRODUCT_READS_PER_MINUTE = 15

        /** What it allows for searching. */
        const val SEARCHES_PER_MINUTE = 10
    }
}
