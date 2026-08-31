package com.weighttrack.wear

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.DailyWeight
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.prefs.AppSettings
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class WearSummaryBuilderTest {

    private val start = LocalDate.of(2026, 8, 1)

    private fun daily(vararg grams: Int): List<DailyWeight> =
        grams.mapIndexed { index, value -> DailyWeight(start.plusDays(index.toLong()), value) }

    private fun entry(grams: Int, date: LocalDate) = WeightEntry(
        id = 1,
        timestamp = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
        zoneOffset = ZoneOffset.UTC,
        localDate = date,
        grams = grams,
        source = EntrySource.MANUAL,
        clientRecordId = "a",
    )

    private fun summary(
        settings: AppSettings = AppSettings(),
        grams: IntArray = intArrayOf(83_000, 82_800, 82_600, 82_400, 82_200, 82_000, 81_800, 81_600),
        goalGrams: Int? = 78_000,
    ) = WearSummaryBuilder.build(
        settings = settings,
        series = TrendEngine.computeSeries(daily(*grams), settings.trendWindowDays),
        latest = entry(grams.last(), start.plusDays(grams.size - 1L)),
        goalGrams = goalGrams,
        entryCount = grams.size,
        // The last day the fixture covers, so "this week" is the week the readings are in
        // rather than whichever week the machine running the test happens to be in.
        today = start.plusDays(grams.size - 1L),
    )

    @Test
    fun `the watch gets the trend, the week and the goal in grams`() {
        val built = summary()

        assertThat(built.hidden).isFalse()
        assertThat(built.hasData).isTrue()
        assertThat(built.trendGrams).isNotNull()
        assertThat(built.latestGrams).isEqualTo(81_600)
        assertThat(built.goalGrams).isEqualTo(78_000)
        assertThat(built.lastLoggedEpochDay).isEqualTo(start.plusDays(7).toEpochDay())
        // Losing weight, so the week's change has to be negative on the watch too.
        assertThat(built.weekChangeGrams!!).isLessThan(0.0)
    }

    @Test
    fun `a week with nothing logged in it says nothing rather than zero`() {
        // Zero reads as a week held steady. A week nobody stepped on the scale in is not that,
        // and the watch has room for one number.
        val built = WearSummaryBuilder.build(
            settings = AppSettings(),
            series = TrendEngine.computeSeries(daily(83_000, 82_800), AppSettings().trendWindowDays),
            latest = entry(82_800, start.plusDays(1)),
            goalGrams = 78_000,
            entryCount = 2,
            today = start.plusMonths(2),
        )

        assertThat(built.weekChangeGrams).isNull()
    }

    @Test
    fun `the unit follows the phone so the crown steps in the right one`() {
        val built = summary(settings = AppSettings(weightUnit = WeightUnit.ST_LB))

        assertThat(built.weightUnit).isEqualTo(WeightUnit.ST_LB)
    }

    @Test
    fun `the app lock leaves the watch with nothing to draw`() {
        // A weight on a wrist is exactly what the lock exists to keep off a glanceable surface.
        val built = summary(settings = AppSettings(appLockEnabled = true, weightUnit = WeightUnit.LB))

        assertThat(built.hidden).isTrue()
        assertThat(built.hasData).isFalse()
        assertThat(built.trendGrams).isNull()
        assertThat(built.latestGrams).isNull()
        assertThat(built.weekChangeGrams).isNull()
        assertThat(built.goalGrams).isNull()
        assertThat(built.lastLoggedEpochDay).isNull()
        // The unit still travels, so the picker steps correctly the moment the lock comes off.
        assertThat(built.weightUnit).isEqualTo(WeightUnit.LB)
    }

    @Test
    fun `no readings yet is not the same as locked`() {
        val built = WearSummaryBuilder.build(
            settings = AppSettings(),
            series = null,
            latest = null,
            goalGrams = null,
            entryCount = 0,
        )

        assertThat(built.hidden).isFalse()
        assertThat(built.hasData).isFalse()
        assertThat(built.startingGrams).isNull()
    }

    @Test
    fun `a single reading gives the watch a weight but no week`() {
        val built = summary(grams = intArrayOf(82_500), goalGrams = null)

        assertThat(built.startingGrams).isNotNull()
        assertThat(built.entryCount).isEqualTo(1)
        assertThat(built.goalGrams).isNull()
    }
}
