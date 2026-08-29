package com.weighttrack.ui.home

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.DailyWeight
import com.weighttrack.core.math.Milestone
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.TrendRate
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.domain.ProgressSnapshot
import org.junit.Test
import java.time.LocalDate

/**
 * Which card somebody gets, and whether they get one at all.
 *
 * The awkward case is the beginner. Two mornings of readings is not a story, and handing somebody
 * a card that says "0.4 kg down in 1 day" is the app inviting them to share water.
 */
class MilestoneCardContentTest {

    private val start = LocalDate.of(2026, 6, 1)
    private val today = LocalDate.of(2026, 8, 29)

    private fun snapshot(
        days: Int,
        gramsPerDay: Double = -50.0,
        milestones: List<Milestone> = emptyList(),
    ): ProgressSnapshot {
        val daily = (0 until days).map {
            DailyWeight(start.plusDays(it.toLong()), (90_000 + gramsPerDay * it).toInt())
        }
        val series = TrendEngine.computeSeries(daily, TrendEngine.DEFAULT_WINDOW_DAYS)
        return ProgressSnapshot.empty(AppSettings(weightUnit = WeightUnit.KG)).copy(
            entryCount = days,
            series = series,
            rate = TrendRate(gramsPerDay = gramsPerDay, standardErrorGramsPerDay = 0.0, sampleDays = days),
            milestones = milestones,
            nextMilestone = milestones.firstOrNull { !it.reached },
        )
    }

    private fun milestone(grams: Int, reachedOn: LocalDate?, index: Int = 1) =
        Milestone(grams = grams, index = index, total = 3, reachedOn = reachedOn)

    @Test
    fun `nothing logged means no card`() {
        assertThat(milestoneCardFor(snapshot(days = 0), includeWeight = false, today = today))
            .isNull()
    }

    @Test
    fun `two mornings is not a story`() {
        // A card saying "0.1 kg down in 1 day" is the app inviting somebody to share water.
        // Today has to be the second of those mornings, not months later.
        assertThat(
            milestoneCardFor(snapshot(days = 2), includeWeight = false, today = start.plusDays(1)),
        ).isNull()
    }

    @Test
    fun `a fortnight is the point at which there is something to say`() {
        assertThat(
            milestoneCardFor(snapshot(days = 14), includeWeight = false, today = start.plusDays(12)),
        ).isNull()
        assertThat(
            milestoneCardFor(snapshot(days = 14), includeWeight = false, today = start.plusDays(14)),
        ).isNotNull()
    }

    @Test
    fun `a fortnight of readings earns a card about the distance travelled`() {
        val content = milestoneCardFor(
            snapshot(days = 60),
            includeWeight = false,
            today = start.plusDays(59),
        )!!

        assertThat(content.headline).contains("down")
        assertThat(content.subhead).contains("days")
    }

    @Test
    fun `a milestone that has been passed wins over the plain stretch`() {
        val content = milestoneCardFor(
            snapshot(days = 60, milestones = listOf(milestone(88_000, start.plusDays(40)))),
            includeWeight = false,
            today = start.plusDays(59),
        )!!

        // 90 kg to 88 kg, which is the milestone rather than the whole stretch of 3 kg.
        assertThat(content.headline).contains("2")
    }

    @Test
    fun `a milestone still ahead is not treated as reached`() {
        // Sharing a plan as though it were a result.
        val content = milestoneCardFor(
            snapshot(days = 60, milestones = listOf(milestone(80_000, reachedOn = null))),
            includeWeight = false,
            today = start.plusDays(59),
        )!!

        assertThat(content.headline).doesNotContain("10")
    }

    @Test
    fun `the most recent milestone passed is the one on the card`() {
        val content = milestoneCardFor(
            snapshot(
                days = 90,
                milestones = listOf(
                    milestone(88_000, start.plusDays(30), index = 1),
                    milestone(86_000, start.plusDays(70), index = 2),
                    milestone(84_000, reachedOn = null, index = 3),
                ),
            ),
            includeWeight = false,
            today = start.plusDays(89),
        )!!

        // 90 to 86 is four kilograms, not the two of the first one.
        assertThat(content.headline).contains("4")
    }

    @Test
    fun `their weight is absent unless they asked for it`() {
        val withoutIt = milestoneCardFor(
            snapshot(days = 60, milestones = listOf(milestone(88_000, start.plusDays(40)))),
            includeWeight = false,
            today = start.plusDays(59),
        )!!
        val withIt = milestoneCardFor(
            snapshot(days = 60, milestones = listOf(milestone(88_000, start.plusDays(40)))),
            includeWeight = true,
            today = start.plusDays(59),
        )!!

        assertThat(withoutIt.footer).doesNotContain("88")
        assertThat(withIt.footer).contains("88")
    }
}
