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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Crash reports") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.reports.isNotEmpty()) {
                        TextButton(onClick = onDeleteAll) { Text("Clear all") }
                    }
                },
            )
        },
    ) { padding ->
        if (state.reports.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.BugReport,
                title = if (state.loaded) "No crashes recorded" else "Loading",
                message = "If WeightTrack ever closes unexpectedly, the details land here so you can send them on. Nothing is uploaded on its own.",
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
                    text = "These stay on your phone. Tap one to read it, then share it if you want it looked at.",
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
                    Text("Share")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onDelete(openReport) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onClose) { Text("Close") }
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
