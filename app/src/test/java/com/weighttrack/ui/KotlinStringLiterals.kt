package com.weighttrack.ui

/** One string literal found in a source file. */
data class SourceLiteral(
    val value: String,
    val at: Int,
    val raw: Boolean,
    /** What sits before it on its own line, which is how a caller tells what it was handed to. */
    val prefix: String,
    /** A little of the source before it, for callers that span more than one line. */
    val before: String,
    val line: Int,
)

/**
 * Finds the string literals in a Kotlin file, and only the string literals.
 *
 * Written because matching them with a regular expression does not work. A pattern that looks for
 * a quote, some text and another quote happily pairs the closing quote of one literal with the
 * opening quote of the next, so `LabelledValue("Build", if (foss) "F-Droid" else "Play")` reports
 * a literal reading `, if (foss) `. Half the findings were that, which is the fastest way to make
 * a guard nobody trusts.
 *
 * This walks the file instead, tracking whether it is inside a line comment, a block comment, a
 * raw string or an ordinary one.
 */
fun kotlinStringLiterals(source: String): List<SourceLiteral> {
    val found = mutableListOf<SourceLiteral>()
    var i = 0
    var line = 1
    var lineStart = 0

    // Taken as the literal opens. A raw string moves both while it is being read, and taking
    // them afterwards asked for the text between a later line's start and an earlier offset.
    fun record(value: String, start: Int, startLine: Int, startOfLine: Int, raw: Boolean) {
        found += SourceLiteral(
            value = value,
            at = start,
            raw = raw,
            prefix = source.substring(startOfLine, start),
            before = source.substring(maxOf(0, start - 300), start),
            line = startLine,
        )
    }

    while (i < source.length) {
        val c = source[i]
        when {
            c == '\n' -> {
                line++
                i++
                lineStart = i
            }

            source.startsWith("//", i) -> {
                while (i < source.length && source[i] != '\n') i++
            }

            source.startsWith("/*", i) -> {
                val end = source.indexOf("*/", i + 2)
                val stop = if (end == -1) source.length else end + 2
                for (k in i until stop) if (source[k] == '\n') { line++; lineStart = k + 1 }
                i = stop
            }

            source.startsWith("\"\"\"", i) -> {
                val start = i
                val startLine = line
                val startOfLine = lineStart
                i += 3
                val builder = StringBuilder()
                while (i < source.length && !source.startsWith("\"\"\"", i)) {
                    if (source[i] == '\n') { line++; lineStart = i + 1 }
                    builder.append(source[i])
                    i++
                }
                i = minOf(source.length, i + 3)
                record(builder.toString(), start, startLine, startOfLine, raw = true)
            }

            c == '"' -> {
                val start = i
                val startLine = line
                val startOfLine = lineStart
                i++
                val builder = StringBuilder()
                while (i < source.length && source[i] != '"') {
                    if (source[i] == '\\' && i + 1 < source.length) {
                        builder.append(source[i]).append(source[i + 1])
                        i += 2
                        continue
                    }
                    // An interpolation may hold code, and that code may hold strings of its own,
                    // as in "${if (stone) "st" else "kg"}". Read as text it would end the literal
                    // at the first inner quote and report the code between as if it were words.
                    if (source.startsWith("\${", i)) {
                        val end = endOfInterpolation(source, i)
                        builder.append(source, i, end)
                        i = end
                        continue
                    }
                    // A newline ends an unterminated literal rather than running to the next one.
                    if (source[i] == '\n') break
                    builder.append(source[i])
                    i++
                }
                if (i < source.length && source[i] == '"') i++
                record(builder.toString(), start, startLine, startOfLine, raw = false)
            }

            c == '\'' -> {
                // A character literal, which can hold a quote and would otherwise start one.
                i++
                while (i < source.length && source[i] != '\'') {
                    i += if (source[i] == '\\') 2 else 1
                }
                i++
            }

            else -> i++
        }
    }
    return found
}

/**
 * Where the interpolation starting at [from] ends.
 *
 * Counts braces, and steps over any string inside so that a brace or a quote within one cannot
 * close the interpolation early.
 */
private fun endOfInterpolation(source: String, from: Int): Int {
    var i = from + 2
    var depth = 1
    while (i < source.length && depth > 0) {
        when {
            source[i] == '{' -> { depth++; i++ }
            source[i] == '}' -> { depth--; i++ }
            source[i] == '"' -> {
                i++
                while (i < source.length && source[i] != '"') {
                    i += if (source[i] == '\\') 2 else 1
                }
                i++
            }
            else -> i++
        }
    }
    return i
}
