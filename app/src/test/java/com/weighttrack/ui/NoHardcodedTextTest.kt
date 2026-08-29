package com.weighttrack.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Keeps the app translatable.
 *
 * Android's own lint has a check for this and it only looks at XML layouts, which this app does
 * not have. Without something watching, the next screen somebody writes goes back to English
 * literals and the translation quietly stops covering the app. This is that something.
 *
 * It reads the source rather than the compiled code, which is unusual and is the only way to see
 * the thing it is checking for. Literals come from [kotlinStringLiterals] rather than a regular
 * expression, because a pattern pairs the end of one literal with the start of the next and
 * reports the code in between as if it were text.
 */
class NoHardcodedTextTest {

    /**
     * The whole app, not just the screens.
     *
     * It used to look at `ui/` alone, which is how the undo snackbar in the navigation host, the
     * reminder notification, the widgets and the backup importer's messages all stayed in English
     * through a translation pass that was supposed to have found everything.
     */
    private val sources = File("src/main/java/com/weighttrack")

    /** What sits immediately before a literal when somebody is about to be shown it. */
    private val sink = Regex(
        """(?:(?<![\w.])(?:Text|SectionHeading|LabelledValue|error|showSnackbar)\(""" +
            """|(?:text|label|actionLabel|placeholder|contentDescription|title|message|subtitle)""" +
            """\s*=|EXTRA_SUBJECT,|createChooser\([^,]+,|problems \+=)""" +
            """\s*(?:if \([^()]*\)\s*)?$""",
    )

    /**
     * Literals that look like text and are not.
     *
     * Animation labels are read by the tooling. A literal made of nothing but an interpolation is
     * a unit suffix being glued on and has no words of its own to translate.
     */
    private val animationCall = Regex("""animate\w*AsState\(|updateTransition\(|rememberInfiniteTransition\(""")
    private val interpolation = Regex("""\$\{(?:[^{}]|\{[^{}]*\})*\}|\$[A-Za-z_][A-Za-z0-9_.]*""")

    /**
     * The packages that put words in front of somebody.
     *
     * The sentence rule looks only here. Elsewhere the app is full of multi-word strings nobody
     * reads, Room queries and date patterns most of all, and it cannot tell those from prose.
     * The sink rule still watches the whole tree.
     */
    private fun rendersText(file: File): Boolean {
        val path = file.path.replace('\\', '/')
        return RENDERS_TEXT.any { path.contains(it) }
    }

    private fun kotlinFiles() = sources.walkTopDown().filter { it.extension == "kt" }

    /** Whether this literal is one somebody could be shown. */
    private fun isExempt(literal: SourceLiteral): Boolean {
        val before = literal.prefix
        if ("@Suppress" in before || "@Deprecated(" in before || "@RequiresApi" in before) return true
        if ("Regex(" in before || "require(" in before || "check(" in before) return true
        // A date pattern is instructions to a formatter, not words. It reads like text because
        // the letters in it stand for parts of a date.
        if ("ofPattern(" in before || "SimpleDateFormat(" in before) return true
        // A message inside error() beginning in lower case is a note to whoever is reading the
        // stack trace, not a sentence anybody is shown.
        if (before.trimEnd().endsWith("error(") && literal.value.firstOrNull()?.isLowerCase() == true) {
            return true
        }
        // An animation label can sit several lines below the call that takes it.
        return animationCall.containsMatchIn(literal.before)
    }

    /**
     * A printf placeholder. Its letters belong to the format, not to the sentence.
     */
    private val placeholder = Regex("""%(?:\d+\$)?[-+ 0,(#]*\d*(?:\.\d+)?[a-zA-Z%]""")

    /**
     * Whether there are real words here.
     *
     * Counts letters outside interpolations and placeholders, so `"%d st %.1f"` reads as the unit
     * symbol it is. The README says units stay as written: `st`, `lb` and `kg` mean the same
     * everywhere, and a translator has nothing to do with them.
     */
    private fun hasWords(value: String, atLeast: Int): Boolean =
        placeholder.replace(interpolation.replace(value, " "), " ").count { it.isLetter() } >= atLeast

    @Test
    fun `the source tree is where this test thinks it is`() {
        // If the module layout ever moves, this test would otherwise pass by finding nothing.
        assertThat(sources.isDirectory).isTrue()
        assertThat(kotlinFiles().count()).isGreaterThan(40)
        // Every package the sentence rule claims to watch has to exist, or it quietly watches
        // nothing at all.
        RENDERS_TEXT.forEach { part ->
            assertThat(kotlinFiles().count { it.path.replace('\\', '/').contains(part) })
                .isGreaterThan(0)
        }
    }

    @Test
    fun `the literal scanner does not splice one literal onto the next`() {
        // The failure this whole test used to have. Without this, weakening the scanner would
        // make the guard quietly find less and still pass.
        val literals = kotlinStringLiterals(
            """LabelledValue("Build", if (foss) "F-Droid" else "Play") // "not this"""",
        )

        assertThat(literals.map { it.value }).containsExactly("Build", "F-Droid", "Play").inOrder()
    }

    @Test
    fun `a string inside an interpolation does not end the literal`() {
        val literals = kotlinStringLiterals("""Text("You weigh ${'$'}{if (st) "12 st" else "80 kg"} now")""")

        assertThat(literals.map { it.value })
            .containsExactly("You weigh ${'$'}{if (st) \"12 st\" else \"80 kg\"} now")
    }

    @Test
    fun `no screen shows text that cannot be translated`() {
        val offenders = mutableListOf<String>()
        for (file in kotlinFiles()) {
            for (literal in kotlinStringLiterals(file.readText())) {
                if (!sink.containsMatchIn(literal.prefix)) continue
                if (!hasWords(literal.value, atLeast = 2)) continue
                if (isExempt(literal)) continue
                offenders += "${file.name}:${literal.line} \"${literal.value}\""
            }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `no sentence is written into a screen`() {
        val offenders = mutableListOf<String>()
        for (file in kotlinFiles().filter(::rendersText)) {
            for (literal in kotlinStringLiterals(file.readText())) {
                if (!hasWords(literal.value, atLeast = 4)) continue
                if (!interpolation.replace(literal.value, " ").trim().contains(' ')) continue
                if (isExempt(literal)) continue
                offenders += "${file.name}:${literal.line} \"${literal.value}\""
            }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `no message shown to somebody is stuck in English`() {
        // These reach a snackbar, which is text somebody reads. They cannot use stringResource,
        // which is why they were the easy ones to forget: everything else on the screen gets
        // translated and these quietly do not.
        val offenders = mutableListOf<String>()
        for (file in kotlinFiles()) {
            for (literal in kotlinStringLiterals(file.readText())) {
                if (!literal.prefix.trimEnd().endsWith("message.value =")) continue
                if (!hasWords(literal.value, atLeast = 2)) continue
                offenders += "${file.name}:${literal.line} \"${literal.value}\""
            }
        }

        assertThat(offenders).isEmpty()
    }

    private companion object {
        val RENDERS_TEXT = listOf("/ui/", "/widget/", "/notifications/")
    }
}
