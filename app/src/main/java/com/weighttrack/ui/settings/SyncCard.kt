package com.weighttrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.data.sync.SyncMode
import com.weighttrack.data.sync.SyncSettings
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionHeading

/**
 * Sync, with no account anywhere in it.
 *
 * Two ways to do it and both are just a folder. The wording avoids the word "cloud" on purpose:
 * what is on offer is somewhere to put a file, and being clear about that is what lets somebody
 * understand where their weight history actually goes.
 */
@Composable
fun SyncCard(
    settings: SyncSettings,
    folderName: String?,
    syncing: Boolean,
    onPickFolder: () -> Unit,
    onUseWebDav: (String, String, String) -> Unit,
    onSyncNow: () -> Unit,
    onTurnOff: () -> Unit,
    onBackgroundChange: (Boolean) -> Unit,
    needsLocalNetwork: Boolean = false,
    onAllowLocalNetwork: () -> Unit = {},
    onPickCertificate: () -> Unit = {},
    onForgetCertificate: () -> Unit = {},
    devices: List<SyncDevice> = emptyList(),
    onDeviceRetiredChange: (String, Boolean) -> Unit = { _, _ -> },
) {
    var editingWebDav by remember { mutableStateOf(false) }

    SettingsSection {
        SectionHeading(stringResource(R.string.settings_sync_between_your_devices))
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_no_account_and_nothing_of_yours),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        when (settings.mode) {
            SyncMode.OFF -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickFolder) { Text(stringResource(R.string.settings_pick_a_folder)) }
                    OutlinedButton(onClick = { editingWebDav = true }) { Text(stringResource(R.string.settings_use_webdav)) }
                }
            }
            SyncMode.FOLDER -> {
                LabelledValue(stringResource(R.string.synccard_folder), folderName ?: stringResource(R.string.synccard_chosen))
                SyncStatus(settings, syncing, onSyncNow, onTurnOff, onBackgroundChange)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onPickFolder) { Text(stringResource(R.string.settings_pick_a_different_folder)) }
            }
            SyncMode.WEBDAV -> {
                LabelledValue(stringResource(R.string.synccard_server), settings.webDavUrl.orEmpty())
                LabelledValue(stringResource(R.string.synccard_username), settings.webDavUser.orEmpty())
                if (needsLocalNetwork) {
                    // Without this grant the socket never opens and every sync times out, which
                    // reads exactly like the server being switched off.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sync_needs_local_network),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onAllowLocalNetwork, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sync_allow_local_network))
                    }
                }
                SyncStatus(settings, syncing, onSyncNow, onTurnOff, onBackgroundChange)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.sync_certificate),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sync_certificate_explained),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (settings.webDavCertificate == null) {
                    TextButton(onClick = onPickCertificate) {
                        Text(stringResource(R.string.sync_certificate_pick))
                    }
                } else {
                    TextButton(onClick = onForgetCertificate) {
                        Text(stringResource(R.string.sync_certificate_forget))
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { editingWebDav = true }) { Text(stringResource(R.string.settings_change_the_details)) }
            }
        }

        if (settings.isOn && devices.isNotEmpty()) {
            SyncDevices(devices, onDeviceRetiredChange)
        }
    }

    if (editingWebDav) {
        WebDavDialog(
            settings = settings,
            onDismiss = { editingWebDav = false },
            onConfirm = { url, user, password ->
                editingWebDav = false
                onUseWebDav(url, user, password)
            },
        )
    }
}

/**
 * The devices sharing the folder, and which of them are still expected back.
 *
 * Here because a deletion is only forgotten once every device that is still expected has said it
 * has seen it, so a phone that has been lost or sold holds every tombstone open forever. Saying
 * it is gone is the only way to close them, and nobody can say that about a device they cannot
 * see. It never touches that device's readings, which is why it needs no dialog: it is one tap,
 * it says what it did, and the same tap puts it back.
 */
@Composable
private fun SyncDevices(
    devices: List<SyncDevice>,
    onRetiredChange: (String, Boolean) -> Unit,
) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = stringResource(R.string.sync_devices),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.sync_devices_explained),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    devices.forEach { device ->
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    text = if (device.isThisDevice) {
                        stringResource(R.string.sync_device_this_one, device.deviceId)
                    } else {
                        device.deviceId
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = when {
                        device.retired && !device.canBringBack ->
                            stringResource(R.string.sync_device_gone_too_long)
                        device.retired -> stringResource(R.string.sync_device_gone)
                        device.lastSeenAtUtcMillis <= 0 ->
                            stringResource(R.string.sync_device_never_seen)
                        else -> stringResource(
                            R.string.sync_device_last_seen,
                            com.weighttrack.ui.format.DateFormatters.fullDate(
                                java.time.Instant.ofEpochMilli(device.lastSeenAtUtcMillis)
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toLocalDate(),
                            ),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // This device is never retired from itself: it is plainly here, and the tombstone
            // rule would then be waiting on nobody at all. A device retired longer than the
            // tombstone floor is offered nothing either, because bringing it back would hand
            // every forgotten deletion straight back; the line above says so.
            if (!device.isThisDevice && (!device.retired || device.canBringBack)) {
                TextButton(onClick = { onRetiredChange(device.deviceId, !device.retired) }) {
                    Text(
                        stringResource(
                            if (device.retired) {
                                R.string.sync_device_bring_back
                            } else {
                                R.string.sync_device_forget
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncStatus(
    settings: SyncSettings,
    syncing: Boolean,
    onSyncNow: () -> Unit,
    onTurnOff: () -> Unit,
    onBackgroundChange: (Boolean) -> Unit,
) {
    settings.lastSyncMessage?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    // The shared row, which names the switch for a screen reader. A private copy here did not,
    // so the background switch was a control that announced nothing at all.
    ToggleRow(
        label = stringResource(R.string.settings_sync_in_the_background),
        checked = settings.syncInBackground,
        onCheckedChange = onBackgroundChange,
    )
    Text(
        // Deliberately not a promise about when. Android decides, and saying "every hour" when
        // the phone might sleep through four of them would be a lie somebody could catch.
        text = stringResource(R.string.settings_about_once_an_hour_when_the),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSyncNow, enabled = !syncing) {
            Text(if (syncing) stringResource(R.string.synccard_syncing) else stringResource(R.string.synccard_sync_now))
        }
        OutlinedButton(onClick = onTurnOff) { Text(stringResource(R.string.settings_turn_off)) }
    }
}

@Composable
private fun WebDavDialog(
    settings: SyncSettings,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var url by remember { mutableStateOf(settings.webDavUrl.orEmpty()) }
    var user by remember { mutableStateOf(settings.webDavUser.orEmpty()) }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_webdav)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_address)) },
                    placeholder = { Text(stringResource(R.string.settings_https_cloud_example_com_remote_php)) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_username)) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.settings_password)) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // Worth saying plainly. An app password can be revoked on its own, and this
                    // one is stored on the phone in the app's own data.
                    text = stringResource(R.string.settings_on_nextcloud_make_an_app_password),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url, user, password) },
                enabled = url.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
            ) { Text(stringResource(R.string.settings_use_this)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
