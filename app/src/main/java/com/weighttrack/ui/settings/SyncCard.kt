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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
) {
    var editingWebDav by remember { mutableStateOf(false) }

    SettingsSection {
        SectionHeading("Sync between your devices")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "No account and nothing of yours on anybody's server but your own. Each device writes one file and reads the others, so put that folder somewhere both phones can see: a Syncthing folder, or a directory on your own Nextcloud.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        when (settings.mode) {
            SyncMode.OFF -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickFolder) { Text("Pick a folder") }
                    OutlinedButton(onClick = { editingWebDav = true }) { Text("Use WebDAV") }
                }
            }
            SyncMode.FOLDER -> {
                LabelledValue("Folder", folderName ?: "Chosen")
                SyncStatus(settings, syncing, onSyncNow, onTurnOff, onBackgroundChange)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onPickFolder) { Text("Pick a different folder") }
            }
            SyncMode.WEBDAV -> {
                LabelledValue("Server", settings.webDavUrl.orEmpty())
                LabelledValue("Username", settings.webDavUser.orEmpty())
                SyncStatus(settings, syncing, onSyncNow, onTurnOff, onBackgroundChange)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { editingWebDav = true }) { Text("Change the details") }
            }
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
    SyncToggleRow(
        label = "Sync in the background",
        checked = settings.syncInBackground,
        onCheckedChange = onBackgroundChange,
    )
    Text(
        // Deliberately not a promise about when. Android decides, and saying "every hour" when
        // the phone might sleep through four of them would be a lie somebody could catch.
        text = "About once an hour when the phone is charging or idle. There is always the button.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSyncNow, enabled = !syncing) {
            Text(if (syncing) "Syncing" else "Sync now")
        }
        OutlinedButton(onClick = onTurnOff) { Text("Turn off") }
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
        title = { Text("WebDAV") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Address") },
                    placeholder = { Text("https://cloud.example.com/remote.php/dav/files/me/WeightTrack") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Username") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Password") },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // Worth saying plainly. An app password can be revoked on its own, and this
                    // one is stored on the phone in the app's own data.
                    text = "On Nextcloud, make an app password rather than using your account one. It is kept on this phone and sent to that server and nowhere else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url, user, password) },
                enabled = url.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
            ) { Text("Use this") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SyncToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
