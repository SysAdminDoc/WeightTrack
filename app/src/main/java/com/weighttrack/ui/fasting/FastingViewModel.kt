package com.weighttrack.ui.fasting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.model.Fast
import com.weighttrack.core.model.FastingPreset
import com.weighttrack.data.repo.FastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class FastingUiState(
    val active: Fast? = null,
    val completed: List<Fast> = emptyList(),
    val selectedPreset: FastingPreset = FastingPreset.DEFAULT,
    val now: Instant = Instant.now(),
) {
    val isRunning: Boolean get() = active != null
}

@HiltViewModel
class FastingViewModel @Inject constructor(
    private val fastRepository: FastRepository,
) : ViewModel() {

    private val selectedPreset = MutableStateFlow(FastingPreset.DEFAULT)

    /**
     * A ticking clock, so the running timer counts up on screen.
     *
     * A cold flow rather than a loop in `init`: it only runs while something is collecting the
     * state, so backgrounding the app with this screen open stops the tick instead of leaving
     * it counting for the rest of the day.
     */
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(1_000)
        }
    }

    private val _editing = MutableStateFlow<Fast?>(null)
    val editing: StateFlow<Fast?> = _editing.asStateFlow()

    val state: StateFlow<FastingUiState> = combine(
        fastRepository.observeActive(),
        fastRepository.observeCompleted(),
        selectedPreset,
        clock,
    ) { active, completed, preset, now ->
        FastingUiState(
            active = active,
            completed = completed,
            // While a fast runs, the shown preset is the one it was started with, not
            // whatever was tapped afterwards.
            selectedPreset = active?.let { FastingPreset.forMinutes(it.targetMinutes) } ?: preset,
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FastingUiState())

    fun selectPreset(preset: FastingPreset) {
        selectedPreset.value = preset
    }

    fun start() {
        viewModelScope.launch {
            fastRepository.start(targetMinutes = selectedPreset.value.targetMinutes)
        }
    }

    fun stop() {
        viewModelScope.launch { fastRepository.stop() }
    }

    fun cancel() {
        viewModelScope.launch { fastRepository.cancelActive() }
    }

    fun startEditing(fast: Fast) {
        _editing.value = fast
    }

    fun cancelEditing() {
        _editing.value = null
    }

    /** Saves a corrected fast. A start after the end is refused rather than stored backwards. */
    fun saveEdit(start: Instant, end: Instant?) {
        val editing = _editing.value ?: return
        if (end != null && end.isBefore(start)) {
            _editing.value = null
            return
        }
        viewModelScope.launch {
            fastRepository.update(editing.copy(start = start, end = end))
            _editing.value = null
        }
    }

    fun delete(fast: Fast) {
        viewModelScope.launch {
            fastRepository.delete(fast)
            _editing.value = null
        }
    }
}
