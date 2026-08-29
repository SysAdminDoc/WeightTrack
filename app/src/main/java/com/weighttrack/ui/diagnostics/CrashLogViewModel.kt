package com.weighttrack.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.diagnostics.CrashLogStore
import com.weighttrack.diagnostics.CrashReport
import com.weighttrack.diagnostics.RuntimeLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CrashLogUiState(
    val reports: List<CrashReport> = emptyList(),
    val openReportId: String? = null,
    val openReportBody: String? = null,
    val loaded: Boolean = false,
    /** Whether anything has gone wrong quietly enough to be worth sending on. */
    val activityLogAvailable: Boolean = false,
)

@HiltViewModel
class CrashLogViewModel @Inject constructor(
    private val store: CrashLogStore,
    private val runtimeLog: RuntimeLog,
) : ViewModel() {

    private val _state = MutableStateFlow(CrashLogUiState())
    val state: StateFlow<CrashLogUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val reports = withContext(Dispatchers.IO) { store.list() }
            val hasActivity = withContext(Dispatchers.IO) { !runtimeLog.isEmpty() }
            _state.update {
                it.copy(reports = reports, loaded = true, activityLogAvailable = hasActivity)
            }
        }
    }

    fun open(report: CrashReport) {
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) { store.read(report.id) }
            if (body == null) {
                // The file went while the list was on screen, most likely pruned by a later
                // crash. Silently doing nothing looks like a broken tap, so the list is
                // refreshed instead and the row disappears.
                _state.update { it.copy(openReportId = null, openReportBody = null) }
                refresh()
                return@launch
            }
            _state.update { it.copy(openReportId = report.id, openReportBody = body) }
        }
    }

    fun close() {
        _state.update { it.copy(openReportId = null, openReportBody = null) }
    }

    /**
     * Hands the activity log to whatever wants to send it.
     *
     * Read on demand rather than held in state: it is up to half a megabyte, and nothing on
     * the screen displays it.
     */
    fun shareActivityLog(share: (String) -> Unit) {
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) { runtimeLog.read() }
            if (body.isNotBlank()) share(body)
        }
    }

    fun delete(report: CrashReport) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(report.id) }
            // Closing first avoids leaving the reader open on a file that no longer exists.
            _state.update { it.copy(openReportId = null, openReportBody = null) }
            refresh()
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteAll() }
            _state.update { it.copy(openReportId = null, openReportBody = null) }
            refresh()
        }
    }
}
