package com.weighttrack.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.data.io.BackupPreview
import com.weighttrack.data.repo.Profile
import com.weighttrack.ui.components.LabelledValue

/**
 * What is in the file, before anything is written.
 *
 * A restore reaches into every screen at once and there is no undo for it, so the counts are put
 * in front of somebody first. It also says what a restore does to what is already here, because
 * "restore" reads to most people as "replace" and this one merges.
 */
@Composable
internal fun RestoreDialog(
    preview: BackupPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.restore_merges),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                // Zero is shown rather than hidden: a backup with no diary in it is a fact worth
                // seeing before it lands on a phone that has one.
                LabelledValue(
                    stringResource(R.string.restore_count_profiles),
                    preview.profiles.toString(),
                )
                LabelledValue(
                    stringResource(R.string.restore_count_weights),
                    preview.weights.toString(),
                )
                LabelledValue(
                    stringResource(R.string.restore_count_measurements),
                    preview.measurements.toString(),
                )
                LabelledValue(stringResource(R.string.restore_count_water), preview.water.toString())
                LabelledValue(stringResource(R.string.restore_count_fasts), preview.fasts.toString())
                LabelledValue(stringResource(R.string.restore_count_goals), preview.goals.toString())
                LabelledValue(stringResource(R.string.restore_count_foods), preview.foods.toString())
                LabelledValue(
                    stringResource(R.string.restore_count_food_log),
                    preview.foodLog.toString(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.restore_photos_excluded),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** One text field and two buttons, for naming a person. */
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_name)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text) },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** The clock face behind the reminder time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderTimeDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onSet: (hour: Int, minute: Int) -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = profile.reminderHour,
        initialMinute = profile.reminderMinute,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reminder_time)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { TimePicker(state = timeState) }
        },
        confirmButton = {
            TextButton(onClick = { onSet(timeState.hour, timeState.minute) }) {
                Text(stringResource(R.string.common_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
