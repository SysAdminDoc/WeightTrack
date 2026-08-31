package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import java.time.LocalDate

class GoalProjectionTest {

    private val day0: LocalDate = LocalDate.of(2026, 1, 1)

    private fun seriesAt(trendGrams: Double): TrendSeries =
        TrendSeries(listOf(TrendPoint(day0, trendGrams, null)), 0.1)

    private fun rate(gramsPerDay: Double, standardError: Double = 0.0, days: Int = 14) =
        TrendRate(gramsPerDay, standardError, days)

    @Test
    fun `losing at a steady rate projects a date`() {
        val projection = GoalProjector.project(
            direction = GoalDirection.LOSE,
            startGrams = 100_000,
            targetGrams = 90_000,
            series = seriesAt(95_000.0),
            rate = rate(-100.0),
        )!!
        assertThat(projection.etaDays).isWithin(1e-6).of(50.0)
        assertThat(projection.etaDate(day0)).isEqualTo(day0.plusDays(50))
        assertThat(projection.movingTowardGoal).isTrue()
        assertThat(projection.reached).isFalse()
    }

    @Test
    fun `progress is the fraction of the span already covered`() {
        val projection = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(95_000.0), rate(-100.0),
        )!!
        assertThat(projection.progressFraction).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `progress never leaves the zero to one range`() {
        val overshot = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(85_000.0), rate(-100.0),
        )!!
        assertThat(overshot.progressFraction).isEqualTo(1.0)

