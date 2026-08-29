package com.weighttrack.ui.goal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.GoalProjection
import com.weighttrack.core.math.NoEtaReason
import com.weighttrack.core.model.GoalDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the sheet says about a date, or about the lack of one.
 *
 * A projected date is the thing people most want and least trust in an app like this, so the
 * wording is the feature. A blank where a date should be reads as the app being broken; naming
 * which of the five situations it is turns it into information.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectionExplanationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun projection(reason: NoEtaReason?) = GoalProjection(
        direction = GoalDirection.LOSE,
        startGrams = 90_000,
        targetGrams = 80_000,
        currentTrendGrams = 85_000.0,
        progressFraction = 0.5,
        remainingGrams = -5_000.0,
        etaDays = if (reason == null) 70.0 else null,
        etaDaysOptimistic = null,
        etaDaysPessimistic = null,
        movingTowardGoal = reason == null,
        reached = reason == NoEtaReason.REACHED,
        noEtaReason = reason,
        fittedDays = 21,
        fittedGramsPerDay = -70.0,
    )

    private fun words(reason: NoEtaReason?): String =
        context.getString(explanationFor(projection(reason)))

    @Test
    fun `every reason has something to say, and they all differ`() {
        val all = (NoEtaReason.entries.map { words(it) } + words(null))

        assertThat(all).hasSize(NoEtaReason.entries.size + 1)
        assertThat(all.toSet()).hasSize(NoEtaReason.entries.size + 1)
        all.forEach { assertThat(it).isNotEmpty() }
    }

    @Test
    fun `a date explains what it was made from`() {
        val explained = words(null)

        assertThat(explained).contains("trend")
        // The honest part: nothing about the person goes into it.
        assertThat(explained.lowercase()).contains("not your age")
    }

    @Test
    fun `a level trend says the date would be invented`() {
        assertThat(words(NoEtaReason.FLAT).lowercase()).contains("level")
    }

    @Test
    fun `going the wrong way is said plainly rather than dressed up`() {
        val explained = words(NoEtaReason.WRONG_WAY).lowercase()

        assertThat(explained).contains("away from the target")
        // Never a telling-off, and never advice.
        assertThat(explained).doesNotContain("should")
        assertThat(explained).doesNotContain("need to")
    }

    @Test
    fun `too few readings reads as a matter of time, not a fault`() {
        val explained = words(NoEtaReason.NOT_ENOUGH_DATA).lowercase()

        assertThat(explained).contains("not enough")
        assertThat(explained).doesNotContain("error")
    }

    @Test
    fun `nothing here promises a date it cannot give`() {
        NoEtaReason.entries.forEach { reason ->
            val explained = words(reason).lowercase()
            assertThat(explained).doesNotContain("you will reach")
            assertThat(explained).doesNotContain("guaranteed")
        }
    }
}
