package com.weighttrack.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.EmptyState
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.core.format.WeightFormatter
import com.weighttrack.ui.log.tagLabel
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onQueryChange: (String) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onEdit: (WeightEntry) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    Column(modifier.fillMaxSize()) {
        if (state.inSelectionMode) {
            SelectionBar(
                count = state.selectedIds.size,
                onClear = onClearSelection,
                onSelectAll = onSelectAll,
                onDelete = onDeleteSelected,
            )
        } else {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.history_search_notes_and_tags)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (state.entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.History,
                title = if (state.query.isBlank()) stringResource(R.string.historyscreen_nothing_logged_yet) else stringResource(R.string.historyscreen_no_matches),
                message = if (state.query.isBlank()) {
                    stringResource(R.string.historyscreen_readings_you_log_will_show_up)
                } else {
                    stringResource(R.string.historyscreen_nothing_matches, state.query)
                },
                modifier = Modifier.padding(top = 48.dp),
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                SectionHeading(
                    text = stringResource(R.string.history_latest),
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
            }
            items(state.entries, key = { it.id }) { entry ->
                HistoryRow(
                    entry = entry,
                    unit = state.unit,
                    selected = entry.id in state.selectedIds,
                    selectionMode = state.inSelectionMode,
                    today = today,
                    onClick = {
                        if (state.inSelectionMode) onToggleSelection(entry.id) else onEdit(entry)
                    },
                    onLongClick = { onToggleSelection(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.history_clear_selection))
        }
        Text(
            text = stringResource(R.string.history_selected, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.history_select_all))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.history_delete_selected),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: WeightEntry,
    unit: WeightUnit,
    selected: Boolean,
    selectionMode: Boolean,
    today: LocalDate,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 48.dp)
                    .background(
                        if (entry.localDate == today) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    ),
            )
            Spacer(Modifier.size(12.dp))
            if (selectionMode) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = DateFormatters.relativeDay(entry.localDate, today),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                val details = buildList {
                    add(DateFormatters.time(entry.timestamp))
                    if (entry.source != EntrySource.MANUAL) add(sourceLabel(entry.source))
                    entry.bodyFatPercent?.let { add(stringResource(R.string.history_body_fat, it)) }
                    entry.tags.forEach { add(tagLabel(it)) }
                }
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.note?.let { note ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }

            Text(
                text = WeightFormatter.full(entry.grams, unit),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!selectionMode) {
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 14.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun sourceLabel(source: EntrySource): String = when (source) {
    EntrySource.MANUAL -> stringResource(R.string.history_manual)
    EntrySource.HEALTH_CONNECT -> stringResource(R.string.history_health_connect)
    EntrySource.IMPORT -> stringResource(R.string.history_imported)
    EntrySource.SCALE -> stringResource(R.string.history_scale)
}
