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
    /** The most recent entries, newest last, for reading on the screen itself. */
    val activityLog: List<String> = emptyList(),
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
            // The tail only. The file runs to half a megabyte and nobody scrolls that on a
            // phone; what matters is what happened just before somebody came looking.
            val recent = withContext(Dispatchers.IO) {
                runtimeLog.read().lines().filter { it.isNotBlank() }.takeLast(RECENT_ENTRIES)
            }
            _state.update {
                it.copy(
                    reports = reports,
                    loaded = true,
                    activityLogAvailable = recent.isNotEmpty(),
                    activityLog = recent,
                )
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

    private companion object {
        /** Enough to see what led up to a failure, few enough to read. */
        const val RECENT_ENTRIES = 20
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
