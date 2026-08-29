package com.weighttrack.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.sync.WearSummary
import java.time.LocalDate

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }
}

@Composable
fun WearApp(viewModel: WearViewModel = viewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val phoneReachable by viewModel.phoneReachable.collectAsStateWithLifecycle()

    MaterialTheme {
        when (val current = screen) {
            is WearScreen.Summary -> SummaryScreen(
                summary = summary,
                phoneReachable = phoneReachable,
                onLog = viewModel::openPicker,
            )
            is WearScreen.Picker -> PickerScreen(
                steps = current.steps,
                unit = summary?.weightUnit ?: WeightUnit.KG,
                onNudge = viewModel::nudge,
                onSave = viewModel::save,
                onCancel = viewModel::cancelPicker,
            )
            is WearScreen.Saved -> MessageScreen(
                message = current.message,
                onDismiss = viewModel::dismissSaved,
            )
        }
    }
}

@Composable
private fun SummaryScreen(
    summary: WearSummary?,
    phoneReachable: Boolean,
    onLog: () -> Unit,
) {
    ScrollingColumn {
        when {
            summary == null -> Text(
                text = if (phoneReachable) {
                    stringResource(R.string.wear_waiting_title)
                } else {
                    stringResource(R.string.wear_waiting_body)
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )

            summary.hidden -> Text(
                // The lock is on. A weight on a wrist is exactly what it exists to hide.
                text = stringResource(R.string.wear_locked_on_phone),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )

            !summary.hasData -> Text(
                text = stringResource(R.string.wear_no_readings),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> {
                Text(
                    text = summary.startingGrams
                        ?.let { WeightFormatter.full(it, summary.weightUnit) }
                        .orEmpty(),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.wear_trend_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                summary.weekChangeGrams?.let { change ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = WeightFormatter.delta(change, summary.weightUnit) + " this week",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                summary.lastLoggedEpochDay?.let { day ->
                    Text(
                        text = staleness(day),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onLog,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.wear_log_weight)) },
        )
    }
}

/**
 * The picker.
 *
 * The crown is the point of it: turning a wrist is faster than tapping a plus button twenty
 * times. The buttons stay for watches without a rotating crown.
 */
@Composable
internal fun PickerScreen(
    steps: Int,
    unit: WeightUnit,
    onNudge: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 24.dp)
            .onRotaryScrollEvent { event ->
                onNudge(WearWeightPicker.rotarySteps(event.verticalScrollPixels))
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = WearWeightPicker.label(steps, unit),
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNudge(-1) }, label = { Text("-") })
            Button(onClick = { onNudge(1) }, label = { Text("+") })
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.wear_save)) },
        )
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.wear_cancel)) },
        )
    }
}

@Composable
private fun MessageScreen(message: String, onDismiss: () -> Unit) {
    ScrollingColumn {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.wear_done)) })
    }
}

@Composable
private fun ScrollingColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** How old the last reading is, in the plainest words that fit on a watch. */
internal fun staleness(lastLoggedEpochDay: Long, today: LocalDate = LocalDate.now()): String {
    val days = today.toEpochDay() - lastLoggedEpochDay
    return when {
        days <= 0L -> "logged today"
        days == 1L -> "logged yesterday"
        days < 7L -> "logged $days days ago"
        days < 14L -> "logged last week"
        else -> "logged ${days / 7} weeks ago"
    }
}
