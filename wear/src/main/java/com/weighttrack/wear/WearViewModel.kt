package com.weighttrack.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.sync.WearSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the watch is showing right now. */
sealed interface WearScreen {
    data object Summary : WearScreen

    /** The picker, holding its position as a step count rather than a weight. */
    data class Picker(val steps: Int) : WearScreen

    data class Saved(val message: String) : WearScreen
}

class WearViewModel(application: Application) : AndroidViewModel(application) {

    private val store = WearSummaryStore(application)
    private val phone = PhoneLink(application)

    val summary: StateFlow<WearSummary?> =
        store.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _screen = MutableStateFlow<WearScreen>(WearScreen.Summary)
    val screen: StateFlow<WearScreen> = _screen.asStateFlow()

    private val _phoneReachable = MutableStateFlow(true)
    val phoneReachable: StateFlow<Boolean> = _phoneReachable.asStateFlow()

    init {
        refresh()
    }

    /** Asks the phone for fresh figures. The cached ones stay on screen while it answers. */
    fun refresh() {
        viewModelScope.launch {
            _phoneReachable.value = phone.isPhoneReachable()
            phone.requestSummary()
        }
    }

    fun openPicker() {
        val unit = summary.value?.weightUnit ?: com.weighttrack.core.model.WeightUnit.KG
        val opening = summary.value?.startingGrams ?: WearWeightPicker.FALLBACK_GRAMS
        _screen.value = WearScreen.Picker(WearWeightPicker.stepsFor(opening, unit))
    }

    fun nudge(steps: Int) {
        val current = _screen.value as? WearScreen.Picker ?: return
        val unit = summary.value?.weightUnit ?: com.weighttrack.core.model.WeightUnit.KG
        _screen.value = current.copy(steps = WearWeightPicker.clamp(current.steps + steps, unit))
    }

    fun cancelPicker() {
        _screen.value = WearScreen.Summary
    }

    fun save() {
        val picker = _screen.value as? WearScreen.Picker ?: return
        val unit = summary.value?.weightUnit ?: com.weighttrack.core.model.WeightUnit.KG
        val grams = WearWeightPicker.gramsFor(picker.steps, unit)
        viewModelScope.launch {
            val result = phone.logWeight(grams)
            _screen.value = WearScreen.Saved(
                when (result) {
                    // Queued, not sent: the Data Layer holds it until the phone is in range, so
                    // promising it has arrived would be a lie on a walk.
                    LogResult.QUEUED -> getApplication<Application>().getString(R.string.wear_queued)
                    LogResult.FAILED -> getApplication<Application>().getString(R.string.wear_failed)
                },
            )
        }
    }

    fun dismissSaved() {
        _screen.value = WearScreen.Summary
        refresh()
    }
}
