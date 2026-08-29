package com.weighttrack.core.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The weekly rhythm, with the underlying loss or gain taken out.
 *
 * That last part is the whole difficulty. The trend line lags a steady loss by design, so every
 * reading sits the same distance below it, and shown uncentred the card tells somebody losing
 * weight that all seven of their days are unusual.
 */
class WeekdayEffectTest {

    private val start = LocalDate.of(2026, 6, 1) // A Monday.

    private fun series(
        days: Int,
        startGrams: Int = 85_000,
        gramsPerDay: Double = 0.0,
        heavyOn: DayOfWeek? = null,
        bump: Int = 0,
    ): TrendSeries {
        val daily = (0 until days).map { day ->
            val date = start.plusDays(day.toLong())
            val extra = if (date.dayOfWeek == heavyOn) bump else 0
            DailyWeight(date, (startGrams + gramsPerDay * day).toInt() + extra)
        }
        return TrendEngine.computeSeries(daily, TrendEngine.DEFAULT_WINDOW_DAYS)
    }

    private fun effects(series: TrendSeries) = Analytics.weekdayEffects(series)

    private fun spread(series: TrendSeries): Double {
        val values = effects(series).map { it.averageDeviationGrams }
        return values.max() - values.min()
    }

    @Test
    fun `a heavy Saturday shows up as a heavy Saturday`() {
        // The single most common reason somebody decides a diet has failed on a Sunday morning.
        val effects = effects(series(days = 70, heavyOn = DayOfWeek.SATURDAY, bump = 900))

        assertThat(effects.maxByOrNull { it.averageDeviationGrams }!!.day)
            .isEqualTo(DayOfWeek.SATURDAY)
        assertThat(spread(series(days = 70, heavyOn = DayOfWeek.SATURDAY, bump = 900)))
            .isGreaterThan(500.0)
    }

    @Test
    fun `a steady weight has no weekday pattern worth mentioning`() {
        assertThat(spread(series(days = 70))).isLessThan(50.0)
    }

    @Test
    fun `a steady loss does not make every day look unusual`() {
        // Uncentred, all seven days read several hundred grams below the line, and the card says
        // so. That is the smoothing, not the person's week.
        val effects = effects(series(days = 70, gramsPerDay = -100.0))

        for (effect in effects) {
            assertThat(effect.averageDeviationGrams).isWithin(60.0).of(0.0)
        }
    }

    @Test
    fun `a steady gain does not either`() {
        val effects = effects(series(days = 70, gramsPerDay = 100.0))

        for (effect in effects) {
            assertThat(effect.averageDeviationGrams).isWithin(60.0).of(0.0)
        }
    }

    @Test
    fun `the days still differ from each other by the right amount`() {
        // Centring moves all seven by the same amount, so the gaps between them are untouched,
        // which is the number the card actually talks about.
        val heavy = series(days = 70, gramsPerDay = -100.0, heavyOn = DayOfWeek.SATURDAY, bump = 800)

        assertThat(spread(heavy)).isGreaterThan(400.0)
        assertThat(effects(heavy).maxByOrNull { it.averageDeviationGrams }!!.day)
            .isEqualTo(DayOfWeek.SATURDAY)
    }

    @Test
    fun `a day weighed only once is left out`() {
        val daily = (0 until 70).map { start.plusDays(it.toLong()) }
            .filter { it.dayOfWeek != DayOfWeek.SUNDAY || it == start.plusDays(6) }
            .map { DailyWeight(it, 85_000) }

        val effects = Analytics.weekdayEffects(TrendEngine.computeSeries(daily, TrendEngine.DEFAULT_WINDOW_DAYS))

        assertThat(effects.map { it.day }).doesNotContain(DayOfWeek.SUNDAY)
    }

    @Test
    fun `nothing weighed is no effects rather than a crash`() {
        assertThat(Analytics.weekdayEffects(TrendSeries(emptyList(), 0.1))).isEmpty()
    }
}
