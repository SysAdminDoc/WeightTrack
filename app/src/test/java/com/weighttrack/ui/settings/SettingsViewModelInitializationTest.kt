package com.weighttrack.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** Guards the state used by work launched from the constructor against Kotlin field-order bugs. */
class SettingsViewModelInitializationTest {

    private val source = File(
        "src/main/java/com/weighttrack/ui/settings/SettingsViewModel.kt",
    ).readText()

    @Test
    fun `automatic backup state exists before constructor work starts`() {
        val state = source.indexOf("private val _autoBackup = MutableStateFlow")
        val initialization = source.indexOf("init {")

        assertThat(state).isGreaterThan(0)
        assertThat(initialization).isGreaterThan(state)
    }
}
