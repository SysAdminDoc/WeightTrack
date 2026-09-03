package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/**
 * Days the weekday card is not allowed to call unusual.
 *
 * The card exists to find the weekend showing up on a Monday, and it works by averaging how far
 * each weekday sits from the smoothed line. A period does the same thing to the scale and is not
 * a fact about any weekday: it puts on about half a kilogram of extracellular water, and because
 * a cycle is not seven days long it lands somewhere different every month.
 *
 * The fixture aligns it to twenty-eight days, which is four whole weeks and therefore the worst
 * case on purpose: every period falls on the same five weekdays, so the card has the best reason
 * it will ever have to believe those days are heavy, and is at its most wrong.
 */
class WeekdayEffectExclusionTest {

    private val start = LocalDate.of(2026, 6, 1) // A Monday.
    private val days = 84 // Twelve whole weeks, so every weekday gets the same twelve readings.

    /** Five days of water every twenty-eight, landing on Thursday through Monday. */
    private val periodDays: Set<LocalDate> = (0 until days)
        .filter { it % 28 < 5 }
        .map { start.plusDays((it + 3).toLong()) }
        .filter { it.isBefore(start.plusDays(days.toLong())) }
        .toSet()

    /**
     * A flat line with the spike sitting on top of it, built rather than smoothed.
     *
     * The subject here is [Analytics.weekdayEffects] and nothing else, so the trend is handed in
     * flat. Run through the smoother the spike also drags the line it is measured against, which
     * is real and is tested on its own below, but it would leave every number in these
     * assertions a fact about two functions at once.
     */
    private fun series(spikeOn: Set<LocalDate>, spikeGrams: Int) = TrendSeries(
        points = (0 until days).map { day ->
            val date = start.plusDays(day.toLong())
            TrendPoint(
                date = date,
                trendGrams = 80_000.0,
                actualGrams = 80_000 + if (date in spikeOn) spikeGrams else 0,
            )
        },
        alpha = 0.1,
    )

    private fun spread(effects: List<WeekdayEffect>): Double {
        val values = effects.map { it.averageDeviationGrams }
        return values.max() - values.min()
    }

    @Test
    fun `a period reads as five heavy weekdays when nothing excludes it`() {
        val effects = Analytics.weekdayEffects(series(periodDays, spikeGrams = 500))

        // Three periods in twelve weeks puts 125 g on five of the days and nothing on the other
        // two, and the centring turns that into a 125 g gap between them. Every bit of it is
        // water on a cycle that has no opinion about weekdays.
        assertThat(spread(effects)).isGreaterThan(100.0)
        assertThat(effects.first { it.day == DayOfWeek.THURSDAY }.averageDeviationGrams)
            .isGreaterThan(20.0)
    }

    @Test
    fun `excluding those days leaves no weekday looking unusual`() {
        val effects = Analytics.weekdayEffects(
            series(periodDays, spikeGrams = 500),
            excluded = periodDays,
        )

        assertThat(spread(effects)).isLessThan(1.0)
        assertThat(abs(effects.first { it.day == DayOfWeek.THURSDAY }.averageDeviationGrams))
            .isLessThan(1.0)
    }

    @Test
    fun `the readings that are left still count`() {
        val effects = Analytics.weekdayEffects(
            series(periodDays, spikeGrams = 500),
            excluded = periodDays,
        )

        // Still seven days reported. Excluding a morning must not lose the weekday with it.
        assertThat(effects.map { it.day }).containsExactlyElementsIn(DayOfWeek.entries)
        assertThat(effects.first { it.day == DayOfWeek.THURSDAY }.readings).isEqualTo(9)
        assertThat(effects.first { it.day == DayOfWeek.TUESDAY }.readings).isEqualTo(12)
    }

    @Test
    fun `a real weekday pattern survives the exclusion`() {
        // The exclusion must not be able to flatten the thing the card is for. A genuinely heavy
        // Monday still reads heavy with the period days taken out from under it.
        val mondays = (0 until days).map { start.plusDays(it.toLong()) }
            .filter { it.dayOfWeek == DayOfWeek.MONDAY }
            .toSet()
        val effects = Analytics.weekdayEffects(series(mondays, spikeGrams = 400), excluded = periodDays)

        assertThat(effects.first { it.day == DayOfWeek.MONDAY }.averageDeviationGrams)
            .isGreaterThan(100.0)
    }

    @Test
    fun `excluding nothing is the behaviour it always had`() {
        val default = Analytics.weekdayEffects(series(periodDays, spikeGrams = 500))
        val explicit = Analytics.weekdayEffects(series(periodDays, spikeGrams = 500), excluded = emptySet())

        assertThat(default).isEqualTo(explicit)
    }

    @Test
    fun `the smoothed line still carries what the average no longer does`() {
        // Worth stating rather than hiding. Excluding a morning from the average does not take
        // the water back out of the line that morning was measured against, so the days around a
        // period keep a little of it. Twelve grams on a spike of five hundred is the size of what
        // is left, and the card is drawn in tenths of a kilogram.
        val smoothed = TrendEngine.computeSeries(
            (0 until days).map { day ->
                val date = start.plusDays(day.toLong())
                DailyWeight(date, 80_000 + if (date in periodDays) 500 else 0)
            },
            TrendEngine.DEFAULT_WINDOW_DAYS,
        )

        val residual = Analytics.weekdayEffects(smoothed, excluded = periodDays)
            .maxOf { abs(it.averageDeviationGrams) }

        assertThat(residual).isLessThan(20.0)
        assertThat(residual)
            .isLessThan(Analytics.weekdayEffects(smoothed).maxOf { abs(it.averageDeviationGrams) })
    }
}