        val backwards = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(105_000.0), rate(-100.0),
        )!!
        assertThat(backwards.progressFraction).isEqualTo(0.0)
    }

    @Test
    fun `no date is offered when the trend moves away from the goal`() {
        val projection = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(95_000.0), rate(120.0),
        )!!
        assertThat(projection.etaDays).isNull()
        assertThat(projection.movingTowardGoal).isFalse()
    }

    @Test
    fun `no date is offered when the trend is flat`() {
        val projection = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(95_000.0), rate(0.0),
        )!!
        assertThat(projection.etaDays).isNull()
    }

    @Test
    fun `no date is offered before there is enough data to fit a rate`() {
        val projection = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(95_000.0), rate(-100.0, days = 3),
        )!!
        assertThat(projection.etaDays).isNull()
        assertThat(projection.movingTowardGoal).isFalse()
    }

    @Test
    fun `an absurdly distant projection is withheld rather than shown`() {
        // Losing one gram a day would take over forty years to cover five kilograms. A date
        // that far out is noise, so the projection declines to invent one.
        val projection = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(95_000.0), rate(-0.3),
        )!!
        assertThat(projection.etaDays).isNull()
    }

    @Test
    fun `the rate confidence interval brackets the projected date`() {
        val projection = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(95_000.0), rate(-100.0, standardError = 10.0),
        )!!
        assertThat(projection.etaDaysOptimistic!!).isLessThan(projection.etaDays!!)
        assertThat(projection.etaDaysPessimistic!!).isGreaterThan(projection.etaDays)
        assertThat(projection.etaDaysOptimistic).isWithin(0.1).of(5_000.0 / 119.6)
        assertThat(projection.etaDaysPessimistic).isWithin(0.1).of(5_000.0 / 80.4)
    }

    @Test
    fun `an uncertain rate that might be flat drops the pessimistic bound`() {
        // Slope -20 with a standard error of 20 has a confidence interval spanning zero, so
        // the slow end of the range is not a real date.
        val projection = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(95_000.0), rate(-20.0, standardError = 20.0),
        )!!
        assertThat(projection.etaDays).isNotNull()
        assertThat(projection.etaDaysPessimistic).isNull()
    }

    @Test
    fun `a reached goal reports no remaining date`() {
        val projection = GoalProjector.project(
            GoalDirection.LOSE, 100_000, 90_000, seriesAt(89_500.0), rate(-100.0),
        )!!
        assertThat(projection.reached).isTrue()
        assertThat(projection.etaDays).isNull()
        assertThat(projection.progressFraction).isEqualTo(1.0)
    }

    @Test
    fun `gain goals project upward`() {
        val projection = GoalProjector.project(
            GoalDirection.GAIN, 60_000, 70_000, seriesAt(65_000.0), rate(50.0),
        )!!
        assertThat(projection.etaDays).isWithin(1e-6).of(100.0)
        assertThat(projection.movingTowardGoal).isTrue()
        assertThat(projection.progressFraction).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `gain goals reject a downward trend`() {
        val projection = GoalProjector.project(
            GoalDirection.GAIN, 60_000, 70_000, seriesAt(65_000.0), rate(-50.0),
        )!!
        assertThat(projection.etaDays).isNull()
        assertThat(projection.movingTowardGoal).isFalse()
    }

    @Test
    fun `maintain reports holding inside the band`() {
        val inside = GoalProjector.project(
            GoalDirection.MAINTAIN, 75_000, 75_000, seriesAt(75_400.0), rate(5.0),
        )!!
        assertThat(inside.reached).isTrue()
        assertThat(inside.progressFraction).isEqualTo(1.0)
        assertThat(inside.etaDays).isNull()

        val drifted = GoalProjector.project(
            GoalDirection.MAINTAIN, 75_000, 75_000, seriesAt(77_000.0), rate(5.0),
        )!!
        assertThat(drifted.reached).isFalse()
        assertThat(drifted.remainingGrams).isWithin(1e-9).of(2_000.0)
    }

    @Test
    fun `a goal equal to the start weight is already complete`() {
        val projection = GoalProjector.project(
            GoalDirection.LOSE, 90_000, 90_000, seriesAt(90_000.0), rate(-100.0),
        )!!
        assertThat(projection.progressFraction).isEqualTo(1.0)
        assertThat(projection.reached).isTrue()
    }

    @Test
    fun `projection needs a trend to work from`() {
        val empty = TrendSeries(emptyList(), 0.1)
        assertThat(GoalProjector.project(GoalDirection.LOSE, 100_000, 90_000, empty, rate(-100.0)))
            .isNull()
    }

    @Test
    fun `required rate for a chosen date`() {
        val perDay = GoalProjector.requiredGramsPerDay(
            currentTrendGrams = 95_000.0,
            targetGrams = 90_000,
            today = day0,
            targetDate = day0.plusDays(100),
        )
        assertThat(perDay).isWithin(1e-9).of(-50.0)
    }

    @Test
    fun `a target date in the past has no required rate`() {
        assertThat(
            GoalProjector.requiredGramsPerDay(95_000.0, 90_000, day0, day0.minusDays(1)),
        ).isNull()
    }

    @Test
    fun `a chosen band decides what counts as holding a maintain goal`() {
        // A kilogram was the constant, and 1.8 kg of drift failed it. Somebody who says two
        // kilograms is fine is on track at that drift, and off it past their own band.
        val tight = GoalProjector.project(
            GoalDirection.MAINTAIN, 75_000, 75_000, seriesAt(76_800.0), rate(5.0),
            bandGrams = 1_000,
        )!!
        assertThat(tight.reached).isFalse()
        assertThat(tight.standing).isEqualTo(GoalStanding.DRIFTED)

        val chosen = GoalProjector.project(
            GoalDirection.MAINTAIN, 75_000, 75_000, seriesAt(76_800.0), rate(5.0),
            bandGrams = 2_000,
        )!!
        assertThat(chosen.reached).isTrue()
        assertThat(chosen.standing).isEqualTo(GoalStanding.HOLDING)
        assertThat(chosen.bandGrams).isEqualTo(2_000)

        val beyond = GoalProjector.project(
            GoalDirection.MAINTAIN, 75_000, 75_000, seriesAt(77_500.0), rate(5.0),
            bandGrams = 2_000,
        )!!
        assertThat(beyond.reached).isFalse()
    }

    @Test
    fun `a reached loss goal that keeps falling is past the target, not still improving`() {
        val holding = GoalProjector.project(
            GoalDirection.LOSE, 90_000, 80_000, seriesAt(79_600.0), rate(-30.0),
            bandGrams = 1_000,
        )!!
        assertThat(holding.reached).isTrue()
        assertThat(holding.standing).isEqualTo(GoalStanding.HOLDING)

        val past = GoalProjector.project(
            GoalDirection.LOSE, 90_000, 80_000, seriesAt(77_500.0), rate(-30.0),
            bandGrams = 1_000,
        )!!
        assertThat(past.reached).isTrue()
        assertThat(past.standing).isEqualTo(GoalStanding.PAST_TARGET)

        // The chosen band, not the constant it used to be: 1.5 kg under is past a one-kilogram
        // band and still holding inside a two-kilogram one.
        val wider = GoalProjector.project(
            GoalDirection.LOSE, 90_000, 80_000, seriesAt(78_500.0), rate(-30.0),
            bandGrams = 2_000,
        )!!
        assertThat(wider.standing).isEqualTo(GoalStanding.HOLDING)
        val narrower = GoalProjector.project(
            GoalDirection.LOSE, 90_000, 80_000, seriesAt(78_500.0), rate(-30.0),
            bandGrams = 1_000,
        )!!
        assertThat(narrower.standing).isEqualTo(GoalStanding.PAST_TARGET)
    }

    @Test
    fun `a gain goal reads the band the other way round`() {
        val past = GoalProjector.project(
            GoalDirection.GAIN, 60_000, 68_000, seriesAt(70_000.0), rate(30.0),
            bandGrams = 1_000,
        )!!
        assertThat(past.standing).isEqualTo(GoalStanding.PAST_TARGET)

        val holding = GoalProjector.project(
            GoalDirection.GAIN, 60_000, 68_000, seriesAt(68_500.0), rate(30.0),
            bandGrams = 1_000,
        )!!
        assertThat(holding.standing).isEqualTo(GoalStanding.HOLDING)
    }

    @Test
    fun `a goal still on the way is working, whatever its band`() {
        val working = GoalProjector.project(
            GoalDirection.LOSE, 90_000, 80_000, seriesAt(85_000.0), rate(-100.0),
            bandGrams = 5_000,
        )!!
        assertThat(working.standing).isEqualTo(GoalStanding.WORKING)
        // A band wider than the remaining distance must not make an unreached goal read as
        // reached: reaching is about crossing the target, not about being near it.
        assertThat(working.reached).isFalse()
    }

    @Test
    fun `a band of zero is treated as a gram rather than as no band at all`() {
        val exact = GoalProjector.project(
            GoalDirection.MAINTAIN, 75_000, 75_000, seriesAt(75_000.0), rate(0.0),
            bandGrams = 0,
        )!!
        assertThat(exact.reached).isTrue()
        assertThat(exact.bandGrams).isEqualTo(1)
    }
}

