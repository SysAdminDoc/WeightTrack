package com.weighttrack.core.math

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a person actually burns, worked out from what they ate and what happened to their weight.
 *
 * This is the loop the subscription apps charge for, and there is nothing in it worth charging
 * for: if somebody ate two thousand calories a day for a fortnight and lost half a kilogram a
 * week, they burn about twenty five hundred. No formula, no activity multiplier, no guessing
 * how hard they exercise. Their own body did the measuring.
 *
 * The weight change is fitted across every reading in the window rather than taken from its two
 * ends, because a fortnight measured from a dehydrated Monday to a salty Sunday says nothing
 * about what anybody burns.
 *
 * Deliberately not read off the smoothed line either. The smoothing lags a real change by design,
 * which is right for a chart and wrong here: on a fortnight it reports about half the weight
 * somebody actually lost, and half the deficit is a recommendation hundreds of calories too high.
 */
object AdaptiveExpenditure {

    /**
     * Energy in a kilogram of body mass, shared with the trend maths.
     *
     * An approximation: real tissue lost is a mixture of fat, water and lean mass in a ratio
     * nobody can measure at home. Close enough to be useful over a fortnight and nowhere near
     * precise enough to quote to the calorie, which is why nothing here is.
     */
    const val KCAL_PER_KG = TrendEngine.KCAL_PER_KG

    /**
     * The shortest window worth answering from.
     *
     * A week of data through a period is noise with an opinion. Two weeks is the point where the
     * water lost and gained roughly cancels.
     */
    const val MIN_DAYS = 10

    /** The window to look back over. Longer is steadier; too long stops noticing a real change. */
    const val DEFAULT_WINDOW_DAYS = 14

    /**
     * The fewest weigh-ins worth fitting a line through.
     *
     * A rate from two readings is the difference between two mornings, which is water.
     */
    const val MIN_WEIGH_INS = 5

    /**
     * The lowest intake this app will ever recommend.
     *
     * Below this it stops being a diet and starts being a medical decision, which an app has no
     * business making for somebody. The recommendation is capped and says so rather than
     * quietly suggesting something it should not.
     */
    const val MINIMUM_RECOMMENDED_KCAL = 1_200.0

    /**
     * What somebody burns, and how much to believe it.
     *
     * [days] and [loggedDays] are both reported because the difference between them is what
     * decides whether the number means anything: fourteen days of weight and three of food is
     * not a fortnight of evidence.
     */
    data class Estimate(
        val kcalPerDay: Double,
        val days: Int,
        val loggedDays: Int,
        /** How many times they actually stood on the scale, which is what the rate is fitted to. */
        val weighIns: Int,
        val meanIntakeKcal: Double,
        val trendChangeKg: Double,
    ) {
        val rounded: Int get() = kcalPerDay.roundToInt()

        /** How much of the window has food in it, which is the thing that limits the answer. */
        val coverage: Double get() = if (days <= 0) 0.0 else loggedDays.toDouble() / days
    }

