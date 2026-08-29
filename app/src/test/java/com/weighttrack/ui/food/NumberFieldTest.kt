package com.weighttrack.ui.food

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NumberFieldTest {

    @Test
    fun `only digits and one separator survive`() {
        assertThat(keepNumeric("379")).isEqualTo("379")
        assertThat(keepNumeric("82.5")).isEqualTo("82.5")
        // A comma is a decimal point in most of the world.
        assertThat(keepNumeric("82,5")).isEqualTo("82.5")
        assertThat(keepNumeric("82.5.7")).isEqualTo("82.57")
    }

    @Test
    fun `a keyboard that sends something else does not disable the save button`() {
        // Found on an emulator: typing 379 into a decimal field produced "379-", which parses
        // as nothing, so the button greyed out with nothing on screen explaining why.
        assertThat(keepNumeric("379-")).isEqualTo("379")
        assertThat(keepNumeric("-379")).isEqualTo("379")
        assertThat(keepNumeric("3 7 9")).isEqualTo("379")
        assertThat(keepNumeric("379 kcal")).isEqualTo("379")
    }

    @Test
    fun `a separator with no digits in front of it is not a number starting`() {
        assertThat(keepNumeric(".")).isEmpty()
        assertThat(keepNumeric(".5")).isEqualTo("5")
        assertThat(keepNumeric("")).isEmpty()
    }

    @Test
    fun `what survives is something the app can actually read as a number`() {
        listOf("379-", "8 2 . 5", "abc12.3xyz", "1,000").forEach { typed ->
            val kept = keepNumeric(typed)
            assertThat(kept.toDoubleOrNull()).isNotNull()
        }
    }
}
