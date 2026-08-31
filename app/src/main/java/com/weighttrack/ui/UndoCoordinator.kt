package com.weighttrack.ui

import com.weighttrack.data.repo.UndoableDelete
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A deletion the app is still offering to put back.
 *
 * [sequence] climbs with every offer so the snackbar shows again when the same words follow the
 * same words. Keying an effect on the message alone means a second delete of the same kind puts
 * nothing on screen, and the person is left with no way back from a deletion they can see happened.
 */
data class UndoOffer(
    val sequence: Long,
    val message: String,
)

/**
 * The one deletion the app is holding open.
 *
 * Every destructive action takes the thing away at once and offers this instead of asking first.
 * A confirmation dialog interrupts the ninety-nine taps that meant it to save the one that did not.
 *
 * Held for the whole app rather than per screen, for two reasons. Clearing a goal closes the goal
 * screen, and an offer that lived on that screen would be gone before it had been drawn. And the
 * restore has to finish even if the person walks away from the screen that started it, which a
 * view model's own scope cannot promise.
 *
 * One offer at a time: a second delete replaces the first, and the first is let go of properly on
 * the way out, or a photo waits an hour in the recovery folder for nobody.
 *
 * Nothing here survives the process. If the app is killed while the snackbar is up the deletion
 * stands, which is one answer rather than two.
 */
@Singleton
class UndoCoordinator @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _offer = MutableStateFlow<UndoOffer?>(null)
    val offer: StateFlow<UndoOffer?> = _offer.asStateFlow()

    private var pending: UndoableDelete? = null
    private var afterwards: suspend () -> Unit = {}
    private var sequence = 0L

    /**
     * Offers a deletion. A delete that removed nothing offers nothing.
     *
     * [afterwards] is what the screen needs doing once the row is back, refreshing the widget and
     * the watch in nearly every case.
     */
    fun offer(delete: UndoableDelete?, message: String, afterwards: suspend () -> Unit = {}) {
        if (delete == null) return
        release()
        pending = delete
        this.afterwards = afterwards
        sequence += 1
        _offer.value = UndoOffer(sequence, message)
    }

    fun undo() {
        val restore = pending ?: return
        val then = afterwards
        pending = null
        afterwards = {}
        _offer.value = null
        scope.launch {
            // Caught rather than allowed out. This scope has no owner to hand a failure to, so
            // an exception here reaches the thread's default handler and takes the app down; a
            // restore that cannot happen has to be a restore that did not happen. A profile
            // whose rows arrived from a sync while the snackbar was up is the real case: the
            // insert aborts on a unique index and there is nothing to be done about it.
            runCatching { restore.undo() }
            runCatching { then() }
        }
    }

    /** The snackbar has gone. The deletion stands. */
    fun dismiss() {
        release()
        _offer.value = null
    }

    private fun release() {
        val lapsing = pending ?: return
        pending = null
        afterwards = {}
        scope.launch { runCatching { lapsing.lapse() } }
    }
}
