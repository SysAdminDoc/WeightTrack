package com.weighttrack.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class WeightPlausibilityTest {

    private val now = Instant.parse("2026-08-31T12:00:00Z")

    @Test
    fun `the supported weight boundaries are inclusive`() {
        assertThat(WeightPlausibility.problem(20_000, Instant.EPOCH, now)).isNull()
        assertThat(
            WeightPlausibility.problem(400_000, now.plusSeconds(24 * 60 * 60L), now),
        ).isNull()
    }

    @Test
    fun `weights outside the supported range are refused`() {
        assertThat(WeightPlausibility.problem(19_999, now, now))
            .isEqualTo(WeightPlausibility.Problem.WEIGHT)
        assertThat(WeightPlausibility.problem(400_001, now, now))
            .isEqualTo(WeightPlausibility.Problem.WEIGHT)
    }

    @Test
    fun `dates before 1970 and over one day ahead are refused`() {
        assertThat(WeightPlausibility.problem(80_000, Instant.EPOCH.minusSeconds(1), now))
            .isEqualTo(WeightPlausibility.Problem.TIMESTAMP)
        assertThat(
            WeightPlausibility.problem(80_000, now.plusSeconds(24 * 60 * 60L + 1), now),
        ).isEqualTo(WeightPlausibility.Problem.TIMESTAMP)
    }
}
