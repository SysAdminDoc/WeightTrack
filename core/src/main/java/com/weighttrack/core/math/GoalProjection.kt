package com.weighttrack.core.math

import com.weighttrack.core.model.DEFAULT_GOAL_BAND_GRAMS
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.WeightUnit
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Where a goal stands right now and when the current rate would reach it.
 *
 * [etaDays] is null whenever a date would be a lie: the trend is flat, or it is moving away
 * from the target. Showing "12,400 days" or a date in 2071 is the behaviour that makes people
 * distrust these apps, so the projection refuses instead.
 */
/**
 * Why no date is on offer.
 *
 * The projection refusing to guess is the honest answer, and it is also the one that reads as the
 * app being broken unless it says which of these it is. Distrust of these apps is mostly built out
 * of dates that were obviously invented; a refusal that explains itself is the opposite of that.
 */
enum class NoEtaReason {
    /** Already there. */
    REACHED,

    /** Not enough weigh-ins yet to fit a rate to. */
    NOT_ENOUGH_DATA,

    /** The line is level, so no rate divides into the remaining distance. */
    FLAT,

    /** Moving away from the target, so at this rate it never arrives. */
    WRONG_WAY,

    /** So slow that the answer would be years out, which is noise rather than a forecast. */
    TOO_FAR_OFF,
}

/**
 * Where the trend stands against the target.
 *
 * "Reached" on its own describes two different situations that want different words. Somebody
 * holding at their target is doing what they set out to do. Somebody two kilograms below a loss
 * target is past it, and calling that an improving trend is the thing Happy Scale is criticised
 * for: it reads as encouragement to keep going, which is not what the person set the goal to do.
 */
enum class GoalStanding {
    /** Still on the way. */
    WORKING,

    /** At the target, inside the band. */
    HOLDING,

    /** Past the target by more than the band: below it for a loss goal, above it for a gain. */
    PAST_TARGET,

    /** Out of the band on the near side. Only a maintain goal can be here. */
    DRIFTED,
}

data class GoalProjection(
    val direction: GoalDirection,
    val startGrams: Int,
    val targetGrams: Int,
    val currentTrendGrams: Double,
    val progressFraction: Double,
    val remainingGrams: Double,
    val etaDays: Double?,
    val etaDaysOptimistic: Double?,
    val etaDaysPessimistic: Double?,
    val movingTowardGoal: Boolean,
    val reached: Boolean,
    /** Which of the four situations this is, once [reached] alone stops being enough. */
    val standing: GoalStanding = GoalStanding.WORKING,
    /** The band the standing was judged against, in grams. */
    val bandGrams: Int = DEFAULT_GOAL_BAND_GRAMS,
    /** Null when there is a date. Otherwise which of the reasons applied. */
    val noEtaReason: NoEtaReason? = null,
    /** The span the rate was fitted across, in days. */
    val fittedDays: Int = 0,
    /** How many of those days carried a weigh-in. */
    val fittedWeighIns: Int = 0,
    /** The rate the date came from, in grams a day. */
    val fittedGramsPerDay: Double = 0.0,
) {
    fun etaDate(today: LocalDate): LocalDate? = etaDays?.let { today.plusDays(ceil(it).toLong()) }

    fun etaDateOptimistic(today: LocalDate): LocalDate? =
        etaDaysOptimistic?.let { today.plusDays(ceil(it).toLong()) }

    fun etaDatePessimistic(today: LocalDate): LocalDate? =
        etaDaysPessimistic?.let { today.plusDays(ceil(it).toLong()) }
}

/**
 * The bands a goal can be held to, in whichever unit somebody reads.
 *
 * Here rather than in the screen because the two ladders do not line up: the stored default is a
 * kilogram, which is not any whole number of pounds, so a pounds reader opened the goal screen
 * with three unselected chips and no way back to the band their goal actually used.
 */
object GoalBands {

    fun optionsGrams(unit: WeightUnit): List<Int> = when (unit) {
        WeightUnit.KG -> listOf(500, 1_000, 2_000)
        WeightUnit.LB, WeightUnit.ST_LB -> listOf(
            UnitConverter.lbToGrams(1.0),
            UnitConverter.lbToGrams(2.0),
            UnitConverter.lbToGrams(5.0),
        )
    }

    /**
     * The offered band closest to a stored one.
     *
     * A band is a number somebody picked in the unit they read. Snapping keeps the screen honest
     * about what is selected; nothing is written until they save.
     */
    fun nearest(grams: Int, unit: WeightUnit): Int =
        optionsGrams(unit).minByOrNull { abs(it - grams) } ?: grams
}

object GoalProjector {

