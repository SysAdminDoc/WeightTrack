package com.weighttrack.ui.goal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.math.GoalProjection
import com.weighttrack.core.math.NoEtaReason
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.format.DateFormatters
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How the date was worked out, for anybody who wants to know.
 *
 * A projected date is the thing people most want and least trust in an app like this, and with
 * good reason: plenty of them will happily name a day in 2071. Saying how many readings it was
 * fitted to, how fast it thinks somebody is going and how wide the answer is turns a number
 * somebody has to believe into one they can judge.
 *
 * When there is no date it says which of the reasons applied, because a blank where a date should
 * be reads as the app being broken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectionExplainer(
    projection: GoalProjection,
    unit: WeightUnit,
    onDismiss: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(
                text = stringResource(R.string.projection_how_this_was_worked_out),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(10.dp))

            LabelledValue(
                stringResource(R.string.projection_readings_used),
                stringResource(R.string.projection_days_of_readings, projection.fittedDays),
            )
            LabelledValue(
                stringResource(R.string.projection_rate),
                stringResource(
                    R.string.projection_a_week,
                    WeightFormatter.delta(projection.fittedGramsPerDay * 7, unit),
                ),
            )
            LabelledValue(
                stringResource(R.string.projection_still_to_go),
                WeightFormatter.full(abs(projection.remainingGrams).roundToInt(), unit),
            )

            val earliest = projection.etaDateOptimistic(today)
            val latest = projection.etaDatePessimistic(today)
            if (earliest != null && latest != null && earliest != latest) {
                LabelledValue(
                    stringResource(R.string.projection_somewhere_between),
                    stringResource(
                        R.string.projection_and,
                        DateFormatters.shortDate(earliest, today),
                        DateFormatters.shortDate(latest, today),
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(explanationFor(projection)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The sentence under the figures: what the date rests on, or why there is not one.
 *
 * Answers a resource rather than the words, so the choice stays a plain function a test can check
 * and the wording lives in the file translators work from.
 */
internal fun explanationFor(projection: GoalProjection): Int = when (projection.noEtaReason) {
    null -> R.string.projection_explained
    NoEtaReason.REACHED -> R.string.projection_reached
    NoEtaReason.NOT_ENOUGH_DATA -> R.string.projection_not_enough_data
    NoEtaReason.FLAT -> R.string.projection_flat
    NoEtaReason.WRONG_WAY -> R.string.projection_wrong_way
    NoEtaReason.TOO_FAR_OFF -> R.string.projection_too_far_off
}
