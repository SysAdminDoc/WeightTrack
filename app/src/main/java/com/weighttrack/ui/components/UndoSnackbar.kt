package com.weighttrack.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.weighttrack.R
import com.weighttrack.ui.UndoOffer
import kotlinx.coroutines.CancellationException

/**
 * Shows one deletion's undo, and says which way it ended.
 *
 * Deletion is immediate with an undo, never a confirmation dialog, so this is the whole of what
 * a person is given to change their mind. Keyed on the offer's sequence rather than its words:
 * deleting two readings in a row produces the same sentence twice, and keying on the sentence
 * would leave the second deletion with nothing on screen at all.
 *
 * The offer is let go of whether the snackbar was answered, timed out, or was cancelled under
 * the screen by a rotation. Left standing through a cancellation it came back later, offering to
 * undo a deletion whose photograph the recovery sweep had already collected: the person is told
 * the picture is back and it is gone for good.
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
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(offer?.sequence) {
        val current = offer ?: return@LaunchedEffect
        try {
            val result = snackbarHostState.showSnackbar(
                message = current.message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo() else dismiss()
        } catch (cancelled: CancellationException) {
            // The screen went before the snackbar did. The deletion stands, which is the same
            // answer a process death gives, rather than an offer that outlives what it points at.
            dismiss()
            throw cancelled
        }
    }
}
