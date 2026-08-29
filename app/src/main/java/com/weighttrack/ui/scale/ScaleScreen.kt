package com.weighttrack.ui.scale

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.weighttrack.ble.ScaleDevice
import com.weighttrack.ble.ScaleMatch
import com.weighttrack.ble.ScaleProblem
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.theme.HeroNumberStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleScreen(
    state: ScaleUiState,
    permissions: List<String>,
    onRetry: () -> Unit,
    onConnect: (ScaleDevice) -> Unit,
    onSave: () -> Unit,
    onSaveToSuggested: () -> Unit,
    onDiscard: () -> Unit,
    onForgetScale: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onRetry() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Weigh in") },
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
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val shown = state.reading?.grams ?: state.savedGrams ?: state.liveGrams
                        Text(
                            text = shown
                                ?.let { WeightFormatter.full(it, state.weightUnit) }
                                ?: "--",
                            style = HeroNumberStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = headline(state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (state.stage == ScaleStage.SEARCHING ||
                            state.stage == ScaleStage.WAITING_FOR_WEIGHT
                        ) {
                            Spacer(Modifier.height(12.dp))
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            state.reading?.let { reading ->
                item {
                    SectionCard {
                        SectionHeading("What the scale measured")
                        Spacer(Modifier.height(4.dp))
                        LabelledValue(
                            "Weight",
                            WeightFormatter.full(reading.grams, state.weightUnit),
                        )
                        reading.bodyFatPercent?.let {
                            LabelledValue("Body fat", String.format("%.1f%%", it))
                        }
                        reading.muscleMassGrams?.let {
                            LabelledValue("Muscle", WeightFormatter.full(it, state.weightUnit))
                        }
                        reading.bodyWaterMassGrams?.let {
                            LabelledValue("Water", WeightFormatter.full(it, state.weightUnit))
                        }
                        reading.basalMetabolismKcal?.let {
                            LabelledValue("Resting burn", WeightFormatter.calories(it))
                        }
                    }
                }
            }

            state.suggestedProfile?.let { suggested ->
                item {
                    SectionCard {
                        Text(
                            // The whole reason a household keeps profiles: a weight that plainly
                            // belongs to somebody else should not land in this person's trend.
                            text = "That looks like " + suggested.name + " rather than you.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSaveToSuggested) {
                                Text("Save as " + suggested.name)
                            }
                            OutlinedButton(onClick = onSave) { Text("No, it is mine") }
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = onDiscard) {
                            Text("Neither", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (state.needsConfirming) {
                item {
                    SectionCard {
                        Text(
                            // The other reading of a jump this size is a shared bathroom scale,
                            // and quietly filing someone else's weight ruins a trend for weeks.
                            text = "That is a long way from your last reading. Record it anyway?",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSave) { Text("It is mine") }
                            OutlinedButton(
                                onClick = onDiscard,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Not me") }
                        }
                    }
                }
            }

            if (state.stage == ScaleStage.BLOCKED) {
                item {
                    SectionCard {
                        Text(
                            text = explain(state.problem),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (state.problem == ScaleProblem.PERMISSION_MISSING) {
                                    permissionLauncher.launch(permissions.toTypedArray())
                                } else {
                                    onRetry()
                                }
                            },
                        ) {
                            Text(
                                if (state.problem == ScaleProblem.PERMISSION_MISSING) {
                                    "Allow"
                                } else {
                                    "Try again"
                                },
                            )
                        }
                    }
                }
            }

            val connectable = state.devices.filter { it != state.connectedTo }
            if (connectable.isNotEmpty() && state.reading == null) {
                item {
                    SectionCard {
                        SectionHeading("Scales nearby")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "A scale that broadcasts its weight needs no tap here. One that does not is connected to when you pick it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(connectable, key = { it.address }) { device ->
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(device.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onConnect(device) }) { Text("Use this one") }
                        }
                    }
                }
            }

            if (state.rememberedName != null) {
                item {
                    SectionCard {
                        LabelledValue("Remembered scale", state.rememberedName)
                        TextButton(onClick = onForgetScale) { Text("Forget it") }
                    }
                }
            }

            if (state.stage == ScaleStage.SAVED) {
                item {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

private fun headline(state: ScaleUiState): String = when (state.stage) {
    ScaleStage.BLOCKED -> "Nothing to listen with"
    ScaleStage.SEARCHING -> "Looking for your scale"
    ScaleStage.WAITING_FOR_WEIGHT -> "Step on the scale"
    ScaleStage.MEASURED -> when {
        state.suggestedProfile != null -> "Whose is this?"
        state.match == ScaleMatch.OUT_OF_RANGE -> "Is this you?"
        else -> "Recording"
    }
    ScaleStage.SAVED -> "Saved"
}

private fun explain(problem: ScaleProblem?): String = when (problem) {
    ScaleProblem.NO_BLUETOOTH_HARDWARE ->
        "This phone has no Bluetooth, so it cannot reach a scale. You can still enter a weight by hand."
    ScaleProblem.BLUETOOTH_OFF -> "Bluetooth is switched off. Turn it on and try again."
    ScaleProblem.PERMISSION_MISSING ->
        "Finding a scale needs permission to look for nearby devices. It is used only while this screen is open, and nothing about your location is read."
    ScaleProblem.SCAN_FAILED -> "Android would not start the search. Try again in a moment."
    ScaleProblem.CONNECTION_LOST -> "The scale stopped talking before it sent a weight."
    null -> "Something went wrong."
}
