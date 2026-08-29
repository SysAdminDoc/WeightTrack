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
                // The call's own brackets, found by matching them, so a description belonging to
                // the composable after it cannot be read as this one's. A flat window let a
                // labelled Spacer underneath an unlabelled Canvas pass the check.
                val body = argumentsOf(source, source.indexOf('(', match.range.first))
                if ("contentDescription" in body) return@forEach
                val line = source.take(match.range.first).count { it == '\n' } + 1
                undescribed += "${file.name}:$line"
            }
        }

        assertThat(undescribed).isEmpty()
    }

    /**
     * The text between a call's brackets, nesting included and nothing past them.
     *
     * A `Canvas(...)` with a trailing draw block carries its modifier inside the round brackets,
     * so this is exactly the part that could describe it.
     */
    private fun argumentsOf(source: String, open: Int): String {
        if (open < 0) return ""
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i)
                }
            }
        }
        return ""
    }

    @Test
    fun `the argument reader stops at the call it was given`() {
        // Without this, a window running past the closing bracket accepts a description that
        // belongs to the next composable and passes a canvas that has none.
        val source = """Canvas(Modifier.height(4.dp)) { }
            Spacer(Modifier.semantics { contentDescription = "x" })"""

        assertThat(argumentsOf(source, source.indexOf('('))).doesNotContain("contentDescription")
    }
}
