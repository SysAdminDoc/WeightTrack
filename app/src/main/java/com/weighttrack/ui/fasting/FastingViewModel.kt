package com.weighttrack.ui.fasting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.R
import com.weighttrack.core.model.Fast
import com.weighttrack.core.model.FastingPreset
import com.weighttrack.data.repo.FastRepository
import com.weighttrack.data.repo.FastUpdateResult
import com.weighttrack.ui.AppStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * A finished fast with its length worked out once.
 *
 * A completed fast has a fixed end, so nothing about a history row depends on the clock. Keeping
 * the length here is what lets the ticking timer redraw without touching the list.
 */
data class CompletedFast(
    val fast: Fast,
    val length: Duration,
    val reachedTarget: Boolean,
) {
    val id: Long get() = fast.id
}

/** History plus the three numbers the summary card shows, all from one pass. */
data class FastingHistory(
    val fasts: List<CompletedFast> = emptyList(),
    val reached: Int = 0,
    val longest: Duration? = null,
) {
    val recorded: Int get() = fasts.size
    val isEmpty: Boolean get() = fasts.isEmpty()
}

data class FastingUiState(
    val active: Fast? = null,
    val history: FastingHistory = FastingHistory(),
    val selectedPreset: FastingPreset = FastingPreset.DEFAULT,
) {
    val isRunning: Boolean get() = active != null
}

@HiltViewModel
class FastingViewModel @Inject constructor(
    private val strings: AppStrings,
    private val fastRepository: FastRepository,
    private val undoOffers: com.weighttrack.ui.UndoCoordinator,
) : ViewModel() {

    private val selectedPreset = MutableStateFlow(FastingPreset.DEFAULT)

    private val active: Flow<Fast?> = fastRepository.observeActive()

    /**
     * The history, summarised off the interface thread and only when it changes.
     *
     * This used to be recomputed inside the per-second tick, which walked the whole of an
     * unbounded history every second on the main thread.
     */
    private val history: Flow<FastingHistory> = fastRepository.observeCompleted()
        .map(::summarise)
        .flowOn(Dispatchers.Default)

    private val _editing = MutableStateFlow<Fast?>(null)
    val editing: StateFlow<Fast?> = _editing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * A ticking clock, so the running timer counts up on screen.
     *
     * Separate from [state] so a tick only redraws the timer, and cold so it stops when nothing
     * is collecting. It only ticks while a fast is actually running; the rest of the time there
     * is nothing on screen that changes with the second.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val now: StateFlow<Instant> = active
        .map { it != null }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (!running) {
                flowOf(Instant.now())
            } else {
                flow {
                    while (true) {
                        emit(Instant.now())
                        delay(1_000)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Instant.now())

    val state: StateFlow<FastingUiState> = combine(
        active,
        history,
        selectedPreset,
    ) { active, history, preset ->
        FastingUiState(active = active, history = history, selectedPreset = preset)
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

    /** Saves a corrected fast, running or finished, and says so when it cannot. */
    fun saveEdit(start: Instant, end: Instant?) {
        val editing = _editing.value ?: return
        viewModelScope.launch {
            val result = fastRepository.update(editing.copy(start = start, end = end))
            _message.value = when (result) {
                FastUpdateResult.SAVED -> null
                FastUpdateResult.BACKWARDS -> strings[R.string.fasting_that_fast_would_end_before_it]
                FastUpdateResult.MISSING -> strings[R.string.fasting_that_fast_is_no_longer_here]
            }
            _editing.value = null
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun delete(fast: Fast) {
        viewModelScope.launch {
            val removed = fastRepository.delete(fast)
            undoOffers.offer(removed, strings[R.string.fasting_fast_deleted])
            _editing.value = null
        }
    }

    companion object {
        /** One pass over the finished fasts: rows, how many hit the target, and the longest. */
        fun summarise(fasts: List<Fast>): FastingHistory {
            var reached = 0
            var longestMillis = -1L
            val rows = fasts.map { fast ->
                // The end is fixed, so any instant gives the recorded length.
                val length = fast.elapsed(fast.start)
                val hit = fast.reachedTarget(fast.start)
                if (hit) reached++
                if (length.toMillis() > longestMillis) longestMillis = length.toMillis()
                CompletedFast(fast = fast, length = length, reachedTarget = hit)
            }
            return FastingHistory(
                fasts = rows,
                reached = reached,
                longest = longestMillis.takeIf { it >= 0 }?.let(Duration::ofMillis),
            )
        }
    }
}
