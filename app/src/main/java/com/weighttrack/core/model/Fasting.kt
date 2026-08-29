package com.weighttrack.core.model

import java.time.Duration
import java.time.Instant

/**
 * The common fasting windows, plus a custom one.
 *
 * Named by the eating pattern people actually use, because "16:8" means something to someone
 * doing it and "960 minutes" does not.
 */
enum class FastingPreset(val label: String, val targetMinutes: Int) {
    TWELVE_TWELVE("12:12", 12 * 60),
    FOURTEEN_TEN("14:10", 14 * 60),
    SIXTEEN_EIGHT("16:8", 16 * 60),
    EIGHTEEN_SIX("18:6", 18 * 60),
    TWENTY_FOUR("20:4", 20 * 60),
    OMAD("OMAD", 23 * 60),
    ;

    companion object {
        val DEFAULT = SIXTEEN_EIGHT

        /** Matches a stored target back to a preset, or null when it was a custom length. */
        fun forMinutes(minutes: Int): FastingPreset? = entries.firstOrNull { it.targetMinutes == minutes }
    }
}

/**
 * A fast, open or finished.
 *
 * [end] is null while it is running. Everything that needs a length takes "now" as a parameter
 * rather than reading the clock itself, so the maths stays testable.
 */
data class Fast(
    val id: Long = 0,
    val start: Instant,
    val end: Instant? = null,
    val targetMinutes: Int,
    val note: String? = null,
) {
    val isRunning: Boolean get() = end == null

    val target: Duration get() = Duration.ofMinutes(targetMinutes.toLong())

    /** How long the fast has run, or ran. Never negative, even if the clock moved backwards. */
    fun elapsed(now: Instant = Instant.now()): Duration {
        val finish = end ?: now
        val duration = Duration.between(start, finish)
        return if (duration.isNegative) Duration.ZERO else duration
    }

    fun progress(now: Instant = Instant.now()): Float {
        if (targetMinutes <= 0) return 0f
        return (elapsed(now).toMinutes().toFloat() / targetMinutes).coerceIn(0f, 1f)
    }

    fun reachedTarget(now: Instant = Instant.now()): Boolean =
        elapsed(now) >= target

    /** Time left to the target, or null once it has been passed. */
    fun remaining(now: Instant = Instant.now()): Duration? {
        val left = target.minus(elapsed(now))
        return if (left.isNegative || left.isZero) null else left
    }
}
