package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * What a chosen window did, beside the same length of time before it.
 *
 * "Down 1.4 kg over the last month" is a fact nobody can place. The comparison is the whole
 * point of the figure, so it is the part worth pinning down.
 */
class RangeComparisonTest {

    private val start = LocalDate.of(2026, 1, 1)

    /** A steady loss of [gramsPerDay] from 90 kg, one point a day, for [days] days. */
    private fun series(days: Int, gramsPerDay: Double = -50.0) = TrendSeries(
        points = (0 until days).map { day ->
            TrendPoint(
                date = start.plusDays(day.toLong()),
                trendGrams = 90_000.0 + day * gramsPerDay,
                actualGrams = (90_000.0 + day * gramsPerDay).toInt(),
            )
        },
        alpha = 0.1,
    )

    @Test
    fun `the change runs from the day before the window to its last day`() {
        val subject = series(days = 60)

        val comparison = Analytics.changeOverRange(
            subject,
            from = start.plusDays(30),
            to = start.plusDays(59),
        )

        // Thirty days of window, each losing fifty grams, measured from the day before it began.
        assertThat(comparison.days).isEqualTo(30)
        assertThat(comparison.changeGrams).isWithin(0.001).of(-1_500.0)
    }

    @Test
    fun `the window before it is the same length and ends the day before`() {
        // Ninety days of history, so the thirty before the chosen thirty have a day in front of
        // them too and both stretches really are the same length.
        val subject = series(days = 90)

        val comparison = Analytics.changeOverRange(
            subject,
            from = start.plusDays(60),
            to = start.plusDays(89),
        )

        assertThat(comparison.changeGrams).isWithin(0.001).of(-1_500.0)
        assertThat(comparison.previousChangeGrams).isWithin(0.001).of(-1_500.0)
    }

    @Test
    fun `a shorter stretch before the window is not offered as a comparison`() {
        // The history starts inside the earlier window, so it covers twenty-nine days rather
        // than thirty. Reported as "the 30 before" it would flatter whichever window was chosen,
        // every time, at the point where somebody has least history to judge by.
        val subject = series(days = 60)

        val comparison = Analytics.changeOverRange(
            subject,
            from = start.plusDays(30),
            to = start.plusDays(59),
        )

        assertThat(comparison.changeGrams).isWithin(0.001).of(-1_500.0)
        assertThat(comparison.previousChangeGrams).isNull()
    }

    @Test
    fun `a month that went better than the one before it says so`() {
        // A month of history in front, then fifty grams a day for thirty days, then two hundred
        // a day for thirty. The month in front is what makes the earlier window a whole one.
        val before = (0..29).map { 90_000.0 }
        val slow = (1..30).map { before.last() - it * 50 }
        val fast = (1..30).map { slow.last() - it * 200 }
        val subject = TrendSeries(
            points = (before + slow + fast).mapIndexed { day, grams ->
                TrendPoint(start.plusDays(day.toLong()), grams, grams.toInt())
            },
            alpha = 0.1,
        )

        val comparison = Analytics.changeOverRange(
            subject,
            from = start.plusDays(60),
            to = start.plusDays(89),
        )

        assertThat(comparison.changeGrams).isWithin(0.001).of(-6_000.0)
        assertThat(comparison.previousChangeGrams).isWithin(0.001).of(-1_500.0)
    }

    @Test
    fun `a range picked backwards is read the way it was meant`() {
        val subject = series(days = 60)

        val forwards = Analytics.changeOverRange(subject, start.plusDays(30), start.plusDays(59))
        val backwards = Analytics.changeOverRange(subject, start.plusDays(59), start.plusDays(30))

        assertThat(backwards).isEqualTo(forwards)
    }

    @Test
    fun `no history before the window leaves nothing to compare against`() {
        val subject = series(days = 30)

        val comparison = Analytics.changeOverRange(subject, start, start.plusDays(29))

        assertThat(comparison.changeGrams).isNotNull()
        assertThat(comparison.previousChangeGrams).isNull()
    }

    @Test
    fun `a window with nothing in it reports nothing rather than no change`() {
        // Zero and "never weighed" look identical as a number and are not the same thing.
        val subject = series(days = 30)

        val comparison = Analytics.changeOverRange(
            subject,
            from = start.plusYears(1),
            to = start.plusYears(1).plusDays(30),
        )

        assertThat(comparison.changeGrams).isNull()
    }

    @Test
    fun `a single day with a day in front of it is a change`() {
        // Somebody picking today as the start is asking what today did, and the day before it is
        // right there to measure from. Answering "nothing recorded in this range" while the chart
        // underneath draws the reading is the version of this that was wrong.
        val subject = series(days = 30)

        val comparison = Analytics.changeOverRange(subject, start.plusDays(10), start.plusDays(10))

        assertThat(comparison.days).isEqualTo(1)
        assertThat(comparison.changeGrams).isWithin(0.001).of(-50.0)
    }

    @Test
    fun `the very first day of a history is not a change`() {
        // Nothing in front of it and nothing beside it. Zero would read as a day that held
        // steady, which is not the same as a day with nothing to compare against.
        val subject = series(days = 30)

        val comparison = Analytics.changeOverRange(subject, start, start)

        assertThat(comparison.changeGrams).isNull()
    }
}