class MilestonesTest {

    private val day0: LocalDate = LocalDate.of(2026, 1, 1)

    @Test
    fun `milestones step down from the start toward the target`() {
        val milestones = Milestones.generate(100_000, 90_000, 2_000)
        assertThat(milestones.map { it.grams })
            .containsExactly(98_000, 96_000, 94_000, 92_000, 90_000)
            .inOrder()
        assertThat(milestones.last().index).isEqualTo(5)
        assertThat(milestones.first().total).isEqualTo(5)
    }

    @Test
    fun `the target is always the final milestone even on an uneven span`() {
        val milestones = Milestones.generate(100_000, 93_000, 2_000)
        assertThat(milestones.map { it.grams })
            .containsExactly(98_000, 96_000, 94_000, 93_000)
            .inOrder()
    }

    @Test
    fun `a milestone that lands almost on the target is dropped`() {
        // 84.2 down to 78.0 in 2 kg steps would otherwise finish 82.2, 80.2, 78.2, 78.0. The
        // last two are 200 g apart, so they crowd into the same spot on the progress bar and
        // both get awarded on the same morning.
        val milestones = Milestones.generate(84_200, 78_000, 2_000)
        assertThat(milestones.map { it.grams })
            .containsExactly(82_200, 80_200, 78_000)
            .inOrder()
        assertThat(milestones.last().total).isEqualTo(3)
    }

    @Test
    fun `a milestone comfortably short of the target is kept`() {
        // 1 kg of clearance is half the step, so it stands on its own.
        val milestones = Milestones.generate(85_000, 78_000, 2_000)
        assertThat(milestones.map { it.grams })
            .containsExactly(83_000, 81_000, 79_000, 78_000)
            .inOrder()
    }

