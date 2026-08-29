package com.weighttrack.widget

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.DailyWeight
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import java.time.LocalDate

class WeightWidgetDataTest {

    private val day0: LocalDate = LocalDate.of(2026, 1, 1)

    private fun series() = TrendEngine.computeSeries(
        (0..13).map { DailyWeight(day0.plusDays(it.toLong()), 90_000 - it * 100) },
    )

    @Test
    fun `the widget shows the trend when the app is not locked`() {
        val data = buildWidgetData(appLockEnabled = false, unit = WeightUnit.KG, series = series())
        assertThat(data.hidden).isFalse()
        assertThat(data.trendGrams).isNotNull()
        assertThat(data.weekChangeGrams).isNotNull()
        assertThat(data.lastLogged).isEqualTo(day0.plusDays(13))
    }

    @Test
    fun `the app lock hides every reading from the widget`() {
        // The home screen is exactly where someone else can read it, so nothing about the
        // weight may survive into the widget while the lock is on.
        val data = buildWidgetData(appLockEnabled = true, unit = WeightUnit.KG, series = series())
        assertThat(data.hidden).isTrue()
        assertThat(data.trendGrams).isNull()
        assertThat(data.weekChangeGrams).isNull()
        assertThat(data.lastLogged).isNull()
    }

    @Test
    fun `an empty log is not reported as locked`() {
        val data = buildWidgetData(appLockEnabled = false, unit = WeightUnit.KG, series = null)
        assertThat(data.hidden).isFalse()
        assertThat(data.trendGrams).isNull()
    }

    @Test
    fun `the unit still comes through while locked so the widget can render`() {
        val data = buildWidgetData(appLockEnabled = true, unit = WeightUnit.LB, series = series())
        assertThat(data.unit).isEqualTo(WeightUnit.LB)
    }
}
