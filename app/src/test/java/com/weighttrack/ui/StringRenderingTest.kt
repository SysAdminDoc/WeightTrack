package com.weighttrack.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the strings actually come out as.
 *
 * Android's resource compiler has its own escaping on top of XML's, and it is silent about both.
 * A doubled backslash becomes a backslash rather than a quote, and leading whitespace is thrown
 * away unless the whole value is quoted. Neither fails the build and neither shows up reading the
 * file, so the only way to know is to ask for the string and look at it.
 *
 * Both mistakes were shipped here in one commit: a search with no matches read
 * `Nothing matches \apple\.` and a food row read `250 kcal per 100 g· 12 g protein`.
 */
@RunWith(RobolectricTestRunner::class)
class StringRenderingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a quoted search term comes out in quotes, not backslashes`() {
        val rendered = context.getString(R.string.historyscreen_nothing_matches, "apple")

        assertThat(rendered).isEqualTo("Nothing matches \"apple\".")
        assertThat(rendered).doesNotContain("\\")
    }

    @Test
    fun `the food row separators keep the space in front of them`() {
        // These are appended to the line before, so losing the leading space runs two facts
        // together: "250 kcal per 100 g· 12 g protein".
        assertThat(context.getString(R.string.foodscreen_g_protein, "12")).startsWith(" ")
        assertThat(context.getString(R.string.foodscreen_serving_g, "30")).startsWith(" ")
    }

    @Test
    fun `a food row reads as separate facts`() {
        val row = context.getString(R.string.foodscreen_kcal_per_g, "250") +
            context.getString(R.string.foodscreen_g_protein, "12") +
            context.getString(R.string.foodscreen_serving_g, "30")

        assertThat(row).isEqualTo("250 kcal per 100 g · 12 g protein · serving 30 g")
    }

    @Test
    fun `no string keeps a stray backslash`() {
        // A backslash surviving into the rendered text is always a mis-escape: nothing the app
        // says has one in it on purpose.
        val fields = R.string::class.java.fields
        val offenders = fields.mapNotNull { field ->
            val id = runCatching { field.getInt(null) }.getOrNull() ?: return@mapNotNull null
            val value = runCatching { context.getString(id) }.getOrNull() ?: return@mapNotNull null
            if ("\\" in value) "${field.name}: $value" else null
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the messages about a row that would not read quote the value`() {
        val rendered = context.getString(R.string.settings_import_bad_weight, 4, "abc")

        assertThat(rendered).isEqualTo("Row 4: \"abc\" is not a supported weight")
    }
}
