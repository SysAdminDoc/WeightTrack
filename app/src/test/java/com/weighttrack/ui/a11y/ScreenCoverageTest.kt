package com.weighttrack.ui.a11y

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Every screen in the app is checked.
 *
 * A hand-kept list of screens stops covering the app the moment somebody adds one, and the
 * failure is invisible: the check still passes, on fewer screens than before. So the list is
 * held against what is actually in the source tree, and a screen that is deliberately left out
 * has to say why here.
 */
class ScreenCoverageTest {

    private val source = File("src/main/java/com/weighttrack/ui")

    /** Top-level screen composables, found by looking rather than by remembering. */
    private fun screensInTheApp(): Set<String> = source.walkTopDown()
        .filter { it.isFile && it.name.endsWith("Screen.kt") }
        .flatMap { file -> TOP_LEVEL.findAll(file.readText()).map { it.groupValues[1] } }
        .toSet()

    @Test
    fun `every screen is covered`() {
        val covered = ScreenFixtures.all.map { it.screen }.toSet()

        val missing = screensInTheApp() - covered

        assertThat(missing).isEmpty()
    }

    @Test
    fun `the fixtures name screens that exist`() {
        val invented = ScreenFixtures.all.map { it.screen }.toSet() - screensInTheApp()

        assertThat(invented).isEmpty()
    }

    @Test
    fun `the states worth checking are all covered somewhere`() {
        // Not per screen: an empty Lock screen is not a thing. But if none of these appears
        // anywhere then the fixtures have drifted back to being happy paths only, which is the
        // shape the screenshots were already in.
        val states = ScreenFixtures.all.map { it.state }.toSet()

        assertThat(states).containsAtLeast("empty", "loading", "permission refused", "error", "undo")
    }

    private companion object {
        val TOP_LEVEL = Regex(
            "^(?:(?:public|internal|private)\\s+)?fun\\s+([A-Za-z][A-Za-z0-9]*Screen)\\s*\\(",
            RegexOption.MULTILINE,
        )

    }
}
