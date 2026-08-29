package com.weighttrack.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Every formatted string gets exactly the arguments it asks for.
 *
 * A resource that says `%1$s` and a call that passes nothing is not a compile error and not a
 * wrong word on screen: it is an `IllegalFormatException` the moment that screen is drawn. A
 * hundred and forty strings moved into resources in one pass, and this is the mistake that pass
 * could quietly have made in any one of them.
 */
class StringFormatArgumentsTest {

    private val strings = File("src/main/res/values/strings.xml")
    private val sources = File("src/main/java/com/weighttrack")

    /** A printf placeholder, positional or not. */
    private val placeholder = Regex("""%(\d+)\$[-+ 0,(#]*\d*(?:\.\d+)?[a-zA-Z]|%[-+ 0,(#]*\d*(?:\.\d+)?[a-zA-Z]""")

    /** How many arguments each resource expects, by name. */
    private fun expectations(): Map<String, Int> {
        val text = strings.readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { match ->
                val body = match.groupValues[2].replace("%%", "")
                val indices = placeholder.findAll(body).map { it.groupValues[1] }.toList()
                match.groupValues[1] to when {
                    indices.isEmpty() -> 0
                    // An unindexed placeholder counts as one argument in order.
                    indices.any { it.isEmpty() } -> indices.size
                    else -> indices.mapNotNull(String::toIntOrNull).maxOrNull() ?: 0
                }
            }
    }

    /**
     * The arguments a call passes, counted by walking to the closing bracket.
     *
     * Commas inside a nested call, a lambda or a string do not separate arguments, which is why
     * this counts depth rather than splitting on commas.
     */
    private fun argumentsAfter(source: String, from: Int): Int {
        var i = from
        var depth = 0
        var commas = 0
        // Kotlin allows a comma after the last argument, so a comma is not by itself proof that
        // another argument follows it.
        var sawSinceComma = false
        while (i < source.length) {
            when (val c = source[i]) {
                '(', '[', '{' -> {
                    // A nested call or lambda is itself an argument, so opening one counts as
                    // having seen something since the last comma.
                    if (depth >= 1) sawSinceComma = true
                    depth++
                    i++
                }
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return if (sawSinceComma) commas + 1 else commas
                    i++
                }
                '"' -> {
                    sawSinceComma = true
                    i++
                    while (i < source.length && source[i] != '"') {
                        i += if (source[i] == '\\') 2 else 1
                    }
                    i++
                }
                ',' -> {
                    if (depth == 1) {
                        commas++
                        sawSinceComma = false
                    }
                    i++
                }
                else -> {
                    if (depth == 1 && !c.isWhitespace()) sawSinceComma = true
                    i++
                }
            }
        }
        return -1
    }

    /**
     * How many format arguments a call really supplies, past the resource id.
     *
     * Most callers pass them one by one. `WebDavSyncTarget` hands over an array instead, because
     * what it holds is a function type rather than a vararg function, and counting that array as
     * a single argument would report four calls as wrong that are not.
     */
    private fun passedTo(source: String, open: Int): Int {
        val counted = argumentsAfter(source, open)
        if (counted < 0) return -1
        val close = closingBracket(source, open)
        if (close < 0) return -1
        val body = source.substring(open + 1, close)
        val rest = body.substringAfter(',', missingDelimiterValue = "").trim()
        return when {
            rest.startsWith("emptyArray(") -> 0
            rest.startsWith("arrayOf(") -> argumentsAfter(rest, rest.indexOf('('))
            else -> counted - 1
        }
    }

    private fun closingBracket(source: String, open: Int): Int {
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    @Test
    fun `the resources parse and there are plenty of them`() {
        val expected = expectations()

        assertThat(expected).isNotEmpty()
        assertThat(expected.size).isGreaterThan(400)
        assertThat(expected).containsEntry("settings_at_time", 2)
        assertThat(expected).containsEntry("widget_tap_to_log", 0)
    }

    @Test
    fun `a positional resource numbers its placeholders from one with no gaps`() {
        val text = strings.readText()
        val broken = mutableListOf<String>()
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .forEach { match ->
                val body = match.groupValues[2].replace("%%", "")
                val indices = placeholder.findAll(body)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .toSet()
                if (indices.isEmpty()) return@forEach
                if (indices != (1..indices.max()).toSet()) broken += match.groupValues[1]
            }

        assertThat(broken).isEmpty()
    }

    @Test
    fun `every call passes the arguments its string asks for`() {
        val expected = expectations()
        // Any function whose first argument is a string resource, not a list of the names in use
        // today. The widgets call a helper of their own, and naming the two obvious functions left
        // both of them unwatched.
        val call = Regex("""(\w+)\(\s*(?:com\.weighttrack\.)?R\.string\.(\w+)""")
        val indexed = Regex("""strings\[\s*(?:com\.weighttrack\.)?R\.string\.(\w+)""")
        val wrong = mutableListOf<String>()

        for (file in sources.walkTopDown().filter { it.extension == "kt" }) {
            val source = file.readText()
            for (match in call.findAll(source)) {
                val name = match.groupValues[2]
                val wants = expected[name] ?: continue
                // The opening bracket of the call itself.
                val open = source.indexOf('(', match.range.first)
                val passed = passedTo(source, open)
                if (passed >= 0 && passed != wants) {
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    wrong += "${file.name}:$line $name wants $wants, given $passed"
                }
            }
            for (match in indexed.findAll(source)) {
                val name = match.groupValues[1]
                val wants = expected[name] ?: continue
                val open = source.indexOf('[', match.range.first)
                val passed = argumentsAfter(source, open) - 1
                if (passed >= 0 && passed != wants) {
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    wrong += "${file.name}:$line $name wants $wants, given $passed"
                }
            }
        }

        assertThat(wrong).isEmpty()
    }

    @Test
    fun `the argument counter can count`() {
        // Without this, a counter that always answered zero would make the test above pass on
        // everything.
        assertThat(argumentsAfter("f(a, b, c)", 1)).isEqualTo(3)
        assertThat(argumentsAfter("f(a)", 1)).isEqualTo(1)
        assertThat(argumentsAfter("f()", 1)).isEqualTo(0)
        // Commas that do not separate arguments.
        assertThat(argumentsAfter("""f(g(a, b), "x, y", { p, q -> p })""", 1)).isEqualTo(3)
        // A comma after the last argument is legal Kotlin and counts for nothing.
        assertThat(argumentsAfter("f(a, b,)", 1)).isEqualTo(2)
        assertThat(argumentsAfter("f(\n    a,\n    b,\n)", 1)).isEqualTo(2)
    }

    @Test
    fun `arguments handed over as an array are counted as arguments`() {
        // Without this the four WebDAV calls, which pass an array because they hold a function
        // type rather than a vararg function, all read as wrong.
        assertThat(passedTo("say(R.string.x, emptyArray())", 3)).isEqualTo(0)
        assertThat(passedTo("say(R.string.x, arrayOf(code))", 3)).isEqualTo(1)
        assertThat(passedTo("say(R.string.x, arrayOf(a, b))", 3)).isEqualTo(2)
        assertThat(passedTo("stringResource(R.string.x, a, b)", 13)).isEqualTo(2)
        assertThat(passedTo("stringResource(R.string.x)", 13)).isEqualTo(0)
    }
}
