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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.LedgerSection
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.SegmentButton
import com.weighttrack.ui.components.WeightKeypad
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.core.format.WeightFormatter
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
                title = { Text(if (state.isEditing) stringResource(R.string.logweightscreen_edit_reading) else stringResource(R.string.logweightscreen_log_weight)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
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
                Text(if (state.isEditing) stringResource(R.string.logweightscreen_save_changes) else stringResource(R.string.logweightscreen_save_weight))
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            )
            LedgerSection(showDivider = false) {
                SectionHeading(stringResource(R.string.log_context))
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
                    Text(if (showAllTags) stringResource(R.string.logweightscreen_fewer_tags) else stringResource(R.string.logweightscreen_more_tags))
                }
                if (!showDetails) {
                    OutlinedButton(
                        onClick = { showDetails = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.log_add_note_or_body_fat))
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.note,
                        onValueChange = onNoteChange,
                        label = { Text(stringResource(R.string.log_note)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.bodyFatText,
                        onValueChange = onBodyFatChange,
                        label = { Text(stringResource(R.string.log_body_fat)) },
                        supportingText = { Text(stringResource(R.string.log_optional_from_a_smart_scale_or)) },
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
                }) { Text(stringResource(R.string.common_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_cancel)) }
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
                }) { Text(stringResource(R.string.common_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.common_cancel)) }
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

@Composable
fun tagLabel(tag: EntryTag): String = when (tag) {
    EntryTag.POST_WORKOUT -> stringResource(R.string.log_post_workout)
    EntryTag.FASTED -> stringResource(R.string.log_fasted)
    EntryTag.WELL_HYDRATED -> stringResource(R.string.log_well_hydrated)
    EntryTag.TRAVEL -> stringResource(R.string.log_travel)
    EntryTag.ALCOHOL -> stringResource(R.string.log_alcohol)
    EntryTag.HIGH_SALT -> stringResource(R.string.log_high_salt)
    EntryTag.PERIOD -> stringResource(R.string.log_period)
    EntryTag.ILL -> stringResource(R.string.log_ill)
    EntryTag.POOR_SLEEP -> stringResource(R.string.log_poor_sleep)
    EntryTag.STRESSED -> stringResource(R.string.log_stressed)
}
