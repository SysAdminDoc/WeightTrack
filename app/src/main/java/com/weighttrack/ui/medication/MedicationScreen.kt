package com.weighttrack.ui.medication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.weighttrack.core.medication.GlpDrug
import com.weighttrack.core.medication.InjectionSite
import com.weighttrack.core.medication.MedicationLevel
import com.weighttrack.core.medication.SideEffectKind
import com.weighttrack.core.medication.SideEffectSeverity
import com.weighttrack.data.repo.MedicationDose
import com.weighttrack.data.repo.SideEffect
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.DateFormatters

/**
 * The injection log.
 *
 * Three things on one screen: where the next one goes, what has gone in already, and how it has
 * been. Everything a person actually loses track of between appointments, and nothing that reads
 * like advice: the app records what happened and adds up what it can, and every judgement is left
 * to the person and whoever prescribed it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MedicationScreen(
    state: com.weighttrack.ui.medication.MedicationUiState,
    onAddDose: (GlpDrug, Double, InjectionSite, String?) -> Unit,
    onDeleteDose: (Long) -> Unit,
    onAddSideEffect: (SideEffectKind, SideEffectSeverity) -> Unit,
    onDeleteSideEffect: (Long) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loggingDose by remember { mutableStateOf(false) }
    var loggingEffect by remember { mutableStateOf(false) }

    androidx.compose.material3.Scaffold(
        modifier = modifier,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.medication_title)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.medication_next_site,
                            stringResource(siteLabel(state.suggestedSite)),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { loggingDose = true }) {
                            Text(stringResource(R.string.medication_log_a_dose))
                        }
                        OutlinedButton(onClick = { loggingEffect = true }) {
                            Text(stringResource(R.string.medication_log_how_you_felt))
                        }
                    }
                }
            }
        }

        state.proteinGrams?.let { grams ->
            item {
                Column {
                    SectionHeading(stringResource(R.string.medication_protein_heading))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.medication_protein_target,
                            grams.first,
                            grams.last,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.medication_protein_explained),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Column {
                SectionHeading(stringResource(R.string.medication_level_heading))
                Spacer(Modifier.height(6.dp))
                if (state.level.isEmpty()) {
                    Text(
                        text = stringResource(R.string.medication_level_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MedicationLevelChart(
                        points = state.level,
                        doses = state.doses,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.medication_level_explained),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { SectionHeading(stringResource(R.string.medication_doses_heading)) }
        if (state.doses.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.medication_no_doses),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.doses) { dose -> DoseRow(dose, onDeleteDose) }
        }

        item { SectionHeading(stringResource(R.string.medication_effects_heading)) }
        if (state.sideEffects.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.medication_no_effects),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.sideEffects) { effect -> EffectRow(effect, onDeleteSideEffect) }
        }

        item {
            Column {
                SectionHeading(stringResource(R.string.medication_export))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.medication_export_explained,
                        MedicationViewModel.REPORT_DAYS.toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onExport) {
                    Text(stringResource(R.string.medication_export))
                }
            }
        }
    }
    }

    if (loggingDose) {
        DoseDialog(
            drug = state.drug,
            milligrams = state.lastMilligrams,
            site = state.suggestedSite,
            onDismiss = { loggingDose = false },
            onConfirm = { drug, mg, site, note ->
                loggingDose = false
                onAddDose(drug, mg, site, note)
            },
        )
    }

    if (loggingEffect) {
        SideEffectDialog(
            onDismiss = { loggingEffect = false },
            onConfirm = { kind, severity ->
                loggingEffect = false
                onAddSideEffect(kind, severity)
            },
        )
    }
}

@Composable
private fun DoseRow(dose: MedicationDose, onDelete: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.medication_dose_row,
                    stringResource(drugLabel(dose.drug)),
                    milligrams(dose.milligrams),
                    stringResource(siteLabel(dose.site)),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = DateFormatters.fullDate(dose.localDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onDelete(dose.id) }) {
            Text(stringResource(R.string.common_delete))
        }
    }
}

@Composable
private fun EffectRow(effect: SideEffect, onDelete: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.medication_effect_row,
                    stringResource(effectLabel(effect.kind)),
                    stringResource(severityLabel(effect.severity)),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = DateFormatters.fullDate(effect.localDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onDelete(effect.id) }) {
            Text(stringResource(R.string.common_delete))
        }
    }
}

/** Milligrams without a trailing zero, because 0.5 and 2 are both things a pen says. */
internal fun milligrams(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun DoseDialog(
    drug: GlpDrug,
    milligrams: Double,
    site: InjectionSite,
    onDismiss: () -> Unit,
    onConfirm: (GlpDrug, Double, InjectionSite, String?) -> Unit,
) {
    var chosenDrug by remember { mutableStateOf(drug) }
    var chosenSite by remember { mutableStateOf(site) }
    var amount by remember { mutableStateOf(milligrams(milligrams)) }
    var note by remember { mutableStateOf("") }
    val parsed = amount.replace(',', '.').toDoubleOrNull()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.medication_log_a_dose)) },
        text = {
            Column {
                ChoiceRow(
                    options = GlpDrug.entries.map { it to stringResource(drugLabel(it)) },
                    selected = chosenDrug,
                    onSelect = { chosenDrug = it },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    // Filtered rather than only validated. A keyboard on a real phone can put a
                    // character of its own into a decimal field, and a save button that greys
                    // out with no explanation is the result.
                    onValueChange = { typed ->
                        amount = typed.filter { it.isDigit() || it == '.' || it == ',' }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    label = { Text(stringResource(R.string.medication_dose_amount)) },
                )
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    options = InjectionSite.entries.map { it to stringResource(siteLabel(it)) },
                    selected = chosenSite,
                    onSelect = { chosenSite = it },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.medication_dose_note)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirm(chosenDrug, it, chosenSite, note) } },
                enabled = parsed != null && parsed > 0,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun SideEffectDialog(
    onDismiss: () -> Unit,
    onConfirm: (SideEffectKind, SideEffectSeverity) -> Unit,
) {
    var kind by remember { mutableStateOf(SideEffectKind.NAUSEA) }
    var severity by remember { mutableStateOf(SideEffectSeverity.MILD) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.medication_log_how_you_felt)) },
        text = {
            Column {
                ChoiceRow(
                    options = SideEffectKind.entries.map { it to stringResource(effectLabel(it)) },
                    selected = kind,
                    onSelect = { kind = it },
                )
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    options = SideEffectSeverity.entries.map {
                        it to stringResource(severityLabel(it))
                    },
                    selected = severity,
                    onSelect = { severity = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(kind, severity) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            androidx.compose.material3.FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}
