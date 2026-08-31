package com.weighttrack.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Every file picker the settings screen opens, behind one call each.
 *
 * The archive pair is the reason this is a holder rather than a handful of launchers: the password
 * is asked for after the destination is chosen, so cancelling the picker costs nobody a password
 * they typed for nothing, which means the chosen file has to be held somewhere in between.
 */
internal class SettingsPickers(
    val exportCsv: (String) -> Unit,
    val exportJson: (String) -> Unit,
    val exportMeasurements: (String) -> Unit,
    val importCsv: (Array<String>) -> Unit,
    val restore: (Array<String>) -> Unit,
    val exportArchive: (String) -> Unit,
    val importArchive: (Array<String>) -> Unit,
    val autoBackupFolder: () -> Unit,
    val syncFolder: () -> Unit,
    val syncCertificate: () -> Unit,
    /** Set once a destination is chosen, cleared when the password dialog closes. */
    val archiveDestination: MutableState<Uri?>,
    /** Set once a source is chosen, cleared when the password dialog closes. */
    val archiveSource: MutableState<Uri?>,
)

@Composable
internal fun rememberSettingsPickers(viewModel: SettingsViewModel): SettingsPickers {
    val context = LocalContext.current

    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportCsv) }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportJson) }

    val exportMeasurementsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportMeasurements) }

    // Some file pickers hand back CSV under a generic MIME type, so the filter stays wide and
    // the parser decides whether the file makes sense.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importCsv) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::previewRestore) }

    val archiveDestination = remember { mutableStateOf<Uri?>(null) }
    val archiveSource = remember { mutableStateOf<Uri?>(null) }

    val exportArchiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> archiveDestination.value = uri }

    val importArchiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> archiveSource.value = uri }

    val autoBackupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            viewModel.useAutoBackupFolder(uri) { picked -> context.holdOnTo(picked) }
        }
    }

    val syncFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Held on to, or the address stops working after a restart and background syncing
            // fails forever with nothing on screen to say why.
            viewModel.useSyncFolder(uri) { picked -> context.holdOnTo(picked) }
        }
    }

    // Any file: a certificate arrives as .crt, .cer, .pem or .der depending on where it came
    // from, and a picker filtered to one of those hides the others.
    val syncCertificateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::useSyncCertificate) }

    return remember(viewModel) {
        SettingsPickers(
            exportCsv = exportCsvLauncher::launch,
            exportJson = exportJsonLauncher::launch,
            exportMeasurements = exportMeasurementsLauncher::launch,
            importCsv = importLauncher::launch,
            restore = restoreLauncher::launch,
            exportArchive = exportArchiveLauncher::launch,
            importArchive = importArchiveLauncher::launch,
            autoBackupFolder = { autoBackupFolderLauncher.launch(null) },
            syncFolder = { syncFolderLauncher.launch(null) },
            syncCertificate = { syncCertificateLauncher.launch(arrayOf("*/*")) },
            archiveDestination = archiveDestination,
            archiveSource = archiveSource,
        )
    }
}

/** Keeps the grant across a restart, which is the whole point of picking a tree. */
private fun android.content.Context.holdOnTo(uri: Uri) {
    contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}
