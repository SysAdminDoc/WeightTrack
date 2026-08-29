package com.weighttrack.data.io

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Which copies are kept and which are removed.
 *
 * The feature exists so that nobody loses their history, so the rule that deletes things is the
 * part worth being sure about. Deleting a file the app did not write would be the worst possible
 * outcome of a backup feature.
 */
class AutoBackupTest {

    private fun names(vararg dates: String) = dates.map { "weighttrack-$it.json" }

    @Test
    fun `a backup is named after the day it was taken`() {
        assertThat(AutoBackup.nameFor(LocalDate.of(2026, 8, 29)))
            .isEqualTo("weighttrack-2026-08-29.json")
    }

    @Test
    fun `the name sorts the same way the dates do`() {
        // Built with nameFor rather than a copy of its format, or a name that no longer sorted
        // would leave this green while the pruning deleted the wrong file.
        val days = listOf(
            LocalDate.of(2026, 12, 31),
            LocalDate.of(2026, 1, 5),
            LocalDate.of(2026, 8, 29),
        )

        val byName = days.map(AutoBackup::nameFor).sorted()

        assertThat(byName).isEqualTo(days.sorted().map(AutoBackup::nameFor))
    }

    @Test
    fun `a name it did not write has no date`() {
        assertThat(AutoBackup.dateOf("weighttrack-2026-08-29.json"))
            .isEqualTo(LocalDate.of(2026, 8, 29))
        assertThat(AutoBackup.dateOf("holiday-photos.json")).isNull()
        assertThat(AutoBackup.dateOf("weighttrack-notadate.json")).isNull()
        assertThat(AutoBackup.dateOf("weighttrack-2026-08-29.txt")).isNull()
        assertThat(AutoBackup.dateOf("weighttrack-2026-13-40.json")).isNull()
    }

    @Test
    fun `nothing is removed until there are more than four`() {
        val four = names("2026-08-01", "2026-08-08", "2026-08-15", "2026-08-22")

        assertThat(AutoBackup.toRemove(four)).isEmpty()
    }

    @Test
    fun `the oldest goes once there is a fifth`() {
        val five = names("2026-08-01", "2026-08-08", "2026-08-15", "2026-08-22", "2026-08-29")

        assertThat(AutoBackup.toRemove(five)).containsExactly("weighttrack-2026-08-01.json")
    }

    @Test
    fun `several extra all go, oldest first`() {
        val seven = names(
            "2026-07-18", "2026-07-25", "2026-08-01", "2026-08-08",
            "2026-08-15", "2026-08-22", "2026-08-29",
        )

        // Seven kept four leaves three to go, newest of those first.
        assertThat(AutoBackup.toRemove(seven)).containsExactly(
            "weighttrack-2026-08-01.json",
            "weighttrack-2026-07-25.json",
            "weighttrack-2026-07-18.json",
        ).inOrder()
    }

    @Test
    fun `the four that survive are the newest four`() {
        val seven = names(
            "2026-07-18", "2026-07-25", "2026-08-01", "2026-08-08",
            "2026-08-15", "2026-08-22", "2026-08-29",
        )

        val kept = seven - AutoBackup.toRemove(seven).toSet()

        assertThat(kept).containsExactly(
            "weighttrack-2026-08-08.json",
            "weighttrack-2026-08-15.json",
            "weighttrack-2026-08-22.json",
            "weighttrack-2026-08-29.json",
        )
    }

    @Test
    fun `somebody else's files in the folder are never touched`() {
        val mixed = names("2026-08-01", "2026-08-08", "2026-08-15", "2026-08-22", "2026-08-29") +
            listOf("tax-return.pdf", "notes.json", "weighttrack-old.json")

        val removing = AutoBackup.toRemove(mixed)

        assertThat(removing).containsExactly("weighttrack-2026-08-01.json")
        assertThat(removing).doesNotContain("notes.json")
        assertThat(removing).doesNotContain("tax-return.pdf")
        assertThat(removing).doesNotContain("weighttrack-old.json")
    }

    @Test
    fun `an empty folder removes nothing`() {
        assertThat(AutoBackup.toRemove(emptyList())).isEmpty()
    }

    @Test
    fun `the newest is listed first, whatever order the folder gave them`() {
        val shuffled = names("2026-08-15", "2026-08-01", "2026-08-29")

        assertThat(AutoBackup.backupsIn(shuffled).first())
            .isEqualTo("weighttrack-2026-08-29.json")
    }

    @Test
    fun `a second run on the same day replaces rather than adds`() {
        // The worker looks for this name before creating anything. Two files for one day would
        // fill the folder with copies of today instead of a month of history, and the pruning
        // would then drop real history to make room.
        val name = AutoBackup.nameFor(LocalDate.of(2026, 8, 29))
        val folder = names("2026-08-22") + name

        assertThat(AutoBackup.backupsIn(folder)).containsExactly(name, "weighttrack-2026-08-22.json")
            .inOrder()
        assertThat(AutoBackup.toRemove(folder)).isEmpty()
        assertThat(
            java.io.File("src/main/java/com/weighttrack/data/io/AutoBackupWorker.kt").readText(),
        ).contains("folder.findFile(name)")
    }
}
