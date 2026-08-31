package com.weighttrack.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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

/** How short a password may be before this refuses it. */
const val ARCHIVE_MINIMUM_PASSWORD = 8

/**
 * Asks for the password that opens or seals an archive.
 *
 * Asked twice when sealing, once when opening. There is no recovery: the key is derived from
 * what is typed and nothing else knows it, so a typo in a password used once would produce a
 * file that can never be read, and the person would find that out on the day they needed it.
 */
@Composable
fun ArchivePasswordDialog(
    confirming: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }

    val tooShort = password.length < ARCHIVE_MINIMUM_PASSWORD
    val mismatched = confirming && again != password
    val ready = !tooShort && !mismatched

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (confirming) {
                        R.string.settings_archive_with_photos
                    } else {
                        R.string.settings_restore_from_an_archive
                    },
                ),
            )
        },
        text = {
            Column {
                if (confirming) {
                    Text(stringResource(R.string.archive_password_needed))
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.archive_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirming) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = again,
                        onValueChange = { again = it },
                        label = { Text(stringResource(R.string.archive_password_again)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = again.isNotEmpty() && mismatched,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Said before the button is pressed, not after. A disabled button with no reason
                // beside it reads as the app being broken.
                if (password.isNotEmpty() && tooShort) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.archive_password_too_short,
                            ARCHIVE_MINIMUM_PASSWORD,
                        ),
                    )
                } else if (again.isNotEmpty() && mismatched) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.archive_passwords_differ))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = { onConfirm(password.toCharArray()) },
            ) { Text(stringResource(R.string.common_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
