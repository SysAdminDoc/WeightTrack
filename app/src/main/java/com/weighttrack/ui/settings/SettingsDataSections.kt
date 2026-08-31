package com.weighttrack.ui.settings

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weighttrack.BuildConfig
import com.weighttrack.R
import com.weighttrack.data.io.ArchiveCodec
import com.weighttrack.data.io.BackupCodec
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.security.AppLockAvailability
import com.weighttrack.security.AppLockSupport
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.DateFormatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything that moves records off the phone or brings them back. */
internal fun LazyListScope.yourDataSection(
    entryCount: Int,
    viewModel: SettingsViewModel,
    pickers: SettingsPickers,
) = item {
    val autoBackup by viewModel.autoBackup.collectAsStateWithLifecycle()
    SettingsSection {
        SectionHeading(stringResource(R.string.settings_your_data))
        Spacer(Modifier.height(4.dp))
        LabelledValue(stringResource(R.string.settingsscreen_readings_stored), entryCount.toString())
        Text(
            text = stringResource(R.string.settings_weighttrack_has_no_account_and_no),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { pickers.exportJson(BackupCodec.suggestedFileName("json")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_back_up_everything)) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { pickers.restore(arrayOf("application/json", "*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_restore_from_a_backup)) }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_archive_explained),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                pickers.exportArchive(BackupCodec.suggestedFileName(ArchiveCodec.EXTENSION))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_archive_with_photos)) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { pickers.importArchive(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_restore_from_an_archive)) }

        Spacer(Modifier.height(14.dp))
        AutoBackupControls(
            state = autoBackup,
            onPickFolder = { pickers.autoBackupFolder() },
            onTurnOff = viewModel::turnOffAutoBackup,
        )

        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.settings_spreadsheets_and_other_apps), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { pickers.exportCsv(BackupCodec.suggestedFileName("csv")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_export_readings_as_csv)) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                pickers.exportMeasurements("weighttrack-measurements-" + LocalDate.now() + ".csv")
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_export_measurements_as_csv)) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                pickers.importCsv(
                    arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_import_a_csv_from_another_app)) }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_exports_from_libra_happy_scale_openscale),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AutoBackupControls(
    state: AutoBackupState,
    onPickFolder: () -> Unit,
    onTurnOff: () -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_automatic_backup),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.settings_automatic_backup_explained),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.problem) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_backup_folder_gone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    state.lastAt?.let { at ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.settings_last_backup,
                DateFormatters.fullDate(
                    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate(),
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPickFolder) {
            Text(
                stringResource(
                    if (state.folder == null) {
                        R.string.settings_choose_backup_folder
                    } else {
                        R.string.settings_change_backup_folder
                    },
                ),
            )
        }
        if (state.folder != null) {
            TextButton(onClick = onTurnOff) {
                Text(stringResource(R.string.settings_turn_off_automatic_backup))
            }
        }
    }
}

/**
 * The screen lock.
 *
 * [resumeCount] changes when somebody comes back from Android settings, which is what forces the
 * availability to be read again: a StateFlow refreshed with an equal value changes nothing.
 */
internal fun LazyListScope.privacySection(
    settings: AppSettings,
    resumeCount: Int,
    viewModel: SettingsViewModel,
) = item {
    val context = LocalContext.current
    val lockAvailability = remember(resumeCount) {
        AppLockSupport.availability(BiometricManager.from(context))
    }
    SettingsSection {
        SectionHeading(stringResource(R.string.settings_privacy))
        Spacer(Modifier.height(4.dp))
        // The switch is shown whenever the lock is already on, whatever the sensor
        // says today. A lock you cannot turn off is worse than no lock, and hiding the
        // switch under the working case is how someone ends up stuck with one.
        if (lockAvailability == AppLockAvailability.AVAILABLE || settings.appLockEnabled) {
            ToggleRow(
                label = stringResource(R.string.settings_lock_the_app),
                checked = settings.appLockEnabled,
                onCheckedChange = viewModel::setAppLockEnabled,
            )
        }
        Text(
            text = when (lockAvailability) {
                AppLockAvailability.AVAILABLE ->
                    stringResource(R.string.settings_asks_for_your_fingerprint_face_or)
                AppLockAvailability.NO_SCREEN_LOCK ->
                    stringResource(R.string.settings_set_a_screen_lock_in_android)
                AppLockAvailability.UNAVAILABLE ->
                    stringResource(R.string.settings_this_device_has_no_screen_lock)
                AppLockAvailability.NEEDS_SECURITY_UPDATE ->
                    stringResource(R.string.settings_android_has_switched_this_phone_s)
                AppLockAvailability.TEMPORARILY_UNAVAILABLE ->
                    stringResource(R.string.settings_android_cannot_check_your_fingerprint_or)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The way in to the crash reports. */
internal fun LazyListScope.diagnosticsSection(
    viewModel: SettingsViewModel,
    onOpenCrashLogs: () -> Unit,
) = item {
    val crashReportCount by viewModel.crashReportCount.collectAsStateWithLifecycle()
    SettingsSection {
        SectionHeading(stringResource(R.string.settings_diagnostics))
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_if_the_app_ever_closes_unexpectedly),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenCrashLogs, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (crashReportCount) {
                    0 -> stringResource(R.string.settings_crash_reports_none)
                    1 -> stringResource(R.string.settings_crash_reports)
                    else -> stringResource(R.string.settings_crash_reports_2, crashReportCount)
                },
            )
        }
    }
}

/** Version, flavour, licence. */
internal fun LazyListScope.aboutSection() = item {
    SettingsSection {
        SectionHeading(stringResource(R.string.settings_about))
        Spacer(Modifier.height(6.dp))
        LabelledValue(stringResource(R.string.settingsscreen_version), BuildConfig.VERSION_NAME)
        LabelledValue(stringResource(R.string.settingsscreen_build), if (BuildConfig.FOSS_ONLY) "F-Droid" else "Play")
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_free_and_open_source_under_the),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
