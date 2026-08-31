package com.weighttrack.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The app's one undo snackbar, above the navigation graph.
 *
 * Above it on purpose. Clearing a goal closes the goal screen, and an offer that lived on that
 * screen would be gone before it had been drawn.
 */
@HiltViewModel
class UndoViewModel @Inject constructor(
    private val coordinator: UndoCoordinator,
) : ViewModel() {

    val offer = coordinator.offer

    fun undo() = coordinator.undo()

    fun dismiss() = coordinator.dismiss()
}