    @Test
    fun `crowding removal also applies to gain goals`() {
        val milestones = Milestones.generate(60_000, 66_200, 2_000)
        assertThat(milestones.map { it.grams })
            .containsExactly(62_000, 64_000, 66_200)
            .inOrder()
    }

    @Test
    fun `a span shorter than one step is still just the target`() {
        assertThat(Milestones.generate(84_200, 84_000, 2_000).map { it.grams })
            .containsExactly(84_000)
    }

    @Test
    fun `gain goals step upward`() {
        val milestones = Milestones.generate(60_000, 66_000, 2_000)
        assertThat(milestones.map { it.grams })
            .containsExactly(62_000, 64_000, 66_000)
            .inOrder()
    }

    @Test
    fun `no span means no milestones`() {
        assertThat(Milestones.generate(90_000, 90_000, 2_000)).isEmpty()
    }

    @Test
    fun `a nonsensical step produces no milestones rather than hanging`() {
        assertThat(Milestones.generate(100_000, 90_000, 0)).isEmpty()
    }

    @Test
    fun `default step is a round number in the chosen unit`() {
        assertThat(Milestones.defaultStepGrams(WeightUnit.KG)).isEqualTo(2_000)
        assertThat(Milestones.defaultStepGrams(WeightUnit.LB))
            .isEqualTo(UnitConverter.lbToGrams(5.0))
    }

    @Test
    fun `progress stamps the day the trend first crossed each milestone`() {
        val points = (0..20).map {
            TrendPoint(day0.plusDays(it.toLong()), 100_000.0 - 500.0 * it, null)
        }
        val series = TrendSeries(points, 0.1)
        val milestones = Milestones.withProgress(
            Milestones.generate(100_000, 90_000, 2_000),
            series,
            losing = true,
        )
        // 98 kg is crossed on day 4, 96 kg on day 8, and so on down to 90 kg on day 20.
        assertThat(milestones[0].reachedOn).isEqualTo(day0.plusDays(4))
        assertThat(milestones[1].reachedOn).isEqualTo(day0.plusDays(8))
        assertThat(milestones.last().reachedOn).isEqualTo(day0.plusDays(20))
        assertThat(milestones.all { it.reached }).isTrue()
    }

    @Test
    fun `a milestone the trend never reached stays unreached`() {
        val points = (0..5).map { TrendPoint(day0.plusDays(it.toLong()), 100_000.0 - 100.0 * it, null) }
        val series = TrendSeries(points, 0.1)
        val milestones = Milestones.withProgress(
            Milestones.generate(100_000, 90_000, 2_000),
            series,
            losing = true,
        )
        assertThat(milestones.none { it.reached }).isTrue()
        assertThat(Milestones.next(milestones)!!.grams).isEqualTo(98_000)
    }

    @Test
    fun `a single heavy weigh-in does not award a milestone the trend has not reached`() {
        // Raw readings dip below 98 kg for one morning, but the smoothed line never gets there,
        // so the milestone is not awarded and cannot be taken back tomorrow.
        val readings = listOf(
            0 to 100_000, 1 to 99_800, 2 to 97_500, 3 to 99_900, 4 to 99_700,
        ).map { (day, grams) -> DailyWeight(day0.plusDays(day.toLong()), grams) }
        val series = TrendEngine.computeSeries(readings)
        val milestones = Milestones.withProgress(
            Milestones.generate(100_000, 90_000, 2_000),
            series,
            losing = true,
        )
        assertThat(milestones.first().reached).isFalse()
    }

    @Test
    fun `remaining distance to the next milestone is a positive magnitude`() {
        val milestones = Milestones.generate(100_000, 90_000, 2_000)
        assertThat(Milestones.remainingToNext(milestones, 99_000.0)).isWithin(1e-9).of(1_000.0)
    }

    @Test
    fun `percent steps divide the span`() {
        assertThat(Milestones.percentStepGrams(100_000, 90_000, 5.0)).isEqualTo(500)
    }
}
