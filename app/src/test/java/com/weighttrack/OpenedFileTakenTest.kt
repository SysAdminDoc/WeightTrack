package com.weighttrack

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whether a file handed over from outside is still waiting to be shown.
 *
 * The intent carrying it is never consumed, so it is still on the activity every time the
 * activity is built again, which a rotation, a change of theme and a restore after the app was
 * killed all do. Without something that outlives the activity saying the file had already been
 * dealt with, a spreadsheet imported itself a second time and a backup asked to be restored
 * again after the question had been answered.
 */
@RunWith(RobolectricTestRunner::class)
class OpenedFileTakenTest {

    private val file: Uri = Uri.parse("content://com.example.files/weighttrack-2026-09-04.json")

    @Test
    fun `a file nobody has been shown yet is still waiting`() {
        assertThat(fileStillToShow(file, alreadyShown = null)).isEqualTo(file)
    }

    @Test
    fun `the same file after it has been shown is not offered again`() {
        // The rotation case: onCreate reads the very same address off the very same intent.
        assertThat(fileStillToShow(file, alreadyShown = Uri.parse(file.toString()))).isNull()
    }

    @Test
    fun `a different file is still offered while an earlier one is remembered`() {
        val other = Uri.parse("content://com.example.files/january.csv")
        assertThat(fileStillToShow(other, alreadyShown = file)).isEqualTo(other)
    }

    @Test
    fun `an ordinary launch carries no file`() {
        assertThat(fileStillToShow(handedOver = null, alreadyShown = null)).isNull()
        assertThat(fileStillToShow(handedOver = null, alreadyShown = file)).isNull()
    }
}
