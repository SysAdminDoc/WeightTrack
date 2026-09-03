package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * What a period does to the number this app tells somebody to eat.
 *
 * The measured effect is about half a kilogram of extracellular water arriving over a few days
 * with no change in fat at all. Fitted across a fortnight that reads as a real gain, and the
 * expenditure loop answers it by recommending less food to somebody whose body did nothing.
 * Flagged, those mornings are still counted and count for a tenth.
 */
class WaterRetentionExpenditureTest {

    private val today = LocalDate.of(2026, 8, 31)
    private val window = 14
    private val start = today.minusDays(window.toLong() - 1)

    /** The days the spike lands on: four of them, in the middle of the fortnight. */
    private val flagged = (7..10).map { start.plusDays(it.toLong()) }.toSet()

    /** Two thousand a day, every day. Never flagged and never weighted. */
    private val intake = (0 until window).associate { start.plusDays(it.toLong()) to 2_000.0 }

    /**
     * A steady hundred grams a day down, with [spike] grams of water added on the flagged days.
     */
    private fun series(spike: Int): TrendSeries {
        val readings = (0 until window).map { day ->
            val date = start.plusDays(day.toLong())
            val water = if (date in flagged) spike else 0
            DailyWeight(date, 80_000 - day * 100 + water)
        }
        return TrendEngine.computeSeries(readings, TrendEngine.DEFAULT_WINDOW_DAYS)
    }

    private fun kcal(spike: Int, waterDays: Set<LocalDate>): Double =
        AdaptiveExpenditure.estimate(
            series = series(spike),
            intakeByDate = intake,
            today = today,
            waterRetentionDays = waterDays,
        )!!.kcalPerDay

    @Test
    fun `a flagged half kilogram of water moves the answer less than fifty calories`() {
        val clean = kcal(spike = 0, waterDays = flagged)
        val flaggedSpike = kcal(spike = 500, waterDays = flagged)

        assertThat(abs(flaggedSpike - clean)).isLessThan(50.0)
    }

    @Test
    fun `the same spike unflagged moves it a great deal more`() {
        // The comparison the flag is worth having. Without it the same four mornings of water
        // take well over a hundred calories a day off what this app suggests eating.
        val clean = kcal(spike = 0, waterDays = emptySet())
        val unflagged = kcal(spike = 500, waterDays = emptySet())

        assertThat(abs(unflagged - clean)).isGreaterThan(50.0)
    }

    @Test
    fun `flagging days with nothing unusual on them barely moves anything`() {
        // Refusing the permission has to be the only difference the permission makes. Granting
        // it on a fortnight with no water event must not quietly change the number either.
        val without = kcal(spike = 0, waterDays = emptySet())
        val with = kcal(spike = 0, waterDays = flagged)

        assertThat(abs(with - without)).isLessThan(1.0)
    }

    @Test
    fun `the intake mean is not discounted on flagged days`() {
        // The morning's reading is contaminated. The food is not. A person who eats more in the
        // week before a period must still have that food counted at full weight, or the answer
        // is about a fortnight they did not have.
        val eatingMore = intake.mapValues { (date, kcal) ->
            if (date in flagged) kcal + 1_000.0 else kcal
        }
        val estimate = AdaptiveExpenditure.estimate(
            series = series(spike = 0),
            intakeByDate = eatingMore,
            today = today,
            waterRetentionDays = flagged,
        )!!

        // Four days of an extra thousand across fourteen days is 285.7 on the mean. Anything
        // less means the flag reached the food.
        assertThat(estimate.meanIntakeKcal).isWithin(0.5).of(2_000.0 + 4_000.0 / 14.0)
    }

    @Test
    fun `a fortnight that is entirely flagged still answers`() {
        // Every weight equally discounted is the same fit as none of them discounted. Refusing
        // to answer at all would punish exactly the person this feature is for.
        val everyDay = (0 until window).map { start.plusDays(it.toLong()) }.toSet()

        assertThat(kcal(spike = 0, waterDays = everyDay))
            .isWithin(0.01)
            .of(kcal(spike = 0, waterDays = emptySet()))
    }
}
