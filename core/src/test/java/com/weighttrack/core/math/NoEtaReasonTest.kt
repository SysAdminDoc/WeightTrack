package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.GoalDirection
import org.junit.Test
import java.time.LocalDate

/**
 * Why there is no date, when there is no date.
 *
 * Refusing to guess is the right answer and it reads as the app being broken unless it says which
 * refusal it is. "Your trend is flat" and "you are going the other way" and "not enough readings
 * yet" are three different situations and only one of them is a problem.
 */
class NoEtaReasonTest {

    private fun series(vararg grams: Double): TrendSeries {
        val start = LocalDate.of(2026, 8, 1)
        val points = grams.mapIndexed { index, value ->
            TrendPoint(
                date = start.plusDays(index.toLong()),
                trendGrams = value,
                actualGrams = value.toInt(),
            )
        }
        return TrendSeries(points, 0.1)
    }

    private fun rate(gramsPerDay: Double, days: Int = 21, error: Double = 1.0) =
        TrendRate(gramsPerDay = gramsPerDay, standardErrorGramsPerDay = error, sampleDays = days)

    private fun project(
        trend: List<Double>,
        gramsPerDay: Double,
        target: Int,
        days: Int = 21,
        direction: GoalDirection = GoalDirection.LOSE,
    ) = GoalProjector.project(
        direction = direction,
        startGrams = 90_000,
        targetGrams = target,
        series = series(*trend.toDoubleArray()),
        rate = rate(gramsPerDay, days),
    )

    @Test
    fun `a level line says it is level`() {
        val projection = project(listOf(85_000.0, 85_000.0), gramsPerDay = 0.0, target = 80_000)!!

        assertThat(projection.etaDays).isNull()
        assertThat(projection.noEtaReason).isEqualTo(NoEtaReason.FLAT)
    }

    @Test
    fun `going the other way says so`() {
        // Gaining against a loss goal. At this rate it never arrives, and a date would be a lie.
        val projection = project(listOf(85_000.0, 85_400.0), gramsPerDay = 60.0, target = 80_000)!!

        assertThat(projection.etaDays).isNull()
        assertThat(projection.noEtaReason).isEqualTo(NoEtaReason.WRONG_WAY)
    }

    @Test
    fun `a target years away is refused as too far off`() {
        // Losing a gram a day with five kilograms to go is roughly fourteen years.
        val projection = project(listOf(85_000.0, 84_999.0), gramsPerDay = -1.0, target = 80_000)!!

        assertThat(projection.etaDays).isNull()
        assertThat(projection.noEtaReason).isEqualTo(NoEtaReason.TOO_FAR_OFF)
    }

    @Test
    fun `too few readings is its own answer`() {
        val projection = project(
            listOf(85_000.0, 84_800.0),
            gramsPerDay = -50.0,
            target = 80_000,
            days = 1,
        )!!

        assertThat(projection.etaDays).isNull()
        assertThat(projection.noEtaReason).isEqualTo(NoEtaReason.NOT_ENOUGH_DATA)
    }

    @Test
    fun `already there is not a failure`() {
        val projection = project(listOf(79_000.0, 78_900.0), gramsPerDay = -50.0, target = 80_000)!!

        assertThat(projection.reached).isTrue()
        assertThat(projection.noEtaReason).isEqualTo(NoEtaReason.REACHED)
    }

    @Test
    fun `a date leaves no reason to give`() {
        val projection = project(listOf(85_000.0, 84_800.0), gramsPerDay = -70.0, target = 84_000)!!

        assertThat(projection.etaDays).isNotNull()
        assertThat(projection.noEtaReason).isNull()
    }

    @Test
    fun `the explanation carries what the estimate was made from`() {
        // The whole point of the sheet: how many days it looked at and how fast it thinks you
        // are going, so somebody can judge the date rather than take it on faith.
        val projection = project(listOf(85_000.0, 84_800.0), gramsPerDay = -70.0, target = 84_000)!!

        assertThat(projection.fittedDays).isEqualTo(21)
        assertThat(projection.fittedGramsPerDay).isWithin(1e-9).of(-70.0)
    }

    @Test
    fun `a maintain goal within its band reports as reached`() {
        val projection = project(
            listOf(80_200.0, 80_100.0),
            gramsPerDay = 0.0,
            target = 80_000,
            direction = GoalDirection.MAINTAIN,
        )!!

        assertThat(projection.noEtaReason).isEqualTo(NoEtaReason.REACHED)
    }

    @Test
    fun `a maintain goal that has drifted is not already there`() {
        // The sheet used to say "you are already there" directly above "still to go 3.0 kg", on a
        // screen whose other line said the trend was not moving toward the goal.
        val projection = project(
            listOf(83_000.0, 83_000.0),
            gramsPerDay = 0.0,
            target = 80_000,
            direction = GoalDirection.MAINTAIN,
        )!!

        assertThat(projection.reached).isFalse()
        assertThat(projection.noEtaReason).isNotEqualTo(NoEtaReason.REACHED)
    }

    @Test
    fun `the explanation says how much of the window was actually measured`() {
        // The series carries a point per calendar day whether or not anybody stood on the scale,
        // so quoting the span as "days of readings" overstated it, sometimes sevenfold.
        val start = LocalDate.of(2026, 8, 1)
        val points = (0 until 14).map { day ->
            TrendPoint(
                date = start.plusDays(day.toLong()),
                trendGrams = 85_000.0 - day * 50,
                // Weighed on the first day and the last, and not in between.
                actualGrams = if (day == 0 || day == 13) (85_000 - day * 50) else null,
            )
        }
        val projection = GoalProjector.project(
            direction = GoalDirection.LOSE,
            startGrams = 90_000,
            targetGrams = 84_000,
            series = TrendSeries(points, 0.1),
            rate = TrendEngine.rate(TrendSeries(points, 0.1)),
        )!!

        assertThat(projection.fittedDays).isEqualTo(14)
        assertThat(projection.fittedWeighIns).isEqualTo(2)
    }

    @Test
    fun `every refusal has a reason`() {
        // A null date with no reason is the shape this exists to prevent: the screen would show
        // a blank where an explanation belongs.
        listOf(
            project(listOf(85_000.0, 85_000.0), 0.0, 80_000),
            project(listOf(85_000.0, 85_400.0), 60.0, 80_000),
            project(listOf(85_000.0, 84_999.0), -1.0, 80_000),
            project(listOf(85_000.0, 84_800.0), -50.0, 80_000, days = 1),
            project(listOf(79_000.0, 78_900.0), -50.0, 80_000),
        ).forEach { projection ->
            if (projection!!.etaDays == null) assertThat(projection.noEtaReason).isNotNull()
        }
    }
}
