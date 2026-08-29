package com.weighttrack.ui.charts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        SectionHeading("What moved with your weight")
        Spacer(Modifier.height(4.dp))
        associations.steps?.takeIf { it.isNotable }?.let {
            Text(text = stepsSentence(it), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
        }
        associations.sleep?.takeIf { it.isNotable }?.let {
            Text(text = sleepSentence(it), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            // The only honest framing. Two things moving together over a couple of months of one
            // person's numbers is a thing worth noticing and not a thing worth acting on.
            text = "These are patterns in your own numbers, not causes. Plenty of things move together for reasons that have nothing to do with each other.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun stepsSentence(association: Insights.Association): String = if (association.isInverse) {
    "Over ${association.weeks} weeks, the weeks you walked more were the weeks your weight came down faster."
} else {
    "Over ${association.weeks} weeks, the weeks you walked more were the weeks your weight went up more. Worth a look at what else those weeks had in common."
}

internal fun sleepSentence(association: Insights.Association): String = if (association.isInverse) {
    "Over ${association.weeks} weeks, the weeks you slept more were the weeks your weight came down faster."
} else {
    "Over ${association.weeks} weeks, the weeks you slept more were the weeks your weight went up more."
}
