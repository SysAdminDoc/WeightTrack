package com.weighttrack.core.math

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
) {
    fun etaDate(today: LocalDate): LocalDate? = etaDays?.let { today.plusDays(ceil(it).toLong()) }

    fun etaDateOptimistic(today: LocalDate): LocalDate? =
        etaDaysOptimistic?.let { today.plusDays(ceil(it).toLong()) }

    fun etaDatePessimistic(today: LocalDate): LocalDate? =
        etaDaysPessimistic?.let { today.plusDays(ceil(it).toLong()) }
}

object GoalProjector {

    /** Beyond this the estimate is noise, so no date is offered at all. */
    const val MAX_PROJECTION_DAYS = 3.0 * 365

    /** Half-width of the band that counts as holding steady for a maintain goal. */
    const val MAINTAIN_TOLERANCE_GRAMS = 1000

    fun project(
        direction: GoalDirection,
        startGrams: Int,
        targetGrams: Int,
        series: TrendSeries,
        rate: TrendRate,
    ): GoalProjection? {
        val currentTrend = series.latestTrendGrams ?: return null

        if (direction == GoalDirection.MAINTAIN) {
            val drift = currentTrend - targetGrams
            val withinBand = abs(drift) <= MAINTAIN_TOLERANCE_GRAMS
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
