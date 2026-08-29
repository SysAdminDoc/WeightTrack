package com.weighttrack.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** App-wide settings, held above the navigation graph because the theme depends on them. */
@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            // Null means "not read yet". Rendering against defaults first would flash the
            // wrong theme and briefly show onboarding to someone who finished it months ago.
            initialValue = null,
        )

    /**
     * Starts locked. A fresh view model means a fresh process or a fresh activity, and both are
     * cases where the person has not authenticated yet. The lock only shows when the setting is
     * on, so this costs nothing when the feature is off.
     */
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _lockError = MutableStateFlow<String?>(null)
    val lockError: StateFlow<String?> = _lockError.asStateFlow()

    fun unlock() {
        _lockError.value = null
        _locked.value = false
    }

    fun lock() {
        _locked.value = true
    }

    fun onUnlockFailed(message: String?) {
        _lockError.value = message
    }
}
