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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.model.Fast
import com.weighttrack.core.model.FastingPreset
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.ui.theme.HeroNumberStyle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Hours and minutes. Seconds would make the number jitter without telling anyone anything. */
internal fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return String.format(Locale.getDefault(), "%d:%02d", hours, minutes)
}

/**
 * Which way a target should be described, and the numbers to describe it with.
 *
 * Kept apart from the wording so the decision can be tested without a screen. Whether seventeen
 * and a half hours reads as "17h 30m" rather than "1050 minutes" is a judgement about the app;
 * how those words are spelled is a matter for whoever translates it.
 */
internal sealed interface FastTarget {
    data object None : FastTarget

    data object OneHour : FastTarget

    data class Hours(val hours: Int) : FastTarget

    data class Minutes(val minutes: Int) : FastTarget

    data class HoursAndMinutes(val hours: Int, val minutes: Int) : FastTarget
}

/**
 * The target a running fast was started with.
 *
 * Read from the fast itself rather than the tapped preset, so a custom length still describes
 * the ring that is being drawn instead of whichever chip happens to be selected.
 */
internal fun fastTarget(minutes: Int): FastTarget {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        minutes <= 0 -> FastTarget.None
        rest == 0 && hours == 1 -> FastTarget.OneHour
        rest == 0 -> FastTarget.Hours(hours)
        hours == 0 -> FastTarget.Minutes(rest)
        else -> FastTarget.HoursAndMinutes(hours, rest)
    }
}

