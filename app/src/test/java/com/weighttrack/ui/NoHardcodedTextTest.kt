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
    private val sources = listOf(
        File("src/main/java/com/weighttrack"),
        // The watch too. It was left out of the translation pass entirely, while the README said
        // this test reads every Kotlin file in the app.
        File("../wear/src/main/java/com/weighttrack"),
        // And the pure module. It has no resources by design, which is exactly why English hid
        // there: the four meal names sat on an enum and were drawn straight onto the diary.
        File("../core/src/main/java/com/weighttrack/core"),
    )

    /**
     * What sits immediately before a literal when somebody is about to be shown it.
     *
     * Builder calls are here as well as named arguments. The biometric prompt is built rather
     * than composed, so its title and subtitle were set through a builder method and this looked
     * straight past them: the first screen anybody who reads another language meets was the last
     * place in the app still speaking English.
     */
    private val sink = Regex(
        """(?:(?<![\w.])(?:Text|SectionHeading|LabelledValue|error|showSnackbar)\(""" +
            """|\.set(?:Title|Subtitle|Description|ContentTitle|ContentText|NegativeButtonText)\(""" +
            """|(?:text|label|actionLabel|placeholder|contentDescription|title|message|subtitle)""" +
            """\s*=|EXTRA_SUBJECT,|createChooser\([^,]+,|problems \+=)""" +
            """\s*(?:if \([^()]*\)\s*)?$""",
    )

    /**
     * The other half of a choice whose first half is a resource.
     *
     * `if (male) stringResource(R.string.male) else "Female"` reads as finished work and is half
     * done. A dozen of these survived a translation pass, because the sink sits in front of the
     * branch that was converted and there is nothing in front of the one that was not.
     */
    private val alternativeToResource = Regex("""R\.string\.\w+[^"]{0,200}?(?:else|\?:)\s*$""")

    /**
     * Literals that look like text and are not.
     *
     * Animation labels are read by the tooling. A literal made of nothing but an interpolation is
     * a unit suffix being glued on and has no words of its own to translate.
     */
    private val animationCall = Regex("""animate\w*AsState\(|updateTransition\(|rememberInfiniteTransition\(""")
    private val interpolation = Regex("""\$\{(?:[^{}]|\{[^{}]*\})*\}|\$[A-Za-z_][A-Za-z0-9_.]*""")

    /**
     * Every composable the app declares for itself, by name.
     *
     * The named sinks above are the ones the framework provides. They are not the whole story: a
     * screen can put words on the glass through a helper of its own, and the chart legend did
     * exactly that. `ChartLegend("Raw", ...)` was English in every locale for as long as this
     * guard has existed, because the guard was watching for `Text(` and the helper called it one
     * layer down.
     *
     * So the declarations are read out of the source and every one of them becomes a sink too.
     * A helper added tomorrow is covered the day it is written rather than the day somebody
     * remembers to add it to a list.
     */
    private val ownComposables: Set<String> by lazy {
        val declaration = Regex("""^\s*(?:(?:private|internal|public|inline|expect|actual)\s+)*fun\s+([A-Z]\w*)\s*[(<]""")
        val names = mutableSetOf<String>()
        for (file in kotlinFiles()) {
            var annotated = false
            for (line in file.readLines()) {
                val match = declaration.find(line)
                if (match != null) {
                    if (annotated) names += match.groupValues[1]
                    annotated = false
                    continue
                }
                // Composable, then any number of other annotations and modifiers, then the
                // declaration. A blank line or a statement in between means it was not this one.
                if ("@Composable" in line) annotated = true
                else if (line.isNotBlank() && !line.trimStart().startsWith("@") &&
                    !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
                ) {
                    annotated = false
                }
            }
        }
        names
    }

    /**
     * A literal handed to one of the app's own composables, in any position.
     *
     * Positional as well as named, because a helper written for one screen takes its label first
     * and names nothing. Bounded by parentheses and quotes so it cannot reach out of the
     * argument list it is looking at.
     */
    private val ownComposableSink: Regex by lazy {
        Regex(
            """(?<![\w.])(?:${ownComposables.joinToString("|")})\(\s*(?:[^()"]*[,=]\s*)?$""",
        )
    }

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

    private fun kotlinFiles() =
        sources.asSequence().flatMap { it.walkTopDown() }.filter { it.extension == "kt" }

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
        sources.forEach { assertThat(it.isDirectory).isTrue() }
        assertThat(kotlinFiles().count()).isGreaterThan(40)
        // The watch is a separate module and easy to leave out, which is exactly what happened.
        assertThat(kotlinFiles().count { it.path.replace('\\', '/').contains("/wear/") })
            .isGreaterThan(3)
        assertThat(kotlinFiles().count { it.path.replace('\\', '/').contains("/core/") })
            .isGreaterThan(10)
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
    fun `the app's own composables are found and are treated as sinks`() {
        // The two halves this rests on. If the declaration scanner ever stops finding them, the
        // sink regex is built from an empty list and the whole widening silently watches nothing.
        assertThat(ownComposables).containsAtLeast("ChartLegend", "NumberlessField", "ToggleRow")
        assertThat(ownComposables.size).isGreaterThan(80)

        // The chart legend, exactly as it was written: three English words in every locale,
        // invisible because the guard was watching for Text( and the helper called it one layer
        // down. Each shape a helper is called in has to be caught, not just the first argument.
        listOf(
            """ChartLegend("Raw", MaterialTheme.colorScheme.onSurfaceVariant, dot = true)""",
            """NumberlessField(name, { name = it }, "Name")""",
            """ToggleRow(label = "Sync in the background", checked = on)""",
        ).forEach { call ->
            val literal = kotlinStringLiterals(call).first { it.value.first().isUpperCase() }
            assertThat(ownComposableSink.containsMatchIn(literal.before)).isTrue()
        }
    }

    @Test
    fun `no screen shows text that cannot be translated`() {
        val offenders = mutableListOf<String>()
        for (file in kotlinFiles()) {
            for (literal in kotlinStringLiterals(file.readText())) {
                // Matched against the run-up rather than the literal's own line: a long call is
                // wrapped by the formatter, and a literal on a line of its own then has nothing
                // in front of it. Any one-word argument was invisible.
                val shown = sink.containsMatchIn(literal.before) ||
                    alternativeToResource.containsMatchIn(literal.before) ||
                    ownComposableSink.containsMatchIn(literal.before)
                if (!shown) continue
                // One letter is enough here. Something handed straight to a snackbar is text
                // whatever its length, and requiring two let "x" through.
                if (!hasWords(literal.value, atLeast = 1)) continue
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
