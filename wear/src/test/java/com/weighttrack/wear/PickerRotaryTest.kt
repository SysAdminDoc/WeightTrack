package com.weighttrack.wear

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performRotaryScrollInput
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.WeightUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The crown, driven for real.
 *
 * The maths is covered elsewhere; what this proves is the wiring, which is the part that fails
 * silently. A rotary handler on an element that never takes focus swallows every turn and the
 * picker just sits there, and no amount of testing the numbers would show it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickerRotaryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun picker(unit: WeightUnit = WeightUnit.KG): MutableList<Int> {
        val nudges = mutableListOf<Int>()
        compose.setContent {
            PickerScreen(
                steps = WearWeightPicker.stepsFor(75_000, unit),
                unit = unit,
                onNudge = { nudges += it },
                onSave = {},
                onCancel = {},
            )
        }
        return nudges
    }

    @Test
    fun `turning the crown moves the picker`() {
        val nudges = picker()

        compose.onRoot().performRotaryScrollInput { rotateToScrollVertically(120f) }
        compose.waitForIdle()

        assertThat(nudges).isNotEmpty()
        assertThat(nudges.sum()).isGreaterThan(0)
    }

    @Test
    fun `turning it the other way moves the other way`() {
        val nudges = picker()

        compose.onRoot().performRotaryScrollInput { rotateToScrollVertically(-120f) }
        compose.waitForIdle()

        assertThat(nudges).isNotEmpty()
        assertThat(nudges.sum()).isLessThan(0)
    }

    @Test
    fun `the smallest turn is still worth one step`() {
        val nudges = picker()

        compose.onRoot().performRotaryScrollInput { rotateToScrollVertically(1f) }
        compose.waitForIdle()

        // A dead zone on a slow deliberate turn makes the control feel broken.
        assertThat(nudges).containsExactly(1)
    }
}
