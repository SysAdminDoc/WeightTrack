package com.weighttrack.ui.charts

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.Insights
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the card actually says.
 *
 * The wording is the whole feature here. A sentence that reads as advice, or as a cause, is a
 * defect however correct the arithmetic behind it.
 */
@RunWith(RobolectricTestRunner::class)
class AssociationSentenceTest {

    private fun association(coefficient: Double, weeks: Int = 8) =
        Insights.Association(coefficient = coefficient, weeks = weeks)

    /** Resolves what the card would actually show, so these read the shipped English. */
    private fun words(id: Int, weeks: Int = 8): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, weeks)

    @Test
    fun `walking more alongside losing faster is described as happening together`() {
        val sentence = words(stepsSentence(association(-0.8)))

        assertThat(sentence).contains("the weeks you walked more")
        assertThat(sentence).contains("came down faster")
        // Never "because", "caused", or "lose weight by".
        assertThat(sentence.lowercase()).doesNotContain("because")
        assertThat(sentence.lowercase()).doesNotContain("cause")
    }

    @Test
    fun `the other direction is not dressed up as good news`() {
        val sentence = words(stepsSentence(association(0.8)))

        assertThat(sentence).contains("went up more")
        assertThat(sentence.lowercase()).doesNotContain("because")
    }

    @Test
    fun `sleep reads the same way round`() {
        assertThat(words(sleepSentence(association(-0.7)))).contains("came down faster")
        assertThat(words(sleepSentence(association(0.7)))).contains("went up more")
        assertThat(words(sleepSentence(association(-0.7))).lowercase()).doesNotContain("should")
    }

    @Test
    fun `how many weeks it is made of is always said`() {
        // Six weeks of one person's numbers is a small thing, and saying so is the difference
        // between a note and a claim.
        assertThat(words(stepsSentence(association(-0.8, weeks = 6)), weeks = 6)).contains("6 weeks")
        assertThat(words(sleepSentence(association(-0.8, weeks = 11)), weeks = 11)).contains("11 weeks")
    }

    @Test
    fun `a weak association is not notable`() {
        // On six or eight weekly points, 0.4 turns up by chance often enough that reporting it
        // would mostly be reporting chance.
        assertThat(association(0.4).isNotable).isFalse()
        assertThat(association(-0.4).isNotable).isFalse()
        assertThat(association(0.65).isNotable).isTrue()
    }

    @Test
    fun `nothing notable means no card at all`() {
        assertThat(AssociationState().hasAny).isFalse()
        assertThat(AssociationState(steps = association(0.3)).hasAny).isFalse()
        assertThat(AssociationState(steps = association(-0.9)).hasAny).isTrue()
    }
}
