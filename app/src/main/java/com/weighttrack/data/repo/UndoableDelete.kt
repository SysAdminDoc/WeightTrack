package com.weighttrack.data.repo

/**
 * A deletion that has already happened and can still be put back.
 *
 * Every destructive action in the app takes the thing away at once and offers this instead of
 * asking first. A confirmation dialog interrupts the ninety-nine taps that meant it to save the
 * one that did not; an undo costs the ninety-nine nothing.
 *
 * The row and its tombstone go in one transaction, as they always have, so a process killed
 * mid-delete cannot leave a row missing here and alive on another device. [undo] puts the row
 * back under its original identity and forgets the tombstone, which is what stops the other
 * device deleting it again on the next sync.
 *
 * Nothing about this survives the process. That is deliberate: if the app is killed while the
 * snackbar is up, the deletion stands. One answer is better than two, and the alternative is
 * holding a deletion back long enough for another device to hand the row straight back.
 *
 * [release] comes first so [restore], which every caller passes, can be a trailing lambda.
 */
class UndoableDelete internal constructor(
    /**
     * Anything the undo window was holding on to, released when the offer lapses.
     *
     * Only the photo delete has work here: its file is moved aside rather than unlinked, and
     * something has to unlink it once nobody can ask for it back.
     */
    private val release: suspend () -> Unit = {},
    private val restore: suspend () -> Unit,
) {
    suspend fun undo() = restore()

    suspend fun lapse() = release()
}
