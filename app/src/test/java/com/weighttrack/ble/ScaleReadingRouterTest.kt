package com.weighttrack.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScaleReadingRouterTest {

    @Test
    fun `a reading near the last one is recorded without asking`() {
        val match = ScaleReadingRouter.match(grams = 82_900, lastKnownGrams = 82_500)

        assertThat(match).isEqualTo(ScaleMatch.MATCHES)
        assertThat(ScaleReadingRouter.recordsWithoutAsking(match)).isTrue()
    }

    @Test
    fun `someone else stepping on the scale is asked about rather than filed`() {
        // The reason this exists: a shared bathroom scale. Quietly recording a partner's weight
        // puts a step change through a trend that takes weeks to work back out.
        val match = ScaleReadingRouter.match(grams = 62_000, lastKnownGrams = 82_500)

        assertThat(match).isEqualTo(ScaleMatch.OUT_OF_RANGE)
        assertThat(ScaleReadingRouter.recordsWithoutAsking(match)).isFalse()
    }

    @Test
    fun `the first ever reading has nothing to be suspicious of`() {
        val match = ScaleReadingRouter.match(grams = 82_500, lastKnownGrams = null)

        assertThat(match).isEqualTo(ScaleMatch.NO_HISTORY)
        assertThat(ScaleReadingRouter.recordsWithoutAsking(match)).isTrue()
    }

    @Test
    fun `a weight no person has is not offered at all`() {
        // A scale settling, or a bad packet, reads as a few hundred grams or as hundreds of
        // kilograms. Neither is worth putting in front of someone.
        assertThat(ScaleReadingRouter.match(500, 82_500)).isEqualTo(ScaleMatch.IMPLAUSIBLE)
        assertThat(ScaleReadingRouter.match(500_000, 82_500)).isEqualTo(ScaleMatch.IMPLAUSIBLE)
        assertThat(ScaleReadingRouter.match(0, null)).isEqualTo(ScaleMatch.IMPLAUSIBLE)
        assertThat(ScaleReadingRouter.recordsWithoutAsking(ScaleMatch.IMPLAUSIBLE)).isFalse()
    }

    @Test
    fun `the tolerance is inclusive at its edge`() {
        val tolerance = ScaleReadingRouter.DEFAULT_TOLERANCE_GRAMS

        assertThat(ScaleReadingRouter.match(82_500 + tolerance, 82_500))
            .isEqualTo(ScaleMatch.MATCHES)
        assertThat(ScaleReadingRouter.match(82_500 - tolerance, 82_500))
            .isEqualTo(ScaleMatch.MATCHES)
        assertThat(ScaleReadingRouter.match(82_500 + tolerance + 1, 82_500))
            .isEqualTo(ScaleMatch.OUT_OF_RANGE)
    }

    @Test
    fun `a fortnight away still reads as the same person`() {
        // Eight kilograms has to cover a holiday and heavy clothing, or the app asks "is this
        // you" every time someone comes back from anywhere.
        assertThat(ScaleReadingRouter.match(86_000, 82_500)).isEqualTo(ScaleMatch.MATCHES)
        assertThat(ScaleReadingRouter.match(79_000, 82_500)).isEqualTo(ScaleMatch.MATCHES)
    }
}
