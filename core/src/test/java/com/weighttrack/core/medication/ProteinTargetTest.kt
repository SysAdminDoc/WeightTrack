package com.weighttrack.core.medication

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProteinTargetTest {

    @Test
    fun `eighty kilograms asks for ninety six to a hundred and twenty eight grams`() {
        assertThat(ProteinTarget.dailyGrams(80_000)).isEqualTo(96..128)
    }

    @Test
    fun `a body mass nobody has gives no target at all`() {
        // The mass comes off the trend line, and one mistyped reading drags that by kilograms.
        // A target worked out from four kilograms is worse than none.
        assertThat(ProteinTarget.dailyGrams(4_000)).isNull()
        assertThat(ProteinTarget.dailyGrams(0)).isNull()
        assertThat(ProteinTarget.dailyGrams(900_000)).isNull()
    }

    @Test
    fun `the range never runs backwards`() {
        (20_000..400_000 step 1_000).forEach { grams ->
            val range = ProteinTarget.dailyGrams(grams)
            assertThat(range).isNotNull()
            assertThat(range!!.first).isAtMost(range.last)
        }
    }
}
