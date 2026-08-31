package com.weighttrack.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.core.model.BodyComposition
import com.weighttrack.core.model.CompositionQuality
import com.weighttrack.core.model.WeightUnit

/**
 * What a scale said beyond the weight, and how much of it to believe.
 *
 * Two rules run through this. Nothing is shown without saying where it came from, because a
 * number on its own reads as a measurement. And a bioelectrical impedance figure is never
 * presented as one: the scale passes a current through somebody and estimates from how easily it
 * travels, and turning that into a percentage is the manufacturer's own unpublished arithmetic.
 * It is worth watching over weeks and worth nothing as a single reading.
 *
 * A weight-only capture is not a failure and does not read as one. It says so and stops.
 */
@Composable
fun CompositionDetail(
    composition: BodyComposition?,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (composition == null || !composition.hasAnything) {
            Text(
                stringResource(R.string.composition_weight_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            text = provenance(composition),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        composition.muscleMassGrams?.let {
            LabelledValue(
                label = stringResource(R.string.composition_muscle_mass),
                value = WeightFormatter.full(it, unit),
            )
        }
        composition.fatFreeMassGrams?.let {
            LabelledValue(
                label = stringResource(R.string.composition_fat_free_mass),
                value = WeightFormatter.full(it, unit),
            )
        }
        composition.softLeanMassGrams?.let {
            LabelledValue(
                label = stringResource(R.string.composition_soft_lean_mass),
                value = WeightFormatter.full(it, unit),
            )
        }
        composition.bodyWaterMassGrams?.let {
            LabelledValue(
                label = stringResource(R.string.composition_body_water),
                value = WeightFormatter.full(it, unit),
            )
        }
        composition.musclePercent?.let {
            LabelledValue(
                label = stringResource(R.string.composition_muscle_percent),
                value = "%.1f%%".format(it),
            )
        }
        composition.basalMetabolismKcal?.let {
            LabelledValue(
                label = stringResource(R.string.composition_basal_metabolism),
                value = "%.0f".format(it),
            )
        }
        composition.scaleBmi?.let {
            LabelledValue(
                label = stringResource(R.string.composition_scale_bmi),
                value = "%.1f".format(it),
            )
        }
        composition.impedanceOhms?.let {
            LabelledValue(
                label = stringResource(R.string.composition_impedance),
                value = "%.0f Ω".format(it),
            )
        }
        // Said whenever a figure came out of a current rather than off a tape measure, which is
        // every one of them a scale like this reports.
        if (composition.impedanceOhms != null || composition.quality ==
            CompositionQuality.REPORTED_BY_SCALE
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.composition_how_it_is_worked_out),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Where the figures came from.
 *
 * The scale's own name when it gave one. "Your scale" when it did not: a made-up model name is
 * worse than none, and the words for a scale that did not introduce itself belong here with the
 * rest of the app's text rather than in the Bluetooth layer.
 */
@Composable
private fun provenance(composition: BodyComposition): String = when (composition.quality) {
    CompositionQuality.REPORTED_BY_SCALE -> composition.device
        ?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.composition_reported_by_scale, it) }
        ?: stringResource(R.string.composition_reported_by_a_scale)
    CompositionQuality.ESTIMATED_BY_APP -> stringResource(R.string.composition_estimated_here)
    CompositionQuality.ENTERED_BY_HAND -> stringResource(R.string.composition_entered_by_hand)
}
