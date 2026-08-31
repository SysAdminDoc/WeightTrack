package com.weighttrack.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weighttrack.ui.theme.StatLabelStyle
import com.weighttrack.ui.theme.StatValueStyle

/** A restrained grouped surface for content that needs separation from the page. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f),
        ),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Content that belongs to the page rather than to a card. Quiet spacing and one hairline do the
 * grouping, which keeps long settings and history surfaces from becoming stacks of boxes.
 */
@Composable
fun LedgerSection(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    showDivider: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A compact rectangular selector shared by ranges and settings choices. */
@Composable
fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        // 44 dp looks right and is under the 48 dp a finger needs. minimumInteractiveComponentSize
        // grows the touch target without growing the box that is drawn.
        modifier = modifier.height(48.dp).minimumInteractiveComponentSize(),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) accent.copy(alpha = 0.11f) else Color.Transparent,
        contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(
            text = label.uppercase(),
            style = StatLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = StatValueStyle, color = accent, maxLines = 1)
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * Goal progress with the milestones drawn on top of it, so a long goal reads as a series of
 * short ones rather than a bar that barely moves.
 */
@Composable
fun GoalProgressBar(
    progress: Float,
    milestoneFractions: List<Float>,
    reachedCount: Int,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    fillColor: Color = MaterialTheme.colorScheme.primary,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        label = "GoalProgress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(fillColor),
        )
        milestoneFractions.forEachIndexed { index, fraction ->
            val reached = index < reachedCount
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (reached) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                        ),
                )
            }
        }
    }
}

/** An empty-state block that says what to do next rather than just that there is nothing here. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** A label and value on one line, the workhorse of the settings and detail screens. */
@Composable
fun LabelledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
    }
}