    /** Beyond this the estimate is noise, so no date is offered at all. */
    const val MAX_PROJECTION_DAYS = 3.0 * 365


    fun project(
        direction: GoalDirection,
        startGrams: Int,
        targetGrams: Int,
        series: TrendSeries,
        rate: TrendRate,
        /** How far either way still counts as being there. Never below a gram. */
        bandGrams: Int = DEFAULT_GOAL_BAND_GRAMS,
    ): GoalProjection? {
        val band = max(1, bandGrams)
        val currentTrend = series.latestTrendGrams ?: return null

        if (direction == GoalDirection.MAINTAIN) {
            val drift = currentTrend - targetGrams
            val withinBand = abs(drift) <= band
            return GoalProjection(
                direction = direction,
                startGrams = startGrams,
                targetGrams = targetGrams,
                currentTrendGrams = currentTrend,
                progressFraction = if (withinBand) 1.0 else 0.0,
                remainingGrams = drift,
                etaDays = null,
                etaDaysOptimistic = null,
                etaDaysPessimistic = null,
                movingTowardGoal = withinBand,
                reached = withinBand,
                standing = if (withinBand) GoalStanding.HOLDING else GoalStanding.DRIFTED,
                bandGrams = band,
                // Holding steady is being there; having drifted out of the band is not, and
                // saying so in a sheet whose other line reads "still to go 3.0 kg" is a
                // contradiction on one screen.
                noEtaReason = if (withinBand) NoEtaReason.REACHED else NoEtaReason.WRONG_WAY,
                fittedDays = rate.sampleDays,
                fittedWeighIns = rate.weighIns,
                fittedGramsPerDay = rate.gramsPerDay,
            )
        }

        val totalSpan = (targetGrams - startGrams).toDouble()
        val covered = currentTrend - startGrams
        val remaining = targetGrams - currentTrend

        val reached = when (direction) {
            GoalDirection.LOSE -> currentTrend <= targetGrams
            GoalDirection.GAIN -> currentTrend >= targetGrams
            GoalDirection.MAINTAIN -> false
        }

        // A start equal to the target leaves nothing to divide by; treat it as already done.
        val progress = if (abs(totalSpan) < 1.0) {
            1.0
        } else {
            (covered / totalSpan).coerceIn(0.0, 1.0)
        }

        val wantedSign = if (direction == GoalDirection.LOSE) -1.0 else 1.0
        val movingToward = rate.hasEnoughData &&
            rate.gramsPerDay != 0.0 &&
            sign(rate.gramsPerDay) == wantedSign

        val eta = if (reached || !movingToward) {
            null
        } else {
            daysFor(remaining, rate.gramsPerDay)
        }
        val why = when {
            eta != null -> null
            reached -> NoEtaReason.REACHED
            !rate.hasEnoughData -> NoEtaReason.NOT_ENOUGH_DATA
            rate.gramsPerDay == 0.0 -> NoEtaReason.FLAT
            !movingToward -> NoEtaReason.WRONG_WAY
            // Moving the right way, with a rate, and still no date: the only way left is that
            // daysFor refused because the answer was beyond the horizon.
            else -> NoEtaReason.TOO_FAR_OFF
        }
        // The faster bound of the rate gives the earlier date, and vice versa. Either bound is
        // dropped when it crosses zero, because a rate that might be flat implies no date.
        val etaOptimistic = if (eta == null) {
            null
        } else {
            val faster = if (wantedSign < 0) rate.fastestGramsPerDay else rate.slowestGramsPerDay
            if (sign(faster) == wantedSign) daysFor(remaining, faster) else null
        }
        val etaPessimistic = if (eta == null) {
            null
        } else {
            val slower = if (wantedSign < 0) rate.slowestGramsPerDay else rate.fastestGramsPerDay
            if (sign(slower) == wantedSign) daysFor(remaining, slower) else null
        }

        // Past the target is not the same as at it. Somebody two kilograms below a loss target
        // has gone further than they set out to, and a screen that reads as encouragement to
        // keep going is telling them the wrong thing.
        val standing = when {
            !reached -> GoalStanding.WORKING
            direction == GoalDirection.LOSE && currentTrend < targetGrams - band ->
                GoalStanding.PAST_TARGET
            direction == GoalDirection.GAIN && currentTrend > targetGrams + band ->
                GoalStanding.PAST_TARGET
            else -> GoalStanding.HOLDING
        }

        return GoalProjection(
            direction = direction,
            startGrams = startGrams,
            targetGrams = targetGrams,
            currentTrendGrams = currentTrend,
            progressFraction = progress,
            remainingGrams = remaining,
            etaDays = eta,
            etaDaysOptimistic = etaOptimistic,
            etaDaysPessimistic = etaPessimistic,
            movingTowardGoal = movingToward,
            reached = reached,
            standing = standing,
            bandGrams = band,
            noEtaReason = why,
            fittedDays = rate.sampleDays,
            fittedWeighIns = rate.weighIns,
            fittedGramsPerDay = rate.gramsPerDay,
        )
    }

