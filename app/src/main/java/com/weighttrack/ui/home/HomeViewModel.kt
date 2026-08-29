package com.weighttrack.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.domain.ProgressCalculator
import com.weighttrack.domain.ProgressSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    progressCalculator: ProgressCalculator,
) : ViewModel() {

    val snapshot: StateFlow<ProgressSnapshot> = progressCalculator.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProgressSnapshot.empty(AppSettings()),
        )
}
