package com.weighttrack.ui.water

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.VolumeUnit
import com.weighttrack.data.repo.WaterEntry
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.core.format.VolumeFormatter
import com.weighttrack.ui.theme.HeroNumberStyle
import com.weighttrack.ui.theme.HeroUnitStyle
import java.time.LocalDate

/** Daily targets people actually pick, in both unit systems. */
private fun targetOptionsMl(unit: VolumeUnit): List<Int> = when (unit) {
    VolumeUnit.ML -> listOf(1_500, 2_000, 2_500, 3_000)
    VolumeUnit.FL_OZ -> listOf(
        UnitConverter.flOzToMl(50.0),
        UnitConverter.flOzToMl(64.0),
        UnitConverter.flOzToMl(80.0),
        UnitConverter.flOzToMl(100.0),
    )
}

/** The quick-add amounts, chosen so a glass, a bottle and a large bottle are all one tap. */
private fun quickAmountsMl(unit: VolumeUnit): List<Int> = when (unit) {
    VolumeUnit.ML -> listOf(150, 250, 330, 500)
    VolumeUnit.FL_OZ -> listOf(
        UnitConverter.flOzToMl(6.0),
        UnitConverter.flOzToMl(8.0),
        UnitConverter.flOzToMl(12.0),
        UnitConverter.flOzToMl(16.0),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaterScreen(
    state: WaterUiState,
    onAddServing: () -> Unit,
    onAdd: (Int) -> Unit,
    onRemove: (WaterEntry) -> Unit,
    onClearDay: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onSetTarget: (Int) -> Unit,
    onSetServing: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Water") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.entries.isNotEmpty()) {
                        TextButton(onClick = onClearDay) { Text("Clear day") }
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPreviousDay) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous day")
                    }
                    Text(
                        text = DateFormatters.relativeDay(state.date, today),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onNextDay, enabled = state.date.isBefore(today)) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Next day")
                    }
                }
            }

            item {
                SectionCard {
                    SectionHeading(
                        if (state.date == today) "Today's total" else "Total for the day",
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = VolumeFormatter.value(state.totalMl, state.unit),
                            style = HeroNumberStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = " ${VolumeFormatter.unitLabel(state.unit)}",
                            style = HeroUnitStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    WaterProgressBar(progress = state.progress)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.targetReached) {
                            "Target reached. Anything more is a bonus."
                        } else {
                            "${VolumeFormatter.full(state.remainingMl, state.unit)} to go out of ${VolumeFormatter.full(state.targetMl, state.unit)}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.targetReached) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            item {
                Button(
                    onClick = onAddServing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("Add ${VolumeFormatter.full(state.servingMl, state.unit)}")
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickAmountsMl(state.unit).forEach { amount ->
                        OutlinedButton(
                            onClick = { onAdd(amount) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = VolumeFormatter.value(amount, state.unit),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            if (state.entries.isNotEmpty()) {
                item { SectionHeading("Logged", Modifier.padding(top = 8.dp)) }
                items(state.entries, key = { it.id }) { entry ->
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = VolumeFormatter.full(entry.millilitres, state.unit),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = DateFormatters.time(entry.timestamp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRemove(entry) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    SectionHeading("Daily target")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targetOptionsMl(state.unit).forEach { amount ->
                            FilterChip(
                                selected = state.targetMl == amount,
                                onClick = { onSetTarget(amount) },
                                label = { Text(VolumeFormatter.full(amount, state.unit)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    SectionHeading("One serving")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickAmountsMl(state.unit).forEach { amount ->
                            FilterChip(
                                selected = state.servingMl == amount,
                                onClick = { onSetServing(amount) },
                                label = { Text(VolumeFormatter.full(amount, state.unit)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "The serving is what the big button and the home screen widget add.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.recentDays.size > 1) {
                item {
                    SectionCard {
                        SectionHeading("Recent days")
                        Spacer(Modifier.height(6.dp))
                        state.recentDays.take(7).forEach { day ->
                            LabelledValue(
                                label = DateFormatters.relativeDay(day.date, today),
                                value = VolumeFormatter.full(day.millilitres, state.unit),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaterProgressBar(progress: Float) {
    val animated by animateFloatAsState(targetValue = progress, label = "waterProgress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary),
        )
    }
}
