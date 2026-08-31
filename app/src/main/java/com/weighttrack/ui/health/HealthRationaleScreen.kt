package com.weighttrack.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading

/**
 * What WeightTrack asks Health Connect for, and why.
 *
 * Health Connect sends people here from its own settings, through the rationale intent, and
 * expects an answer for each access rather than a link to a policy. It used to land on the home
 * screen, which answers nothing.
 *
 * Every line here has a feature behind it. An access with no feature is not explained away, it
 * is removed: height was requested for two releases after the last thing that used it went, and
 * it took a review to notice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthRationaleScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_rationale_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.health_rationale_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Use(title = R.string.health_rationale_weight_title, body = R.string.health_rationale_weight_body)
            Use(title = R.string.health_rationale_body_fat_title, body = R.string.health_rationale_body_fat_body)
            Use(
                title = R.string.health_rationale_background_title,
                body = R.string.health_rationale_background_body,
            )
            Use(title = R.string.health_rationale_history_title, body = R.string.health_rationale_history_body)
            Use(title = R.string.health_rationale_water_title, body = R.string.health_rationale_water_body)
            Use(title = R.string.health_rationale_food_title, body = R.string.health_rationale_food_body)
            Use(title = R.string.health_rationale_movement_title, body = R.string.health_rationale_movement_body)
            Use(title = R.string.health_rationale_sleep_title, body = R.string.health_rationale_sleep_body)
            Text(
                stringResource(R.string.health_rationale_nothing_leaves),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * One access and what it is for.
 *
 * Called with named arguments on purpose. A helper whose first argument is a resource id reads,
 * to the guard that holds every formatted string to its arguments, exactly like a call passing
 * that string a value it does not take.
 */
@Composable
private fun Use(title: Int, body: Int) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(stringResource(title))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
    }
}
