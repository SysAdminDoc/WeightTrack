package com.weighttrack.core.math

/**
 * How the smoothed line is worked out from the readings.
 *
 * The difference only shows up when the weight is moving steadily, which is exactly when somebody
 * is watching it. An average of the recent past sits behind a steady climb or fall for as long as
 * the climb lasts, by construction. Carrying a slope alongside the level catches up instead.
 */
enum class SmoothingMode {

    /**
     * The Hacker's Diet average, and what every version of this app has shown.
     *
     * Steady on a plateau and slow to be fooled by a heavy meal, at the cost of reading high
     * through a loss and low through a gain.
     */
    EMA,

    /**
     * Holt's linear method: a level and a daily slope, each smoothed.
     *
     * Keeps up with a steady change rather than trailing it, and carries the slope forward across
     * days with no reading, so the line between two weigh-ins a fortnight apart is a continuation
     * rather than a flat stretch.
     */
    HOLT,
}
