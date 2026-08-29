package com.weighttrack.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.weighttrack.core.share.MilestoneCard

/**
 * Shows what the card will say before it says it anywhere.
 *
 * The preview is the point. Somebody is about to put this in front of other people, and finding
 * out what is on it afterwards is too late.
 */
@Composable
fun ShareProgressDialog(
    content: MilestoneCard.Content,
    includeWeight: Boolean,
    onIncludeWeightChange: (Boolean) -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share your progress") },
        text = {
            Column {
                Text(content.headline, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = content.subhead,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = content.footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Include what I weigh",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = includeWeight, onCheckedChange = onIncludeWeightChange)
                }
                Text(
                    // Off every time it opens, never remembered. A setting that stays on is one
                    // somebody forgets they turned on.
                    text = "Off unless you turn it on, and off again next time. The card shows how far you have come, not what the scale says.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Holds the dialog's own state, which is deliberately forgotten each time it closes. */
@Composable
fun rememberShareState(): ShareState = remember { ShareState() }

class ShareState {
    var includeWeight by mutableStateOf(false)
        private set

    var showing by mutableStateOf(false)
        private set

    fun open() {
        // Reset on open rather than on close, so it is off however the last one ended.
        includeWeight = false
        showing = true
    }

    fun chooseIncludeWeight(value: Boolean) {
        includeWeight = value
    }

    fun close() {
        showing = false
        includeWeight = false
    }
}