/** That target in words. */
@Composable
internal fun formatTarget(minutes: Int): String = when (val target = fastTarget(minutes)) {
    FastTarget.None -> stringResource(R.string.fasting_no_target)
    FastTarget.OneHour -> stringResource(R.string.fasting_hour)
    is FastTarget.Hours -> stringResource(R.string.fasting_hours, target.hours)
    is FastTarget.Minutes -> stringResource(R.string.fasting_minutes, target.minutes)
    is FastTarget.HoursAndMinutes ->
        stringResource(R.string.fasting_h_m, target.hours, target.minutes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastingScreen(
    state: FastingUiState,
    /**
     * The ticking clock, deferred.
     *
     * Taken as a [State] rather than an [Instant] so this function does not read it. Reading it
     * here would recompose the whole list every second, which is what splitting the clock out
     * of the screen state was for; the read happens inside the timer card alone.
     */
    now: State<Instant>,
    onSelectPreset: (FastingPreset) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (Fast) -> Unit,
    editing: Fast?,
    onStartEditing: (Fast) -> Unit,
    onCancelEditing: () -> Unit,
    onSaveEdit: (Instant, Instant?) -> Unit,
    message: String?,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onDismissMessage()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fasting_fasting)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                    // The one place the clock is read. Everything below draws from fixed values.
                    val instant = now.value
                    Box(
                        Modifier.fillMaxWidth().height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        FastingRing(progress = active?.progress(instant) ?: 0f)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formatDuration(active?.elapsed(instant) ?: Duration.ZERO),
                                style = HeroNumberStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (active == null) {
                                    "Not fasting"
                                } else {
                                    "of ${formatTarget(active.targetMinutes)}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (active != null) {
                        Spacer(Modifier.height(4.dp))
                        val remaining = active.remaining(instant)
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
                        // Correcting a start you tapped late is the whole reason the edit dialog
                        // exists, so it has to be reachable while the fast is still running.
                        TextButton(onClick = { onStartEditing(active) }) {
                            Text(stringResource(R.string.fasting_correct_the_start_time))
                        }
                    }
                }
            }

            if (!state.isRunning) {
                item {
                    SectionCard {
                        SectionHeading(stringResource(R.string.fasting_window))
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
                    ) { Text(stringResource(R.string.fasting_end_fast)) }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.fasting_discard_this_fast), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.fasting_start_fasting)) }
                }
            }

            if (!state.history.isEmpty) {
                item {
                    SectionCard {
                        SectionHeading(stringResource(R.string.fasting_recent_fasts))
                        Spacer(Modifier.height(4.dp))
                        LabelledValue("Recorded", state.history.recorded.toString())
                        LabelledValue("Hit the target", state.history.reached.toString())
                        state.history.longest?.let {
                            LabelledValue("Longest", formatDuration(it))
                        }
                    }
                }
                items(state.history.fasts, key = { it.id }) { row ->
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = formatDuration(row.length),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.fasting_at, DateFormatters.relativeDay(row.fast.start.atZone(ZoneId.systemDefault()).toLocalDate(), today), DateFormatters.time(row.fast.start)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (row.reachedTarget) {
                                Text(
                                    text = stringResource(R.string.fasting_target),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(10.dp))
                            }
                            TextButton(onClick = { onStartEditing(row.fast) }) { Text(stringResource(R.string.common_edit)) }
                            OutlinedButton(
                                onClick = { onDelete(row.fast) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text(stringResource(R.string.common_delete)) }
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
 * Corrects a recorded fast, running or finished.
 *
 * Forgetting to press stop is the most common complaint about fasting apps, and one that
 * refuses to let you fix the time turns an honest record into a wrong one. The date is editable
 * as well as the clock, because a fast left running overnight is off by a day, not by hours.
 */
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
        title = { Text(if (fast.isRunning) "Edit running fast" else "Edit fast") },
        text = {
            Column {
                LabelledValue(
                    label = stringResource(R.string.fasting_started),
                    value = DateFormatters.shortDate(start.toLocalDate()) + " at " + formatClock(start),
                )
                TextButton(onClick = { editingStart = true }) { Text(stringResource(R.string.fasting_change_start)) }
                Spacer(Modifier.height(4.dp))
                LabelledValue(
                    label = stringResource(R.string.fasting_ended),
                    value = finish
                        ?.let { DateFormatters.shortDate(it.toLocalDate()) + " at " + formatClock(it) }
                        ?: "Still running",
                )
                if (finish != null) {
                    TextButton(onClick = { editingEnd = true }) { Text(stringResource(R.string.fasting_change_end)) }
                }
                if (invalid) {
                    Text(
                        text = stringResource(R.string.fasting_the_end_has_to_come_after),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (finish != null) {
                    Text(
                        text = stringResource(
                            R.string.fasting_that_is_how_long,
                            formatDuration(Duration.between(start, finish)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.fasting_still_running_targeting_what,
                            formatTarget(fast.targetMinutes),
                        ),
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
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )

    if (editingStart) {
        DateTimeDialog(
            initial = start,
            onCancel = { editingStart = false },
            onPick = { picked ->
                start = picked
                editingStart = false
            },
        )
    }
    if (editingEnd && finish != null) {
        DateTimeDialog(
            initial = finish,
            onCancel = { editingEnd = false },
            onPick = { picked ->
                end = picked
                editingEnd = false
            },
        )
    }
}

/** Date first, then the clock, so a fast that ran past midnight can be put on the right day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeDialog(
    initial: LocalDateTime,
    onCancel: () -> Unit,
    onPick: (LocalDateTime) -> Unit,
) {
    var date by remember { mutableStateOf(initial.toLocalDate()) }
    var pickingTime by remember { mutableStateOf(false) }

    if (!pickingTime) {
        // The picker works in UTC milliseconds regardless of where the user is, so the local
        // date is converted through UTC midnight rather than the device zone.
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = onCancel,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingTime = true
                }) { Text(stringResource(R.string.common_next)) }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
        ) {
            DatePicker(state = dateState, showModeToggle = false)
        }
    } else {
        val timeState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
        )
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.fasting_pick_a_time)) },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { TimePicker(state = timeState) }
            },
            confirmButton = {
                TextButton(onClick = {
                    onPick(LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute)))
                }) { Text(stringResource(R.string.common_set)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.common_back)) } },
        )
    }
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
