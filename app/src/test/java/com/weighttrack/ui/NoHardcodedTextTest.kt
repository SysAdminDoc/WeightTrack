package com.weighttrack.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Keeps the screens translatable.
 *
 * Android's own lint has a check for this and it only looks at XML layouts, which this app does
 * not have. Without something watching, the next screen somebody writes goes back to English
 * literals and the translation quietly stops covering the app. This is that something.
 *
 * It reads the source rather than the compiled code, which is unusual and is the only way to see
 * the thing it is checking for.
 */
class NoHardcodedTextTest {

    private val uiSources = File("src/main/java/com/weighttrack/ui")

    /** Where a literal is being shown to somebody. */
    private val shown = Regex(
        """((?<![\w.])Text\(\s*|(?<![\w.])SectionHeading\(\s*|\btext = |\blabel = """ +
            """|\bplaceholder = |\bcontentDescription = |\btitle = |\bmessage = |\bsubtitle = )"""" +
            """((?:[^"\\]|\\.)*)"""",
    )

    /**
     * Literals that look like text and are not.
     *
     * Animation labels are read by the tooling. A literal made of nothing but an interpolation is
     * a unit suffix being glued on and has no words of its own to translate.
     */
    private val animationCall = Regex("""animate\w*AsState\(|updateTransition\(|rememberInfiniteTransition\(""")
    private val interpolation = Regex("""\$\{(?:[^{}]|\{[^{}]*\})*\}|\$[A-Za-z_][A-Za-z0-9_.]*""")

    @Test
    fun `the source tree is where this test thinks it is`() {
        // If the module layout ever moves, this test would otherwise pass by finding nothing.
        assertThat(uiSources.isDirectory).isTrue()
        assertThat(uiSources.walkTopDown().count { it.extension == "kt" }).isGreaterThan(20)
    }

    @Test
    fun `no screen shows text that cannot be translated`() {
        val offenders = mutableListOf<String>()
        for (file in uiSources.walkTopDown().filter { it.extension == "kt" }) {
            val source = file.readText()
            for (match in shown.findAll(source)) {
                val literal = match.groupValues[2]
                if (literal.length < 2) continue
                if (!literal.any { it.isLetter() }) continue
                if (!interpolation.replace(literal, " ").any { it.isLetter() }) continue
                // An interpolation can hold a string of its own, as in
                // " ${if (stones) "lb" else label}". The pattern stops at that inner quote, so
                // what is in hand is half a line rather than a sentence, and the words inside are
                // a unit symbol either way.
                if (literal.count { it == '{' } != literal.count { it == '}' }) continue

                val lineStart = source.lastIndexOf('\n', match.range.first) + 1
                val before = source.substring(lineStart, match.range.first)
                if ("//" in before || before.trimStart().startsWith("*")) continue
                val window = source.substring(maxOf(0, match.range.first - 300), match.range.first)
                if (animationCall.containsMatchIn(window)) continue

                val line = source.take(match.range.first).count { it == '\n' } + 1
                offenders += "${file.name}:$line \"$literal\""
            }
        }

        assertThat(offenders).isEmpty()
    }

    /** A literal handed to the thing that shows a message. */
    private val messaged = Regex("(_?message\\.value = )\"((?:[^\"\\\\]|\\\\.)*)\"")

    @Test
    fun `no message shown to somebody is stuck in English`() {
        // These reach a snackbar, which is text somebody reads. They cannot use stringResource,
        // which is why they were the easy ones to forget: everything else on the screen gets
        // translated and these quietly do not.
        val offenders = mutableListOf<String>()
        for (file in uiSources.walkTopDown().filter { it.extension == "kt" }) {
            val source = file.readText()
            for (match in messaged.findAll(source)) {
                val literal = match.groupValues[2]
                if (literal.length < 2) continue
                if (!literal.any { it.isLetter() }) continue
                val line = source.take(match.range.first).count { it == '\n' } + 1
                offenders += "${file.name}:$line \"$literal\""
            }
        }

        assertThat(offenders).isEmpty()
    }
}
