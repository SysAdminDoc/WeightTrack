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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.weighttrack.R
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
    val screenContext = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scale_weigh_in)) },
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
                        SectionHeading(stringResource(R.string.scale_what_the_scale_measured))
                        Spacer(Modifier.height(4.dp))
                        LabelledValue(
                            stringResource(R.string.scalescreen_weight),
                            WeightFormatter.full(reading.grams, state.weightUnit),
                        )
                        reading.bodyFatPercent?.let {
                            LabelledValue(stringResource(R.string.scalescreen_body_fat), String.format("%.1f%%", it))
                        }
                        reading.muscleMassGrams?.let {
                            LabelledValue(stringResource(R.string.scalescreen_muscle), WeightFormatter.full(it, state.weightUnit))
                        }
                        reading.bodyWaterMassGrams?.let {
                            LabelledValue(stringResource(R.string.scalescreen_water), WeightFormatter.full(it, state.weightUnit))
                        }
                        reading.basalMetabolismKcal?.let {
                            LabelledValue(stringResource(R.string.scalescreen_resting_burn), WeightFormatter.calories(it))
                        }
                    }
                }
            }

            state.suggestedProfile?.takeIf { state.stage == ScaleStage.MEASURED }?.let { suggested ->
                item {
                    SectionCard {
                        Text(
                            // The whole reason a household keeps profiles: a weight that plainly
                            // belongs to somebody else should not land in this person's trend.
                            text = stringResource(
                                R.string.scale_that_looks_like_somebody_else,
                                suggested.name,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSaveToSuggested) {
                                Text(stringResource(R.string.scale_save_as_somebody, suggested.name))
                            }
                            OutlinedButton(onClick = onSave) { Text(stringResource(R.string.scale_no_it_is_mine)) }
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = onDiscard) {
                            Text(stringResource(R.string.scale_neither), color = MaterialTheme.colorScheme.error)
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
                            text = stringResource(R.string.scale_that_is_a_long_way_from),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSave) { Text(stringResource(R.string.scale_it_is_mine)) }
                            OutlinedButton(
                                onClick = onDiscard,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text(stringResource(R.string.scale_not_me)) }
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
                                when (state.problem) {
                                    ScaleProblem.PERMISSION_MISSING ->
                                        permissionLauncher.launch(permissions.toTypedArray())
                                    // Only the system can drop a stale pairing, so the honest
                                    // thing is to take somebody to where it is done.
                                    ScaleProblem.BOND_LOST -> {
                                        runCatching {
                                            screenContext.startActivity(
                                                android.content.Intent(
                                                    android.provider.Settings
                                                        .ACTION_BLUETOOTH_SETTINGS,
                                                ),
                                            )
                                        }
                                        Unit
                                    }
                                    else -> onRetry()
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    when (state.problem) {
                                        ScaleProblem.PERMISSION_MISSING -> R.string.scale_allow
                                        ScaleProblem.BOND_LOST -> R.string.scale_open_bluetooth_settings
                                        else -> R.string.scalescreen_try_again
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            val connectable = state.devices.filter { it != state.connectedTo }
            if (connectable.isNotEmpty() && state.reading == null) {
                item {
                    SectionCard {
                        SectionHeading(stringResource(R.string.scale_scales_nearby))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.scale_a_scale_that_broadcasts_its_weight),
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
                            TextButton(onClick = { onConnect(device) }) { Text(stringResource(R.string.scale_use_this_one)) }
                        }
                    }
                }
            }

            if (state.rememberedName != null) {
                item {
                    SectionCard {
                        LabelledValue(stringResource(R.string.scalescreen_remembered_scale), state.rememberedName)
                        TextButton(onClick = onForgetScale) { Text(stringResource(R.string.scale_forget_it)) }
                    }
                }
            }

            if (state.stage == ScaleStage.SAVED) {
                item {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(R.string.common_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun headline(state: ScaleUiState): String = when (state.stage) {
    ScaleStage.BLOCKED -> stringResource(R.string.scale_nothing_to_listen_with)
    ScaleStage.SEARCHING -> stringResource(R.string.scale_looking_for_your_scale)
    ScaleStage.WAITING_FOR_WEIGHT -> stringResource(R.string.scale_step_on_the_scale)
    ScaleStage.MEASURED -> when {
        state.suggestedProfile != null -> stringResource(R.string.scale_whose_is_this)
        state.match == ScaleMatch.OUT_OF_RANGE -> stringResource(R.string.scale_is_this_you)
        else -> stringResource(R.string.scale_recording)
    }
    ScaleStage.SAVED -> stringResource(R.string.scale_saved)
}

@Composable
private fun explain(problem: ScaleProblem?): String = when (problem) {
    ScaleProblem.NO_BLUETOOTH_HARDWARE ->
        stringResource(R.string.scale_this_phone_has_no_bluetooth_so)
    ScaleProblem.BLUETOOTH_OFF -> stringResource(R.string.scale_bluetooth_is_switched_off_turn_it)
    ScaleProblem.PERMISSION_MISSING ->
        stringResource(R.string.scale_finding_a_scale_needs_permission_to)
    ScaleProblem.SCAN_FAILED -> stringResource(R.string.scale_android_would_not_start_the_search)
    ScaleProblem.CONNECTION_LOST -> stringResource(R.string.scale_the_scale_stopped_talking_before_it)
    ScaleProblem.BOND_LOST -> stringResource(R.string.scale_bond_lost)
    null -> stringResource(R.string.scale_something_went_wrong)
}
