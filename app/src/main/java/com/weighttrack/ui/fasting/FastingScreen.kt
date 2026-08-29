package com.weighttrack.ui.fasting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import java.time.LocalDateTime
import java.time.Instant
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TimePicker
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.weighttrack.core.model.Fast
import com.weighttrack.core.model.FastingPreset
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.ui.theme.HeroNumberStyle
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Hours and minutes. Seconds would make the number jitter without telling anyone anything. */
internal fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return String.format(Locale.getDefault(), "%d:%02d", hours, minutes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastingScreen(
    state: FastingUiState,
    onSelectPreset: (FastingPreset) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (Fast) -> Unit,
    editing: Fast?,
    onStartEditing: (Fast) -> Unit,
    onCancelEditing: () -> Unit,
    onSaveEdit: (Instant, Instant?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Fasting") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    val active = state.active
                    Box(
                        Modifier.fillMaxWidth().height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        FastingRing(progress = active?.progress(state.now) ?: 0f)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formatDuration(active?.elapsed(state.now) ?: Duration.ZERO),
                                style = HeroNumberStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (active == null) {
                                    "Not fasting"
                                } else {
                                    "of ${state.selectedPreset.targetMinutes / 60} hours"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (active != null) {
                        Spacer(Modifier.height(4.dp))
                        val remaining = active.remaining(state.now)
                        Text(
                            text = if (remaining == null) {
                                "Target reached. Keep going or stop whenever you like."
                            } else {
                                "${formatDuration(remaining)} to go, started ${DateFormatters.time(active.start)}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (remaining == null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            if (!state.isRunning) {
                item {
                    SectionCard {
                        SectionHeading("Window")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FastingPreset.entries.take(3).forEach { preset ->
                                FilterChip(
                                    selected = state.selectedPreset == preset,
                                    onClick = { onSelectPreset(preset) },
                                    label = { Text(preset.label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FastingPreset.entries.drop(3).forEach { preset ->
                                FilterChip(
                                    selected = state.selectedPreset == preset,
                                    onClick = { onSelectPreset(preset) },
                                    label = { Text(preset.label) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                if (state.isRunning) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("End fast") }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Discard this fast", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Start fasting") }
                }
            }

            if (state.completed.isNotEmpty()) {
                item {
                    SectionCard {
                        SectionHeading("Recent fasts")
                        Spacer(Modifier.height(4.dp))
                        val reached = state.completed.count { it.reachedTarget(state.now) }
                        LabelledValue("Recorded", state.completed.size.toString())
                        LabelledValue("Hit the target", reached.toString())
                        val longest = state.completed.maxByOrNull { it.elapsed(state.now) }
                        longest?.let {
                            LabelledValue("Longest", formatDuration(it.elapsed(state.now)))
                        }
                    }
                }
                items(state.completed, key = { it.id }) { fast ->
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = formatDuration(fast.elapsed(state.now)),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "${DateFormatters.relativeDay(fast.start.atZone(ZoneId.systemDefault()).toLocalDate(), today)} at ${DateFormatters.time(fast.start)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (fast.reachedTarget(state.now)) {
                                Text(
                                    text = "target",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(10.dp))
                            }
                            TextButton(onClick = { onStartEditing(fast) }) { Text("Edit") }
                            OutlinedButton(
                                onClick = { onDelete(fast) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { fast ->
        EditFastDialog(fast = fast, onCancel = onCancelEditing, onSave = onSaveEdit)
    }
}


/**
 * Corrects a recorded fast.
 *
 * Forgetting to press stop is the most common complaint about fasting apps, and one that
 * refuses to let you fix the time turns an honest record into a wrong one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditFastDialog(
    fast: Fast,
    onCancel: () -> Unit,
    onSave: (Instant, Instant?) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var start by remember(fast.id) { mutableStateOf(fast.start.atZone(zone).toLocalDateTime()) }
    var end by remember(fast.id) { mutableStateOf(fast.end?.atZone(zone)?.toLocalDateTime()) }
    var editingStart by remember(fast.id) { mutableStateOf(false) }
    var editingEnd by remember(fast.id) { mutableStateOf(false) }

    val finish = end
    val invalid = finish != null && finish.isBefore(start)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Edit fast") },
        text = {
            Column {
                LabelledValue(
                    label = "Started",
                    value = DateFormatters.shortDate(start.toLocalDate()) + " at " + formatClock(start),
                )
                TextButton(onClick = { editingStart = true }) { Text("Change start time") }
                Spacer(Modifier.height(4.dp))
                LabelledValue(
                    label = "Ended",
                    value = finish
                        ?.let { DateFormatters.shortDate(it.toLocalDate()) + " at " + formatClock(it) }
                        ?: "Still running",
                )
                if (finish != null) {
                    TextButton(onClick = { editingEnd = true }) { Text("Change end time") }
                }
                if (invalid) {
                    Text(
                        text = "The end has to come after the start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = "That is " + formatDuration(Duration.between(start, finish ?: start)) + " long.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !invalid,
                onClick = {
                    onSave(start.atZone(zone).toInstant(), finish?.atZone(zone)?.toInstant())
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )

    if (editingStart) {
        ClockDialog(
            initial = start,
            onCancel = { editingStart = false },
            onPick = { picked ->
                start = picked
                editingStart = false
            },
        )
    }
    if (editingEnd && finish != null) {
        ClockDialog(
            initial = finish,
            onCancel = { editingEnd = false },
            onPick = { picked ->
                end = picked
                editingEnd = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(
    initial: LocalDateTime,
    onCancel: () -> Unit,
    onPick: (LocalDateTime) -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
    )
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pick a time") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { TimePicker(state = timeState) }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick(initial.withHour(timeState.hour).withMinute(timeState.minute))
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private fun formatClock(value: LocalDateTime): String =
    String.format(Locale.getDefault(), "%02d:%02d", value.hour, value.minute)

@Composable
private fun FastingRing(progress: Float) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(200.dp)) {
        val stroke = 14.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (progress > 0f) {
            drawArc(
                color = fill,
                // Starts at the top and runs clockwise, the way a clock face reads.
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