    private fun daysFor(remainingGrams: Double, gramsPerDay: Double): Double? {
        if (gramsPerDay == 0.0) return null
        val days = remainingGrams / gramsPerDay
        if (days < 0 || days.isNaN() || days.isInfinite()) return null
        return if (days > MAX_PROJECTION_DAYS) null else days
    }

    /**
     * The rate needed to land on a chosen date, for goals expressed as "by this day"
     * rather than "at this pace".
     */
    fun requiredGramsPerDay(
        currentTrendGrams: Double,
        targetGrams: Int,
        today: LocalDate,
        targetDate: LocalDate,
    ): Double? {
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, targetDate)
        if (days <= 0) return null
        return (targetGrams - currentTrendGrams) / days
    }
}

/**
 * A staged sub-goal between the start weight and the target.
 *
 * Happy Scale's most-praised idea: a 40 lb goal is discouraging, the next 5 lb is not.
 */
data class Milestone(
    val grams: Int,
    val index: Int,
    val total: Int,
    val reachedOn: LocalDate?,
) {
    val reached: Boolean get() = reachedOn != null
}

object Milestones {

    /** Default spacing, chosen to be a round number in whichever unit the person reads. */
    fun defaultStepGrams(unit: WeightUnit): Int = when (unit) {
        WeightUnit.KG -> 2_000
        WeightUnit.LB, WeightUnit.ST_LB -> UnitConverter.lbToGrams(5.0)
    }

    /**
     * How close to the target a generated milestone may sit before it is dropped, as a
     * fraction of the step.
     */
    private const val CROWDING_FRACTION = 0.4

    /**
     * Milestones from just past the start weight through to the target. The final milestone is
     * always the target itself, even when the span does not divide evenly by the step.
     */
    fun generate(startGrams: Int, targetGrams: Int, stepGrams: Int): List<Milestone> {
        val span = targetGrams - startGrams
        if (span == 0 || stepGrams <= 0) return emptyList()

        val step = abs(stepGrams) * (if (span < 0) -1 else 1)
        val marks = ArrayList<Int>()
        var next = startGrams + step
        // Guard against a step so small that the list would be unusable.
        val maxMarks = 500
        while (marks.size < maxMarks && isBefore(next, targetGrams, span)) {
            marks += next
            next += step
        }
        // A span of 6.2 kg in 2 kg steps would otherwise end on 0.2 kg from the target, which
        // reads as two milestones in the same place and awards both on the same morning.
        if (marks.isNotEmpty() && abs(targetGrams - marks.last()) < abs(step) * CROWDING_FRACTION) {
            marks.removeAt(marks.lastIndex)
        }
        marks += targetGrams

        val total = marks.size
        return marks.mapIndexed { i, grams -> Milestone(grams, i + 1, total, null) }
    }

    /**
     * Stamps each milestone with the first day the smoothed trend reached it.
     *
     * The trend is used rather than a raw reading so a single dehydrated morning cannot award
     * a milestone that gets taken back the next day.
     */
    fun withProgress(milestones: List<Milestone>, series: TrendSeries, losing: Boolean): List<Milestone> {
        if (milestones.isEmpty() || series.isEmpty) return milestones
        return milestones.map { milestone ->
            val day = series.points.firstOrNull { point ->
                if (losing) point.trendGrams <= milestone.grams else point.trendGrams >= milestone.grams
            }
            milestone.copy(reachedOn = day?.date)
        }
    }

    /** The next milestone still ahead, or null once every one has been passed. */
    fun next(milestones: List<Milestone>): Milestone? = milestones.firstOrNull { !it.reached }

    /**
     * How much further to the next milestone, as a positive magnitude.
     */
    fun remainingToNext(milestones: List<Milestone>, currentTrendGrams: Double): Double? =
        next(milestones)?.let { max(0.0, abs(currentTrendGrams - it.grams)) }

    private fun isBefore(candidate: Int, target: Int, span: Int): Boolean =
        if (span < 0) candidate > target else candidate < target

    /** Milestone spacing as a share of the total span, for the "5% steps" option. */
    fun percentStepGrams(startGrams: Int, targetGrams: Int, percent: Double): Int =
        max(1.0, abs(targetGrams - startGrams) * (percent / 100.0)).roundToInt()
}
