package com.weighttrack.core.io

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenedFileTest {

    @Test
    fun `a declared type is believed where it says something specific`() {
        assertThat(OpenedFile.kindOf("application/json", null)).isEqualTo(OpenedFileKind.BACKUP)
        assertThat(OpenedFile.kindOf("text/csv", null)).isEqualTo(OpenedFileKind.READINGS)
    }

    @Test
    fun `a charset on the end of the type changes nothing`() {
        assertThat(OpenedFile.kindOf("text/csv; charset=utf-8", null))
            .isEqualTo(OpenedFileKind.READINGS)
        assertThat(OpenedFile.kindOf("APPLICATION/JSON", null)).isEqualTo(OpenedFileKind.BACKUP)
    }

    @Test
    fun `a file manager handing over a generic type falls back to the name`() {
        // What a lot of them actually send, and refusing it would lose somebody their backup to
        // a "not supported" message about a file that is perfectly fine.
        assertThat(OpenedFile.kindOf("application/octet-stream", "weighttrack-backup.json"))
            .isEqualTo(OpenedFileKind.BACKUP)
        assertThat(OpenedFile.kindOf(null, "readings.csv")).isEqualTo(OpenedFileKind.READINGS)
    }

    @Test
    fun `anything else is nothing this app can read`() {
        assertThat(OpenedFile.kindOf("image/jpeg", "photo.jpg")).isNull()
        assertThat(OpenedFile.kindOf("application/pdf", "letter.pdf")).isNull()
        assertThat(OpenedFile.kindOf(null, null)).isNull()
        assertThat(OpenedFile.kindOf("application/octet-stream", "backup")).isNull()
    }

    @Test
    fun `only the end of a name counts`() {
        // "january.csv.txt" is a text file whatever the middle of its name says.
        assertThat(OpenedFile.kindOf(null, "january.csv.txt")).isNull()
        assertThat(OpenedFile.kindOf(null, "csv")).isNull()
        assertThat(OpenedFile.kindOf(null, "notes.json.bak")).isNull()
    }

    @Test
    fun `the name decides only when the type says nothing useful`() {
        // A share sheet labels plenty of things application/json. Where the type is specific it
        // is what the app that wrote the file said, and it wins.
        assertThat(OpenedFile.kindOf("text/csv", "backup.json")).isEqualTo(OpenedFileKind.READINGS)
    }
}
