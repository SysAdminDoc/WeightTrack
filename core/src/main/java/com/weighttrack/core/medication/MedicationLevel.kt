package com.weighttrack.core.medication

import kotlin.math.exp
import kotlin.math.ln

/**
 * Roughly how much of the medicine is still in the body.
 *
 * Each dose decays on the drug's published half-life and the doses add up, which is why somebody
 * moving from four weeks in to twelve weeks in feels a difference without changing the number on
 * the pen: the level at the end of a week keeps rising until it settles.
 *
 * A single-compartment decay and nothing more. It is not a pharmacokinetic model and it does not
 * try to be: absorption, and everything that varies between people, are left out entirely. What
 * it is good for is the shape between doses and a missed week showing as a dip.
 */
object MedicationLevel {

    /** One injection: when it went in, and how much. */
    data class Dose(val atUtcMillis: Long, val milligrams: Double)

    /** A point on the curve. */
    data class Point(val atUtcMillis: Long, val milligrams: Double)

    /**
     * What is left of every dose given before [atUtcMillis], added up.
     *
     * Doses after that moment count for nothing rather than counting backwards, which is what an
     * unsigned decay would do with a dose recorded in the future.
     */
    fun at(doses: List<Dose>, atUtcMillis: Long, halfLifeHours: Double): Double {
        if (halfLifeHours <= 0) return 0.0
        val decayPerMilli = ln(2.0) / (halfLifeHours * 60 * 60 * 1000)
        return doses.sumOf { dose ->
            val elapsed = atUtcMillis - dose.atUtcMillis
            if (elapsed < 0) 0.0 else dose.milligrams * exp(-decayPerMilli * elapsed)
        }
    }

    /**
     * The curve across a range, one point every [stepHours].
     *
     * Drawn rather than sampled at the doses themselves, because the whole point is what happens
     * between them.
     */
    fun curve(
        doses: List<Dose>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
        halfLifeHours: Double,
        stepHours: Double = 6.0,
    ): List<Point> {
        if (toUtcMillis <= fromUtcMillis || stepHours <= 0 || halfLifeHours <= 0) return emptyList()
        val step = (stepHours * 60 * 60 * 1000).toLong().coerceAtLeast(1)
        // Bounded, so a range somebody scrolled to a decade wide cannot fill memory with points
        // nothing can draw.
        val steps = ((toUtcMillis - fromUtcMillis) / step).coerceAtMost(MAX_POINTS.toLong()).toInt()
        return (0..steps).map { index ->
            val at = fromUtcMillis + index * step
            Point(at, at(doses, at, halfLifeHours))
        }
    }

    private const val MAX_POINTS = 2_000
}
