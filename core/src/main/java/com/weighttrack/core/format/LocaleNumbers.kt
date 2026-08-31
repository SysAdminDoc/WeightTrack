package com.weighttrack.core.format

import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

/**
 * Reading a number somebody typed, in the way they type numbers.
 *
 * `toDoubleOrNull` understands exactly one convention: ASCII digits with a full stop. Most of
 * Europe writes 82,4 for a weight. France groups with a space that is not a space. Arabic and
 * Persian keyboards produce digits that are not 0 to 9 at all. Every one of those typed a
 * perfectly ordinary weight into this app and got nothing: a save button that stayed grey with
 * no explanation, which reads as the app being broken rather than as the number being wrong.
 *
 * One rule, applied to everybody: the last separator is the decimal one and anything before it
 * groups. That is true of every convention in use, and it does not need to know what the phone's
 * language is, which matters because a phone set to English with a European keyboard is an
 * ordinary arrangement. The reader's own conventions are tried only for something that rule
 * cannot read at all.
 */
object LocaleNumbers {

    /**
     * A decimal number, or null when the text is not one.
     *
     * Null for anything with something left over: "82.4kg" is not a number, and quietly reading
     * it as 82.4 would let a typo through as a weight.
     */
    fun decimal(text: String, locale: Locale = Locale.getDefault()): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val ascii = toAsciiDigits(trimmed)

        return plainReading(ascii) ?: localeReading(ascii, locale)
    }

    /** A whole number, in the same terms. */
    fun integer(text: String, locale: Locale = Locale.getDefault()): Int? =
        decimal(text, locale)?.takeIf { it == Math.floor(it) && !it.isInfinite() }?.toInt()

    /**
     * The reader's own conventions, refusing anything the parser did not consume.
     *
     * A last resort. Java is lenient about grouping, so it reads "82.4" on a German phone as
     * eight hundred and twenty-four, which is a weight this app would otherwise have accepted.
     * A partial parse is refused for the same reason.
     */
    private fun localeReading(text: String, locale: Locale): Double? {
        val format = NumberFormat.getInstance(locale)
        val position = ParsePosition(0)
        val parsed = format.parse(text, position) ?: return null
        if (position.index != text.length) return null
        return parsed.toDouble()
    }

    /**
     * Whatever anybody plausibly meant.
     *
     * The last separator in the text is the decimal one and everything before it groups, which
     * is true of every convention in use: 1.234,5 and 1,234.5 both come out as 1234.5. A single
     * separator with three digits after it is a grouping mark rather than a decimal point,
     * because nobody writes a weight to three places and 1,234 is a thousand.
     *
     * The two spaces that are not spaces are dropped, because France groups with one of them and
     * they mean nothing else. An ordinary space is refused: "8 2" is two numbers or a slip, and
     * reading it as eighty-two is the sort of guess that files a typo as a weight.
     */
    private fun plainReading(text: String): Double? {
        val cleaned = text.filterNot { it == NARROW_SPACE || it == NBSP }
        if (cleaned.isEmpty()) return null
        if (cleaned.any { !it.isDigit() && it !in SEPARATORS && it != '-' && it != '+' }) return null

        val lastSeparator = cleaned.indexOfLast { it in SEPARATORS }
        if (lastSeparator < 0) return cleaned.toDoubleOrNull()

        val after = cleaned.length - lastSeparator - 1
        val onlyOne = cleaned.count { it in SEPARATORS } == 1
        // "1,234" is a thousand, not one and a bit. Three digits after a lone separator is what
        // grouping looks like everywhere it is used.
        if (onlyOne && after == 3) return cleaned.filterNot { it in SEPARATORS }.toDoubleOrNull()

        val whole = cleaned.take(lastSeparator).filterNot { it in SEPARATORS }
        val fraction = cleaned.drop(lastSeparator + 1)
        if (fraction.any { !it.isDigit() }) return null
        return "$whole.$fraction".toDoubleOrNull()
    }

    /**
     * Arabic-Indic, Devanagari and the rest, as the digits everything else here expects.
     *
     * A keyboard set to Arabic produces ٨٢٫٤ for 82.4. Every parser in the app read that as
     * nothing at all.
     */
    private fun toAsciiDigits(text: String): String = buildString {
        text.forEach { character ->
            val digit = Character.digit(character, 10)
            when {
                digit in 0..9 && !character.isAsciiDigit() -> append('0' + digit)
                character == ARABIC_DECIMAL || character == ARABIC_THOUSANDS -> {
                    append(if (character == ARABIC_DECIMAL) '.' else ',')
                }
                else -> append(character)
            }
        }
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private val SEPARATORS = setOf('.', ',', '٫', '’')
    private const val NBSP = ' '
    private const val NARROW_SPACE = ' '
    private const val ARABIC_DECIMAL = '٫'
    private const val ARABIC_THOUSANDS = '٬'
}
