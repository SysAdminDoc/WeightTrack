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

    /** How many days at the end of the window say what somebody is doing now. */
    const val RECENT_MOVEMENT_DAYS = 7

    /**
     * How far a day's movement has to sit from the recent norm before it stops being evidence
     * about what that person burns today.
     *
     * A quarter either way. Ordinary weeks vary by less than that; a fortnight where somebody
     * started walking to work, or spent a week on the sofa with flu, does not.
     */
    const val MOVEMENT_SHIFT_THRESHOLD = 0.25

    /**
     * What a day from a different activity level is still worth.
     *
     * Not zero. The week before somebody changed what they were doing is still the best evidence
     * there is about their body, it is just no longer evidence about their week, and throwing it
     * away entirely would leave the estimate swinging on three days of water.
     */
    const val STALE_MOVEMENT_WEIGHT = 0.25

    /**
     * What a morning inside a flagged water event is worth to the rate.
     *
     * Damped rather than dropped, and for the same reason the movement weighting damps: a person
     * who weighs themselves through a period and not much else would otherwise have no window
     * left to fit anything to. A tenth is enough that a measured half-kilogram of water moves the
     * answer by about twenty calories a day instead of a hundred and thirty-five, and small
     * enough that it cannot decide the number on its own.
     *
     * It applies to the weight fit and not to the intake mean. The morning's reading is
     * contaminated; the food eaten that day is not, and down-weighting it would quietly discount
     * the days a lot of people eat most.
     */
    const val WATER_EVENT_WEIGHT = 0.1

    /**
     * How far expenditure moves when the weekly target changes, as a multiple of that change.
     *
     * MacroFactor's figure. Somebody holding a one percent weekly deficit is burning roughly four
     * percent less than they would at maintenance, and the moment they stop, that comes back
     * before any weight change can show it. Waiting a fortnight for the evidence means a
     * fortnight of recommending too little food to somebody who has just decided to stop dieting.
     */
    const val GOAL_SWITCH_ADAPTATION = 4.0

    /**
     * The most the correction may ever move the estimate.
     *
     * Fifteen per cent is already beyond any weekly target a person could hold. The cap exists
     * because the percentage is taken against body mass, and body mass comes from the trend line,
     * which a mistyped reading can drag a long way: a body mass of four kilograms turned a four
     * per cent correction into forty-two, and the number this feeds is a recommendation about
     * what somebody eats.
     */
    const val MAX_ADAPTATION_SHIFT = 0.15

    /**
     * What somebody burns, and how much to believe it.
     *
     * [days] and [loggedDays] are both reported because the difference between them is what
     * decides whether the number means anything: fourteen days of weight and three of food is
     * not a fortnight of evidence.
     */
    data class Estimate(
        val kcalPerDay: Double,
        /** First and last day actually measured, which is not the window that was asked for. */
        val from: LocalDate,
        val to: LocalDate,
        val days: Int,
        val loggedDays: Int,
        /** How many times they actually stood on the scale, which is what the rate is fitted to. */
        val weighIns: Int,
        val meanIntakeKcal: Double,
        val trendChangeKg: Double,
        /**
         * Somebody's movement changed part way through the window, so the older days count for
         * less than the recent ones.
         *
         * Worth saying out loud. A number that moves two hundred calories because the step count
         * doubled, with nothing on screen about it, reads as the app being unreliable.
         */
        val movementChanged: Boolean = false,
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
        /**
         * Daily step counts, where there are any.
         *
         * Never converted into calories, and never added to or subtracted from the answer. Step
         * counters disagree with each other by a third and none of them knows what anybody
         * weighs, so a number of calories taken from one is a guess dressed up as a measurement.
         * All they are read for is whether somebody is doing roughly what they were doing last
         * week, which is the one thing a step counter is actually good at.
         */
        stepsByDate: Map<LocalDate, Long> = emptyMap(),
        /**
         * Days somebody is known to be carrying water that is not tissue.
         *
         * Menstruation, where the app has been allowed to read it. The measured effect is about
         * half a kilogram of extracellular water with no change in fat, arriving and leaving
         * over a few days, which is exactly the shape a fortnight-long fit reads as a real gain.
         * Left empty the maths is unchanged, which is what refusing the permission has to mean.
         */
        waterRetentionDays: Set<LocalDate> = emptySet(),
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

        // How much each day is worth, from whether that day's movement looks like this week's.
        val weightOfDay = movementWeights(stepsByDate, first, last)
        val movementChanged = weightOfDay.values.any { it < 1.0 }

        val readings = weighed.map { (date, grams) ->
            val water = if (date in waterRetentionDays) WATER_EVENT_WEIGHT else 1.0
            Weighted(date.toEpochDay().toDouble(), grams.toDouble(), weightOfDay.of(date) * water)
        }
        val gramsPerDay = slopePerDay(readings) ?: return null
        val kgPerDay = gramsPerDay / 1_000.0
        // What the window covers, first reading to last. A span, not a count: fourteen days have
        // thirteen nights between them. Reported to the person, and never used as the rate.
        val changeKg = kgPerDay * (days - 1)
        // Averaged over the same stretch the weight was measured across, and carrying the
        // movement weighting, so the two halves of the sum describe the same fortnight. A week
        // at a different activity level is a different week for both food and weight, and
        // weighting one and not the other is how a window becomes an answer about two people.
        //
        // The water weighting is deliberately not applied here, and it is the one asymmetry in
        // this sum: a period contaminates the morning's reading and tells you nothing about the
        // food, so discounting the intake as well would drop the days a lot of people eat most
        // and hand back an expenditure built on the wrong fortnight of eating.
        val logged = window.filterKeys { !it.isBefore(first) && !it.isAfter(last) }
        if (logged.size < MIN_DAYS) return null
        val intakeWeight = logged.keys.sumOf { weightOfDay.of(it) }
        if (intakeWeight <= 0.0) return null
        val meanIntake = logged.entries.sumOf { (date, kcal) -> kcal * weightOfDay.of(date) } /
            intakeWeight

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
            from = first,
            to = last,
            days = days,
            loggedDays = logged.size,
            weighIns = readings.size,
            meanIntakeKcal = meanIntake,
            trendChangeKg = changeKg,
            movementChanged = movementChanged,
        )
    }

    /** One day's number, and how much this window believes it. */
    private data class Weighted(val x: Double, val y: Double, val weight: Double)

    private fun Map<LocalDate, Double>.of(date: LocalDate): Double = this[date] ?: 1.0

    /**
     * How much each day of the window counts, from whether its movement looks like this week's.
     *
     * Empty when there is nothing to go on, which leaves every day worth the same. A day with no
     * step count is worth the same too: not wearing the watch is not evidence that anything
     * changed, and treating it as such would down-weight the whole window of anybody who charges
     * their watch overnight.
     */
    private fun movementWeights(
        stepsByDate: Map<LocalDate, Long>,
        first: LocalDate,
        last: LocalDate,
    ): Map<LocalDate, Double> {
        // Zero is a reading, not a gap. Dropping it made the number of days in this map depend on
        // the values in it, so a single quiet day could push the count below the guard below and
        // silently abandon the weighting for every other day: 123 kcal moved by one day's step
        // count, which is precisely what steps are not allowed to do. It also threw away the week
        // on the sofa, which is the case this exists for.
        val inWindow = stepsByDate.filterKeys { !it.isBefore(first) && !it.isAfter(last) }
        if (inWindow.size < MIN_DAYS) return emptyMap()

        val recentFrom = last.minusDays(RECENT_MOVEMENT_DAYS.toLong() - 1)
        val recent = inWindow.filterKeys { !it.isBefore(recentFrom) }.values
        // Not enough of this week measured to say what this week looks like.
        if (recent.size < 3) return emptyMap()
        val now = recent.average()
        if (now <= 0.0) return emptyMap()

        return inWindow.mapValues { (date, _) ->
            // A day is judged by the week around it, never on its own.
            //
            // Everybody's step count halves at the weekend, and comparing single days against
            // the average day makes a two-to-one weekday split look like a change of habit for
            // ever. Worse, it is the weekend days that get down-weighted, and those are the days
            // people eat most, so the intake mean came out 234 kcal light on somebody who had
            // changed nothing at all.
            val week = weekAround(inWindow, date)
            if (week.size < 4) {
                1.0
            } else if (abs(week.average() / now - 1.0) > MOVEMENT_SHIFT_THRESHOLD) {
                STALE_MOVEMENT_WEIGHT
            } else {
                1.0
            }
        }
    }

    /**
     * The seven measured days nearest [date], which is a whole week wherever there is one.
     *
     * Nearest rather than centred, so the days at either end of the window are judged against a
     * full week too. A centred window shrinks to four days at the edges, and four days of a
     * weekday-heavy stretch reads as a different activity level from the week it belongs to.
     */
    private fun weekAround(steps: Map<LocalDate, Long>, date: LocalDate): List<Long> =
        steps.entries
            .sortedBy { abs(it.key.toEpochDay() - date.toEpochDay()) }
            .take(RECENT_MOVEMENT_DAYS)
            .map { it.value }

    /**
     * The least-squares slope through a set of weigh-ins.
     *
     * Unbiased, unlike the smoothed line, and it uses every reading rather than the two that
     * happen to sit at the ends of the window.
     */
    private fun slopePerDay(readings: List<Weighted>): Double? {
        val total = readings.sumOf { it.weight }
        if (total <= 0.0) return null
        val meanX = readings.sumOf { it.x * it.weight } / total
        val meanY = readings.sumOf { it.y * it.weight } / total
        var covariance = 0.0
        var variance = 0.0
        readings.forEach { (x, y, weight) ->
            covariance += weight * (x - meanX) * (y - meanY)
            variance += weight * (x - meanX) * (x - meanX)
        }
        // Every reading on the same day gives no slope to speak of.
        return if (variance <= 0.0) null else covariance / variance
    }

    /**
     * What somebody burns once the target changes, before any evidence of it has arrived.
     *
     * Expenditure is not a constant of the body. It falls while somebody is in a deficit and
     * comes back when they stop, and it does that within days, long before a scale can show it.
     * Handing back the same number after somebody switches from losing to maintaining recommends
     * a fortnight of eating too little to a person who has just decided to stop dieting.
     *
     * [fromKgPerWeek] and [toKgPerWeek] are the old and new weekly targets, negative for losing.
     * [bodyMassKg] turns them into the percentage of body weight the adaptation is measured
     * against, which is what makes the same rate a bigger change for a smaller person.
     */
    fun afterGoalChange(
        estimate: Estimate,
        fromKgPerWeek: Double,
        toKgPerWeek: Double,
        bodyMassKg: Double,
    ): Estimate {
        if (bodyMassKg <= 0.0) return estimate
        val change = (toKgPerWeek - fromKgPerWeek) / bodyMassKg
        if (change == 0.0) return estimate
        val shift = (GOAL_SWITCH_ADAPTATION * change)
            .coerceIn(-MAX_ADAPTATION_SHIFT, MAX_ADAPTATION_SHIFT)
        return estimate.copy(kcalPerDay = estimate.kcalPerDay * (1.0 + shift))
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
