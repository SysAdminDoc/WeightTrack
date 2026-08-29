package com.weighttrack.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.diagnostics.CrashLogStore
import com.weighttrack.diagnostics.CrashReport
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
)

@HiltViewModel
class CrashLogViewModel @Inject constructor(
    private val store: CrashLogStore,
) : ViewModel() {

    private val _state = MutableStateFlow(CrashLogUiState())
    val state: StateFlow<CrashLogUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val reports = withContext(Dispatchers.IO) { store.list() }
            _state.update { it.copy(reports = reports, loaded = true) }
        }
    }

    fun open(report: CrashReport) {
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) { store.read(report.id) }
            _state.update { it.copy(openReportId = report.id, openReportBody = body) }
        }
    }

    fun close() {
        _state.update { it.copy(openReportId = null, openReportBody = null) }
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
