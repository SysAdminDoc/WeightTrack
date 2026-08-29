package com.weighttrack.ui.fasting

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.Fast
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FastingHistoryTest {

    private val start: Instant = Instant.parse("2026-01-01T20:00:00Z")

    private fun finished(hours: Long, targetHours: Int = 16, id: Long = hours) = Fast(
        id = id,
        start = start,
        end = start.plus(Duration.ofHours(hours)),
        targetMinutes = targetHours * 60,
    )

    @Test
    fun `history is summarised without reading the clock`() {
        val history = FastingViewModel.summarise(
            listOf(finished(hours = 18), finished(hours = 12), finished(hours = 16)),
        )

        assertThat(history.recorded).isEqualTo(3)
        assertThat(history.reached).isEqualTo(2)
        assertThat(history.longest).isEqualTo(Duration.ofHours(18))
        assertThat(history.fasts.map { it.length })
            .containsExactly(Duration.ofHours(18), Duration.ofHours(12), Duration.ofHours(16))
            .inOrder()
        assertThat(history.fasts.map { it.reachedTarget })
            .containsExactly(true, false, true)
            .inOrder()
    }

    @Test
    fun `an empty history has no longest fast`() {
        val history = FastingViewModel.summarise(emptyList())

        assertThat(history.isEmpty).isTrue()
        assertThat(history.recorded).isEqualTo(0)
        assertThat(history.reached).isEqualTo(0)
        assertThat(history.longest).isNull()
    }

    @Test
    fun `a recorded length does not grow with the clock`() {
        val row = FastingViewModel.summarise(listOf(finished(hours = 16))).fasts.single()

        // The old screen recomputed this against "now" every second; the value must be fixed.
        assertThat(row.length).isEqualTo(Duration.ofHours(16))
        assertThat(row.id).isEqualTo(16L)
    }

    @Test
    fun `a target is described from the fast rather than the tapped preset`() {
        // The decision, not the wording. What the words are is a matter for whoever translates
        // the app; which of them applies is a matter for the app.
        assertThat(fastTarget(16 * 60)).isEqualTo(FastTarget.Hours(16))
        assertThat(fastTarget(60)).isEqualTo(FastTarget.OneHour)
        // The case the preset lookup returns null for, which used to caption the ring wrongly.
        assertThat(fastTarget(17 * 60 + 30)).isEqualTo(FastTarget.HoursAndMinutes(17, 30))
        assertThat(fastTarget(45)).isEqualTo(FastTarget.Minutes(45))
        assertThat(fastTarget(0)).isEqualTo(FastTarget.None)
    }
}
