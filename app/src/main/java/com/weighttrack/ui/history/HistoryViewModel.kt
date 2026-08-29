package com.weighttrack.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.widget.SurfaceUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val entries: List<WeightEntry> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
    val query: String = "",
    val selectedIds: Set<Long> = emptySet(),
) {
    val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val weightRepository: WeightRepository,
    private val SurfaceUpdater: SurfaceUpdater,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Entries removed but still undoable, so the snackbar can put them back. */
    private val pendingUndo = MutableStateFlow<List<WeightEntry>>(emptyList())
    val undoAvailable: StateFlow<Int> = pendingUndo.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val entries = query
        .debounce { if (it.isEmpty()) 0L else 200L }
        .flatMapLatest { weightRepository.search(it) }

    val state: StateFlow<HistoryUiState> = combine(
        entries,
        settingsRepository.settings.map { it.weightUnit },
        query,
        selectedIds,
    ) { list, unit, currentQuery, selection ->
        HistoryUiState(
            entries = list,
            unit = unit,
            query = currentQuery,
            // Selecting a row then filtering it away would otherwise leave an invisible
            // selection that the delete button still acts on.
            selectedIds = selection intersect list.map { it.id }.toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun onQueryChange(value: String) = query.update { value }

    fun toggleSelection(id: Long) = selectedIds.update { current ->
        if (id in current) current - id else current + id
    }

    fun clearSelection() = selectedIds.update { emptySet() }

    fun selectAll() {
        selectedIds.update { state.value.entries.map { it.id }.toSet() }
    }

    fun deleteSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        val removed = state.value.entries.filter { it.id in ids }
        viewModelScope.launch {
            weightRepository.deleteByIds(ids.toList())
            SurfaceUpdater.refresh()
            pendingUndo.value = removed
            selectedIds.value = emptySet()
        }
    }

    fun delete(entry: WeightEntry) {
        viewModelScope.launch {
            weightRepository.delete(entry)
            SurfaceUpdater.refresh()
            pendingUndo.value = listOf(entry)
        }
    }

    /**
     * Puts deleted readings back.
     *
     * The rows are reinserted with their original client record ids, so a restored reading is
     * still the same record as far as Health Connect is concerned rather than a fresh one.
     */
    fun undoDelete() {
        val restore = pendingUndo.value
        if (restore.isEmpty()) return
        viewModelScope.launch {
            weightRepository.upsertAll(restore.map { it.copy(id = 0) })
            SurfaceUpdater.refresh()
            pendingUndo.value = emptyList()
        }
    }

    fun consumeUndo() {
        pendingUndo.value = emptyList()
    }
}