    /**
     * Estimates what somebody burns over a window ending today.
     *
     * Null when there is not enough to say. Refusing is the honest answer, and it is the whole
     * difference between this and an app that shows a confident number made of nothing.
     */
    fun estimate(
        series: TrendSeries,
        intakeByDate: Map<LocalDate, Double>,
        today: LocalDate,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): Estimate? {
        if (windowDays < MIN_DAYS) return null
        val from = today.minusDays(windowDays.toLong() - 1)

        val window = intakeByDate.filterKeys { !it.isBefore(from) && !it.isAfter(today) }
            .filterValues { it > 0 }
        if (window.size < MIN_DAYS) return null

        val weighed = series.points
            .filter { it.actualGrams != null && !it.date.isBefore(from) && !it.date.isAfter(today) }
            .map { it.date to it.actualGrams!! }
            .sortedBy { it.first }
        if (weighed.size < MIN_WEIGH_INS) return null

        // Only the stretch that was actually weighed.
        //
        // Counting readings is not enough. Five mornings in a row, on somebody who bought a
        // scale last week, gives a slope from five days of ordinary water movement, and using it
        // across a fortnight multiplies that noise by three. Four hundred grams of water either
        // way then comes out as fourteen hundred calories a day between the two answers, both
        // stated with a straight face. So the window is the measured stretch, not the asked-for
        // one, and if that stretch is too short there is no answer to give.
        val first = weighed.first().first
        val last = weighed.last().first
        val days = (last.toEpochDay() - first.toEpochDay()).toInt() + 1
        if (days < MIN_DAYS) return null

        val readings = weighed.map { it.first.toEpochDay().toDouble() to it.second.toDouble() }
        val gramsPerDay = slopePerDay(readings) ?: return null
        val kgPerDay = gramsPerDay / 1_000.0
        // What the window covers, first reading to last. A span, not a count: fourteen days have
        // thirteen nights between them. Reported to the person, and never used as the rate.
        val changeKg = kgPerDay * (days - 1)
        // Averaged over the same stretch the weight was measured across, so the two halves of
        // the sum describe the same fortnight.
        val logged = window.filterKeys { !it.isBefore(first) && !it.isAfter(last) }
        if (logged.size < MIN_DAYS) return null
        val meanIntake = logged.values.average()

        // Eating below what you burn shows up as weight going down, so a fall adds to the
        // estimate and a rise takes away from it.
        //
        // The daily rate is used directly here. Working the total change out first and then
        // dividing it by the number of days puts a factor of (days - 1) / days through the
        // answer: about seven per cent of the deficit missing on a fortnight, and a different
        // answer from the same body depending on how long a window it was asked about.
        val burned = meanIntake - kgPerDay * KCAL_PER_KG
        if (burned <= 0) return null

        return Estimate(
            kcalPerDay = burned,
            days = days,
            loggedDays = logged.size,
            weighIns = readings.size,
            meanIntakeKcal = meanIntake,
            trendChangeKg = changeKg,
        )
    }

    /**
     * The least-squares slope through a set of weigh-ins.
     *
     * Unbiased, unlike the smoothed line, and it uses every reading rather than the two that
     * happen to sit at the ends of the window.
     */
    private fun slopePerDay(readings: List<Pair<Double, Double>>): Double? {
        val meanX = readings.sumOf { it.first } / readings.size
        val meanY = readings.sumOf { it.second } / readings.size
        var covariance = 0.0
        var variance = 0.0
        readings.forEach { (x, y) ->
            covariance += (x - meanX) * (y - meanY)
            variance += (x - meanX) * (x - meanX)
        }
        // Every reading on the same day gives no slope to speak of.
        return if (variance <= 0.0) null else covariance / variance
    }

    /** What to eat to move at a chosen rate, given what the person burns. */
    data class Recommendation(
        val kcalPerDay: Double,
        /** True when the honest number was below the floor and this one is the floor instead. */
        val cappedAtMinimum: Boolean,
    ) {
        val rounded: Int get() = kcalPerDay.roundToInt()
    }

    /**
     * What to eat for a chosen rate of change.
     *
     * A rate of zero is maintenance, a negative rate is losing. The result never goes below
     * [MINIMUM_RECOMMENDED_KCAL], and says when it has been held there, because a recommendation
     * that quietly suggests eight hundred calories is worse than one that admits the goal is too
     * aggressive.
     */
    fun recommendedIntake(estimate: Estimate, kgPerWeek: Double): Recommendation {
        val adjustment = kgPerWeek * KCAL_PER_KG / 7.0
        val honest = estimate.kcalPerDay + adjustment
        return if (honest < MINIMUM_RECOMMENDED_KCAL) {
            Recommendation(MINIMUM_RECOMMENDED_KCAL, cappedAtMinimum = true)
        } else {
            Recommendation(honest, cappedAtMinimum = false)
        }
    }

    /**
     * The rate a goal implies, in kilograms a week.
     *
     * Zero when there is no goal or nothing to work from, which reads as maintenance.
     */
    fun rateForGoal(currentGrams: Int?, targetGrams: Int?, weeks: Double): Double {
        if (currentGrams == null || targetGrams == null || weeks <= 0) return 0.0
        return (targetGrams - currentGrams) / 1_000.0 / weeks
    }

    /** Whether an estimate is worth showing rather than hedging about. */
    fun isConfident(estimate: Estimate): Boolean =
        estimate.coverage >= 0.8 && abs(estimate.trendChangeKg) < 5.0
}
