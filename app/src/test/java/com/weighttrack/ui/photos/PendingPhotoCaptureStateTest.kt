package com.weighttrack.ui.photos

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class PendingPhotoCaptureStateTest {

    @Test
    fun `the camera target survives saved state recreation`() {
        val target = File("progress-photos/photo-restored.jpg").absoluteFile
        val originalHandle = SavedStateHandle()
        PendingPhotoCaptureState(originalHandle).remember(target)

        val restoredHandle = SavedStateHandle(
            mapOf(PENDING_CAPTURE_PATH_KEY to originalHandle.get<String>(PENDING_CAPTURE_PATH_KEY)),
        )

        assertThat(PendingPhotoCaptureState(restoredHandle).pending()).isEqualTo(target)
    }

    @Test
    fun `a stale camera callback cannot clear a newer target`() {
        val stale = File("progress-photos/photo-stale.jpg").absoluteFile
        val current = File("progress-photos/photo-current.jpg").absoluteFile
        val state = PendingPhotoCaptureState(SavedStateHandle())
        state.remember(stale)
        state.remember(current)

        state.clear(stale)

        assertThat(state.pending()).isEqualTo(current)
    }

    @Test
    fun `the matching target is cleared after it is filed`() {
        val target = File("progress-photos/photo-complete.jpg").absoluteFile
        val state = PendingPhotoCaptureState(SavedStateHandle())
        state.remember(target)

        state.clear(target)

        assertThat(state.pending()).isNull()
    }
}
