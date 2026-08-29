package com.weighttrack.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.SegmentButton
import com.weighttrack.ui.components.WeightKeypad
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.ui.format.WeightFormatter
import com.weighttrack.ui.theme.HeroNumberStyle
import com.weighttrack.ui.theme.HeroUnitStyle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogWeightScreen(
    state: LogWeightUiState,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onNoteChange: (String) -> Unit,
    onBodyFatChange: (String) -> Unit,
    onToggleTag: (EntryTag) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showAllTags by remember { mutableStateOf(false) }
    var showDetails by remember(state.note, state.bodyFatText) {
        mutableStateOf(state.note.isNotBlank() || state.bodyFatText.isNotBlank())
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onClose()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit reading" else "Log weight") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = state.formattedValue,
                    style = HeroNumberStyle,
                    color = if (state.canSave) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = " ${if (state.unit == WeightUnit.ST_LB) "lb" else WeightFormatter.unitLabel(state.unit)}",
                    style = HeroUnitStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.CalendarToday, contentDescription = null)
                    Text("  ${DateFormatters.relativeDay(state.date)}")
                }
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Schedule, contentDescription = null)
                    Text("  ${state.time.hour.toString().padStart(2, '0')}:${state.time.minute.toString().padStart(2, '0')}")
                }
            }

            WeightKeypad(onDigit = onDigit, onBackspace = onBackspace, onClear = onClear)

            Button(
                onClick = onSave,
                enabled = state.canSave,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.isEditing) "Save changes" else "Save weight")
            }

            SectionCard {
                SectionHeading("Context")
                Spacer(Modifier.height(8.dp))
                val preferredTags = listOf(
                    EntryTag.FASTED,
                    EntryTag.POST_WORKOUT,
                    EntryTag.WELL_HYDRATED,
                )
                val orderedTags = preferredTags + EntryTag.entries.filterNot { it in preferredTags }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    orderedTags.take(if (showAllTags) orderedTags.size else 3).forEach { tag ->
                        SegmentButton(
                            label = tagLabel(tag),
                            selected = tag in state.tags,
                            onClick = { onToggleTag(tag) },
                        )
                    }
                }
                TextButton(onClick = { showAllTags = !showAllTags }) {
                    Text(if (showAllTags) "Fewer tags" else "More tags")
                }
                if (!showDetails) {
                    OutlinedButton(
                        onClick = { showDetails = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Add note or body fat")
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.note,
                        onValueChange = onNoteChange,
                        label = { Text("Note") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.bodyFatText,
                        onValueChange = onBodyFatChange,
                        label = { Text("Body fat %") },
                        supportingText = { Text("Optional, from a smart scale or a caliper reading") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onDateChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                    showDatePicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = state.time.hour,
            initialMinute = state.time.minute,
        )
        DatePickerDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(pickerState.hour, pickerState.minute))
                    showTimePicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = pickerState)
            }
        }
    }
}

fun tagLabel(tag: EntryTag): String = when (tag) {
    EntryTag.POST_WORKOUT -> "Post workout"
    EntryTag.FASTED -> "Fasted"
    EntryTag.WELL_HYDRATED -> "Well hydrated"
    EntryTag.TRAVEL -> "Travel"
    EntryTag.ALCOHOL -> "Alcohol"
    EntryTag.HIGH_SALT -> "High salt"
    EntryTag.PERIOD -> "Period"
    EntryTag.ILL -> "Ill"
    EntryTag.POOR_SLEEP -> "Poor sleep"
    EntryTag.STRESSED -> "Stressed"
}
