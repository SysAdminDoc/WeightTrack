package com.weighttrack.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Every picture the app draws says what it shows.
 *
 * A `Canvas` is one node with nothing in it as far as a screen reader is concerned. The trend
 * chart, the week bars, the step bars and the fasting ring were all silent, which for somebody
 * using TalkBack means the main thing on the screen simply is not there.
 *
 * A drawing that adds nothing to the words beside it can say so with an empty description, which
 * makes a reader skip it. What it cannot do is stay unlabelled.
 */
class CanvasIsDescribedTest {

    private val sources = listOf(
        File("src/main/java/com/weighttrack"),
        File("../wear/src/main/java/com/weighttrack"),
    )

    private fun kotlinFiles() =
        sources.asSequence().flatMap { it.walkTopDown() }.filter { it.extension == "kt" }

    @Test
    fun `the source tree is where this test thinks it is`() {
        sources.forEach { assertThat(it.isDirectory).isTrue() }
        assertThat(kotlinFiles().count()).isGreaterThan(40)
    }

    @Test
    fun `every canvas carries a description`() {
        val undescribed = mutableListOf<String>()
        for (file in kotlinFiles()) {
            val source = file.readText()
            // The Compose one. `android.graphics.Canvas`, which is how the share card is drawn,
            // is a bitmap and never on screen as a node.
            if ("import androidx.compose.foundation.Canvas" !in source) continue
            Regex("""(?<![\w.])Canvas\(""").findAll(source).forEach { match ->
                // Everything from the call up to the end of its modifier chain. Cutting at the
                // first brace lands inside `.semantics { ... }`, which is where the description
                // being looked for lives.
                val body = source.substring(
                    match.range.first,
                    minOf(source.length, match.range.first + MODIFIER_WINDOW),
                )
                if ("contentDescription" in body) return@forEach
                val line = source.take(match.range.first).count { it == '\n' } + 1
                undescribed += "${file.name}:$line"
            }
        }

        assertThat(undescribed).isEmpty()
    }

    private companion object {
        /** Long enough to hold a wrapped modifier chain, short enough not to reach the next call. */
        const val MODIFIER_WINDOW = 400
    }
}
