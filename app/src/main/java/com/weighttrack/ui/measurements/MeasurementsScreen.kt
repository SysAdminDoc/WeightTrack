package com.weighttrack.ui.measurements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.ui.components.LedgerSection
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.core.format.LengthFormatter
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementsScreen(
    state: MeasurementsUiState,
    editor: MeasurementEditor?,
    onStartEditing: (MeasurementType) -> Unit,
    onEditorTextChange: (String) -> Unit,
    onCancelEditing: () -> Unit,
    onSaveEditor: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_measurements)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                LedgerSection(contentPadding = PaddingValues(vertical = 18.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                        Column {
                            Text(stringResource(R.string.measurements_track_more_than_the_scale), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.measurements_waist_neck_and_hips_improve_your),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                MeasurementSectionHeading(
                    text = stringResource(R.string.measurements_body_fat_estimate),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                MeasurementGroup(
                    types = MeasurementType.entries.filter { it.usedForBodyFat },
                    state = state,
                    today = today,
                    onStartEditing = onStartEditing,
                )
            }

            item {
                MeasurementSectionHeading(
                    text = stringResource(R.string.measurements_other_measurements),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            item {
                MeasurementGroup(
                    types = MeasurementType.entries.filter { !it.usedForBodyFat },
                    state = state,
                    today = today,
                    onStartEditing = onStartEditing,
                )
            }
        }
    }

    if (editor != null) {
        AlertDialog(
            onDismissRequest = onCancelEditing,
            title = { Text(measurementLabel(editor.type)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editor.text,
                        onValueChange = onEditorTextChange,
                        label = { Text(LengthFormatter.unitLabel(state.lengthUnit)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = measurementHint(editor.type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onSaveEditor) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = onCancelEditing) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun MeasurementGroup(
    types: List<MeasurementType>,
    state: MeasurementsUiState,
    today: LocalDate,
    onStartEditing: (MeasurementType) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
    ) {
        types.forEachIndexed { index, type ->
            MeasurementRow(type, state, today, onStartEditing)
            if (index < types.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun MeasurementRow(
    type: MeasurementType,
    state: MeasurementsUiState,
    today: LocalDate,
    onStartEditing: (MeasurementType) -> Unit,
) {
    val measurement = state.latest[type]
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onStartEditing(type) }
            .padding(horizontal = 4.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(measurementLabel(type), style = MaterialTheme.typography.titleMedium)
            Text(
                text = measurement
                    ?.let { "Updated ${DateFormatters.sinceDay(it.localDate, today)}" }
                    ?: stringResource(R.string.measurementsscreen_not_measured_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = measurement?.let { LengthFormatter.full(it.valueMm, state.lengthUnit) } ?: "Add",
            style = MaterialTheme.typography.titleMedium,
            color = if (measurement == null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MeasurementSectionHeading(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(18.dp).background(color))
        Spacer(Modifier.width(10.dp))
        SectionHeading(text)
    }
}

@Composable
fun measurementLabel(type: MeasurementType): String = when (type) {
    MeasurementType.NECK -> stringResource(R.string.measurements_neck)
    MeasurementType.SHOULDERS -> stringResource(R.string.measurements_shoulders)
    MeasurementType.CHEST -> stringResource(R.string.measurements_chest)
    MeasurementType.WAIST -> stringResource(R.string.measurements_waist)
    MeasurementType.HIPS -> stringResource(R.string.measurements_hips)
    MeasurementType.LEFT_ARM -> stringResource(R.string.measurements_left_arm)
    MeasurementType.RIGHT_ARM -> stringResource(R.string.measurements_right_arm)
    MeasurementType.LEFT_FOREARM -> stringResource(R.string.measurements_left_forearm)
    MeasurementType.RIGHT_FOREARM -> stringResource(R.string.measurements_right_forearm)
    MeasurementType.LEFT_THIGH -> stringResource(R.string.measurements_left_thigh)
    MeasurementType.RIGHT_THIGH -> stringResource(R.string.measurements_right_thigh)
    MeasurementType.LEFT_CALF -> stringResource(R.string.measurements_left_calf)
    MeasurementType.RIGHT_CALF -> stringResource(R.string.measurements_right_calf)
}

@Composable
private fun measurementHint(type: MeasurementType): String = when (type) {
    MeasurementType.NECK -> stringResource(R.string.measurements_just_below_the_larynx_tape_sloping)
    MeasurementType.WAIST -> stringResource(R.string.measurements_at_the_navel_for_men_at)
    MeasurementType.HIPS -> stringResource(R.string.measurements_around_the_widest_part_of_the)
    MeasurementType.CHEST -> stringResource(R.string.measurements_around_the_fullest_part_arms_relaxed)
    MeasurementType.SHOULDERS -> stringResource(R.string.measurements_around_the_widest_point_tape_level)
    else -> stringResource(R.string.measurements_same_spot_each_time_tape_snug)
}
