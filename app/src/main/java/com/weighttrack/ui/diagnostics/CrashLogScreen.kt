package com.weighttrack.ui.diagnostics

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.diagnostics.CrashReport
import com.weighttrack.ui.components.EmptyState
import com.weighttrack.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(
    state: CrashLogUiState,
    onOpen: (CrashReport) -> Unit,
    onClose: () -> Unit,
    onDelete: (CrashReport) -> Unit,
    onDeleteAll: () -> Unit,
    onShareActivityLog: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_crash_reports)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // Offered even with no crashes to show: the things this log is for, a sync
                    // that did nothing and a scale that went quiet, are not crashes.
                    if (state.activityLogAvailable) {
                        TextButton(onClick = onShareActivityLog) {
                            Text(stringResource(R.string.diagnostics_share_activity_log))
                        }
                    }
                    if (state.reports.isNotEmpty()) {
                        TextButton(onClick = onDeleteAll) { Text(stringResource(R.string.diagnostics_clear_all)) }
                    }
                },
            )
        },
    ) { padding ->
        if (state.reports.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.BugReport,
                title = if (state.loaded) "No crashes recorded" else "Loading",
                message = stringResource(R.string.diagnostics_if_weighttrack_ever_closes_unexpectedly_the),
                modifier = Modifier.fillMaxSize().padding(padding).padding(top = 48.dp),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.diagnostics_these_stay_on_your_phone_tap),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.reports, key = { it.id }) { report ->
                SectionCard(modifier = Modifier.clickable { onOpen(report) }) {
                    Text(
                        text = report.formattedTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = report.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                    )
                }
            }
        }
    }

    val openBody = state.openReportBody
    val openReport = state.reports.firstOrNull { it.id == state.openReportId }
    if (openBody != null && openReport != null) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(openReport.formattedTime()) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    // A stack trace is unreadable once it wraps, so it scrolls sideways instead.
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            text = openBody,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shareReport(context, openReport, openBody) }) {
                    Text(stringResource(R.string.diagnostics_share))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onDelete(openReport) }) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
                }
            },
        )
    }
}

/**
 * Shares the report as plain text.
 *
 * Text rather than a file attachment on purpose: it needs no FileProvider, works in every mail
 * and chat app, and keeps the report visible to the person before they send it.
 */
/**
 * Shares the activity log as plain text, the same way a crash report goes.
 *
 * Text rather than an attachment: it needs no FileProvider, works in every mail and chat app,
 * and the person sees exactly what they are sending before they send it.
 */
fun shareActivityLogText(context: Context, body: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_activity_log_subject))
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.diagnostics_share_activity_log))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun shareReport(context: Context, report: CrashReport, body: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "WeightTrack crash ${report.formattedTime()}")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "Share crash report")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
