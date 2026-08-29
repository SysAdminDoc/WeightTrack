package com.weighttrack.ui.charts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.math.Insights
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading

/**
 * What moved alongside somebody's weight, when anything did.
 *
 * Careful about the claim it makes, because there is only one honest one available. Six or eight
 * weeks of one person's numbers can show that two things moved together. It cannot show that one
 * caused the other, and the card says so rather than leaving somebody to assume it.
 */
@Composable
fun AssociationCard(associations: AssociationState) {
    if (!associations.hasAny) return
    SectionCard {
        SectionHeading(stringResource(R.string.charts_what_moved_with_your_weight))
        Spacer(Modifier.height(4.dp))
        associations.steps?.takeIf { it.isNotable }?.let {
            Text(
                text = stringResource(stepsSentence(it), it.weeks),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
        }
        associations.sleep?.takeIf { it.isNotable }?.let {
            Text(
                text = stringResource(sleepSentence(it), it.weeks),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            // The only honest framing. Two things moving together over a couple of months of one
            // person's numbers is a thing worth noticing and not a thing worth acting on.
            text = stringResource(R.string.charts_these_are_patterns_in_your_own),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Which sentence describes this association.
 *
 * Answers with the resource rather than the words, so the choice stays a plain function a test can
 * check, and the words themselves stay in the file translators work from.
 */
@StringRes
internal fun stepsSentence(association: Insights.Association): Int = if (association.isInverse) {
    R.string.associationcard_over_weeks_weeks_you_walked_more_2
} else {
    R.string.associationcard_over_weeks_weeks_you_walked_more
}

@StringRes
internal fun sleepSentence(association: Insights.Association): Int = if (association.isInverse) {
    R.string.associationcard_over_weeks_weeks_you_slept_more_2
} else {
    R.string.associationcard_over_weeks_weeks_you_slept_more
}
