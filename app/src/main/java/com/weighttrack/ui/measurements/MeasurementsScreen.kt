package com.weighttrack.ui.measurements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.ui.components.SectionCard
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
                title = { Text("Measurements") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
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
                SectionCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
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
                            Text("Track more than the scale", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Waist, neck and hips improve your body-fat estimate.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { SectionHeading("Body fat estimate", Modifier.padding(top = 8.dp)) }
            item {
                MeasurementGroup(
                    types = MeasurementType.entries.filter { it.usedForBodyFat },
                    state = state,
                    today = today,
                    onStartEditing = onStartEditing,
                )
            }

            item { SectionHeading("Other measurements", Modifier.padding(top = 12.dp)) }
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
            confirmButton = { TextButton(onClick = onSaveEditor) { Text("Save") } },
            dismissButton = { TextButton(onClick = onCancelEditing) { Text("Cancel") } },
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
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        types.forEachIndexed { index, type ->
            MeasurementRow(type, state, today, onStartEditing)
            if (index < types.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(measurementLabel(type), style = MaterialTheme.typography.titleMedium)
            Text(
                text = measurement
                    ?.let { "Updated ${DateFormatters.sinceDay(it.localDate, today)}" }
                    ?: "Not measured yet",
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

fun measurementLabel(type: MeasurementType): String = when (type) {
    MeasurementType.NECK -> "Neck"
    MeasurementType.SHOULDERS -> "Shoulders"
    MeasurementType.CHEST -> "Chest"
    MeasurementType.WAIST -> "Waist"
    MeasurementType.HIPS -> "Hips"
    MeasurementType.LEFT_ARM -> "Left arm"
    MeasurementType.RIGHT_ARM -> "Right arm"
    MeasurementType.LEFT_FOREARM -> "Left forearm"
    MeasurementType.RIGHT_FOREARM -> "Right forearm"
    MeasurementType.LEFT_THIGH -> "Left thigh"
    MeasurementType.RIGHT_THIGH -> "Right thigh"
    MeasurementType.LEFT_CALF -> "Left calf"
    MeasurementType.RIGHT_CALF -> "Right calf"
}

private fun measurementHint(type: MeasurementType): String = when (type) {
    MeasurementType.NECK -> "Just below the larynx, tape sloping slightly down at the front."
    MeasurementType.WAIST -> "At the navel for men, at the narrowest point for women. Relaxed, not held in."
    MeasurementType.HIPS -> "Around the widest part of the buttocks."
    MeasurementType.CHEST -> "Around the fullest part, arms relaxed at your sides."
    MeasurementType.SHOULDERS -> "Around the widest point, tape level all the way round."
    else -> "Same spot each time, tape snug but not tight."
}
