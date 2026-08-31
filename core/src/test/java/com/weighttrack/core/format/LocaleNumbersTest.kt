package com.weighttrack.core.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * Reading a number somebody typed, in the way they type numbers.
 *
 * `toDoubleOrNull` understands one convention: ASCII digits with a full stop. Most of Europe
 * writes 82,4 for a weight; France groups with a space that is not a space; an Arabic keyboard
 * produces digits that are not 0 to 9 at all. Every one of those typed an ordinary weight into
 * this app and got nothing back: a save button that stayed grey with no explanation, which reads
 * as the app being broken rather than as the number being wrong.
 */
class LocaleNumbersTest {

    private val german = Locale.GERMANY
    private val french = Locale.FRANCE
    private val american = Locale.US

    @Test
    fun `a plain number reads the same everywhere`() {
        listOf(american, german, french).forEach { locale ->
            assertThat(LocaleNumbers.decimal("82", locale)).isEqualTo(82.0)
        }
    }

    @Test
    fun `a comma is a decimal point where people write it that way`() {
        assertThat(LocaleNumbers.decimal("82,4", german)).isEqualTo(82.4)
        assertThat(LocaleNumbers.decimal("82,4", french)).isEqualTo(82.4)
    }

    @Test
    fun `a comma is a decimal point even on a phone set to English`() {
        // An English phone with a European keyboard is an ordinary arrangement, and refusing it
        // is refusing a perfectly good weight.
        assertThat(LocaleNumbers.decimal("82,4", american)).isEqualTo(82.4)
    }

    @Test
    fun `a full stop is a decimal point even on a phone set to German`() {
        assertThat(LocaleNumbers.decimal("82.4", german)).isEqualTo(82.4)
    }

    @Test
    fun `a grouped thousand is a thousand, not one and a bit`() {
        // Both conventions, and the bare form an English phone would produce.
        assertThat(LocaleNumbers.decimal("1,234", american)).isEqualTo(1234.0)
        assertThat(LocaleNumbers.decimal("1.234", german)).isEqualTo(1234.0)
    }

    @Test
    fun `both separators together read as one number`() {
        assertThat(LocaleNumbers.decimal("1,234.5", american)).isEqualTo(1234.5)
        assertThat(LocaleNumbers.decimal("1.234,5", german)).isEqualTo(1234.5)
    }

    @Test
    fun `the space France groups with is not a space and is still read`() {
        // A narrow no-break space, which is what a French keyboard and French formatting both
        // produce. Nothing that splits on ' ' sees it.
        assertThat(LocaleNumbers.decimal("1 234,5", french)).isEqualTo(1234.5)
        assertThat(LocaleNumbers.decimal("1 234,5", french)).isEqualTo(1234.5)
    }

    @Test
    fun `digits that are not zero to nine are still digits`() {
        // Arabic-Indic, with the Arabic decimal separator. A keyboard set to Arabic produces
        // this for 82.4, and every parser in the app read it as nothing at all.
        assertThat(LocaleNumbers.decimal("٨٢٫٤")).isEqualTo(82.4)
        // Devanagari, which uses the ordinary full stop.
        assertThat(LocaleNumbers.decimal("८२.४")).isEqualTo(82.4)
    }

    @Test
    fun `something that is not a number is not a number`() {
        assertThat(LocaleNumbers.decimal("")).isNull()
        assertThat(LocaleNumbers.decimal("   ")).isNull()
        assertThat(LocaleNumbers.decimal("heavy")).isNull()
        // Anything left over is a refusal. Reading "82.4kg" as 82.4 would let a typo through as
        // a weight, and the one thing worse than a refused number is a wrong accepted one.
        assertThat(LocaleNumbers.decimal("82.4kg")).isNull()
        assertThat(LocaleNumbers.decimal("8 2")).isNull()
    }

    @Test
    fun `a negative reads as a negative`() {
        assertThat(LocaleNumbers.decimal("-2,5", german)).isEqualTo(-2.5)
    }

    @Test
    fun `a whole number is a whole number and a fraction is not`() {
        assertThat(LocaleNumbers.integer("1988")).isEqualTo(1988)
        assertThat(LocaleNumbers.integer("1.988", german)).isEqualTo(1988)
        assertThat(LocaleNumbers.integer("82,4", german)).isNull()
    }

    @Test
    fun `what the app writes is what it can read back`() {
        // The round trip that matters: a value shown to somebody, edited, and read again. If
        // formatting and parsing disagree, editing a field breaks it.
        listOf(american, german, french).forEach { locale ->
            val shown = java.text.NumberFormat.getInstance(locale).apply {
                maximumFractionDigits = 2
            }.format(82.45)

            assertThat(LocaleNumbers.decimal(shown, locale)).isEqualTo(82.45)
        }
    }
}
