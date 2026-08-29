package com.weighttrack.ui.charts

import com.google.common.truth.Truth.assertThat
import com.weighttrack.health.DailyActivity
import org.junit.Test
import java.time.LocalDate

class ActivityStateTest {

    private val day0: LocalDate = LocalDate.of(2026, 1, 1)

    private fun day(offset: Int, steps: Long?, kcal: Double?) =
        DailyActivity(day0.plusDays(offset.toLong()), steps, kcal)

    @Test
    fun `averages ignore days with nothing recorded`() {
        // A day the watch was on the charger is not a day of zero steps. Counting it as zero
        // would drag the average down and misrepresent how much someone actually moved.
        val state = ActivityState(
            status = ActivityStatus.READY,
            days = listOf(
                day(0, 10_000, 400.0),
                day(1, null, null),
                day(2, 6_000, 200.0),
            ),
        )
        assertThat(state.averageSteps).isEqualTo(8_000)
        assertThat(state.averageActiveKilocalories).isWithin(1e-9).of(300.0)
    }

    @Test
    fun `steps and calories are averaged independently`() {
        // A watch can report steps without calories, so one missing figure must not discard
        // the other on that day.
        val state = ActivityState(
            status = ActivityStatus.READY,
            days = listOf(
                day(0, 10_000, null),
                day(1, 5_000, 300.0),
            ),
        )
        assertThat(state.averageSteps).isEqualTo(7_500)
        assertThat(state.averageActiveKilocalories).isWithin(1e-9).of(300.0)
    }

    @Test
    fun `nothing recorded means no average rather than zero`() {
        val state = ActivityState(
            status = ActivityStatus.READY,
            days = listOf(day(0, null, null)),
        )
        assertThat(state.averageSteps).isNull()
        assertThat(state.averageActiveKilocalories).isNull()
    }

    @Test
    fun `an empty state reports no averages`() {
        val state = ActivityState()
        assertThat(state.status).isEqualTo(ActivityStatus.LOADING)
        assertThat(state.averageSteps).isNull()
        assertThat(state.averageActiveKilocalories).isNull()
    }
}
