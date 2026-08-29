package com.weighttrack.ui.home

import com.weighttrack.core.share.MilestoneCard
import com.weighttrack.domain.ProgressSnapshot
import java.time.LocalDate

/**
 * Picks what a share card should be about.
 *
 * Three cases, in order of how much they are worth saying. A milestone that has actually been
 * passed is the best of them; the distance travelled since the first reading is next; and if
 * neither exists there is nothing worth a card, which is an answer rather than a failure.
 */
fun milestoneCardFor(
    snapshot: ProgressSnapshot,
    includeWeight: Boolean,
    today: LocalDate = LocalDate.now(),
): MilestoneCard.Content? {
    if (!snapshot.hasData) return null
    val unit = snapshot.settings.weightUnit
    val start = snapshot.series.points.firstOrNull() ?: return null
    val startGrams = start.actualGrams ?: start.trendGrams.toInt()

    // The most recent one they have passed, not the next one ahead. A card about something that
    // has not happened yet would be somebody sharing a plan as though it were a result.
    val passed = snapshot.milestones.lastOrNull { it.reached }
    if (passed != null) {
        return MilestoneCard.forMilestone(
            milestone = passed,
            startGrams = startGrams,
            series = snapshot.series,
            unit = unit,
            includeWeight = includeWeight,
            today = today,
        )
    }

    val now = snapshot.displayGrams ?: return null
    val days = (today.toEpochDay() - start.date.toEpochDay()).toInt()
    // A day or two of readings is not a stretch of progress. Somebody who started yesterday has
    // nothing to share yet and should not be handed a card saying otherwise.
    if (days < MIN_DAYS_FOR_PROGRESS) return null
    return MilestoneCard.forProgress(
        fromGrams = startGrams,
        toGrams = now,
        days = days,
        series = snapshot.series,
        unit = unit,
        includeWeight = includeWeight,
        today = today,
    )
}

/** Below this there is no story yet, only a couple of mornings. */
const val MIN_DAYS_FOR_PROGRESS = 14
