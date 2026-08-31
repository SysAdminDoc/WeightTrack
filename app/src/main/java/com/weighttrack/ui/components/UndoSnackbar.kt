package com.weighttrack.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.weighttrack.R
import com.weighttrack.ui.UndoOffer

/**
 * Shows one deletion's undo, and says which way it ended.
 *
 * Deletion is immediate with an undo, never a confirmation dialog, so this is the whole of what
 * a person is given to change their mind. Keyed on the offer's sequence rather than its words:
 * deleting two readings in a row produces the same sentence twice, and keying on the sentence
 * would leave the second deletion with nothing on screen at all.
 */
@Composable
fun UndoSnackbar(
    offer: UndoOffer?,
    snackbarHostState: SnackbarHostState,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Read outside the effect: stringResource is composition, and the effect runs after it.
    val undoLabel = stringResource(R.string.common_undo)
    LaunchedEffect(offer?.sequence) {
        val current = offer ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = current.message,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo() else onDismiss()
    }
}
