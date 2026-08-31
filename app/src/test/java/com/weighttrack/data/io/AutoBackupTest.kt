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

    private fun names(vararg dates: String) = dates.map { "weighttrack-$it-weekly.json" }

    @Test
    fun `a backup carries the day it was taken and a name of its own`() {
        // Not weighttrack-2026-08-29.json: that is what the export button suggests, and the
        // pruning works by name, so a backup somebody saved here by hand was counted as one of
        // the four kept and thrown out to make room for a fifth.
        assertThat(AutoBackup.nameFor(LocalDate.of(2026, 8, 29)))
            .isEqualTo("weighttrack-2026-08-29-weekly.json")
        assertThat(AutoBackup.partialNameFor(LocalDate.of(2026, 8, 29)))
            .isEqualTo("weighttrack-2026-08-29-part-weekly.json")
    }

    @Test
    fun `a backup saved by hand is never touched, whichever kind it is`() {
        val theirs = listOf(
            "weighttrack-2026-07-04.json",
            "weighttrack-2026-07-04.csv",
            "weighttrack-2026-07-04-part.json",
        )
        val folder = theirs + (0..4).flatMap {
            val day = LocalDate.of(2026, 8, 29).minusWeeks(it.toLong())
            listOf(AutoBackup.nameFor(day), AutoBackup.csvNameFor(day))
        }

        assertThat(AutoBackup.toRemove(folder)).containsNoneIn(theirs)
        assertThat(AutoBackup.partialsIn(folder)).containsNoneIn(theirs)
        // Everything it did write is still counted and still pruned.
        assertThat(AutoBackup.toRemove(folder)).containsExactly(
            AutoBackup.nameFor(LocalDate.of(2026, 8, 1)),
            AutoBackup.csvNameFor(LocalDate.of(2026, 8, 1)),
        )
    }

    @Test
    fun `a backup written under the old name is left where it is`() {
        // At most four of these, once. The job cannot prove one of them is its own rather than a
        // copy somebody saved, and four stale files is a far better outcome than deleting theirs.
        val legacy = (0..4).map { "weighttrack-2026-08-0$it.json" }

        assertThat(AutoBackup.toRemove(legacy, keep = 0)).isEmpty()
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
        assertThat(AutoBackup.dateOf("weighttrack-2026-08-29-weekly.json"))
            .isEqualTo(LocalDate.of(2026, 8, 29))
        assertThat(AutoBackup.dateOf("holiday-photos.json")).isNull()
        assertThat(AutoBackup.dateOf("weighttrack-notadate.json")).isNull()
        assertThat(AutoBackup.dateOf("weighttrack-2026-08-29.txt")).isNull()
        // The name the export button suggests, which the job must not claim as its own.
        assertThat(AutoBackup.dateOf("weighttrack-2026-08-29.json")).isNull()
        assertThat(AutoBackup.dateOf("weighttrack-2026-13-40-weekly.json")).isNull()
    }

    @Test
    fun `nothing is removed until there are more than four`() {
        val four = names("2026-08-01", "2026-08-08", "2026-08-15", "2026-08-22")

        assertThat(AutoBackup.toRemove(four)).isEmpty()
    }

    @Test
    fun `the oldest goes once there is a fifth`() {
        val five = names("2026-08-01", "2026-08-08", "2026-08-15", "2026-08-22", "2026-08-29")

        assertThat(AutoBackup.toRemove(five)).containsExactly("weighttrack-2026-08-01-weekly.json")
    }

    @Test
    fun `several extra all go, oldest first`() {
        val seven = names(
            "2026-07-18", "2026-07-25", "2026-08-01", "2026-08-08",
            "2026-08-15", "2026-08-22", "2026-08-29",
        )

        // Seven kept four leaves three to go, newest of those first.
        assertThat(AutoBackup.toRemove(seven)).containsExactly(
            "weighttrack-2026-08-01-weekly.json",
            "weighttrack-2026-07-25-weekly.json",
            "weighttrack-2026-07-18-weekly.json",
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
            "weighttrack-2026-08-08-weekly.json",
            "weighttrack-2026-08-15-weekly.json",
            "weighttrack-2026-08-22-weekly.json",
            "weighttrack-2026-08-29-weekly.json",
        )
    }

    @Test
    fun `somebody else's files in the folder are never touched`() {
        val mixed = names("2026-08-01", "2026-08-08", "2026-08-15", "2026-08-22", "2026-08-29") +
            listOf("tax-return.pdf", "notes.json", "weighttrack-old.json")

        val removing = AutoBackup.toRemove(mixed)

        assertThat(removing).containsExactly("weighttrack-2026-08-01-weekly.json")
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
            .isEqualTo("weighttrack-2026-08-29-weekly.json")
    }

    @Test
    fun `a second run on the same day replaces rather than adds`() {
        // The worker looks for this name before creating anything. Two files for one day would
        // fill the folder with copies of today instead of a month of history, and the pruning
        // would then drop real history to make room.
        val name = AutoBackup.nameFor(LocalDate.of(2026, 8, 29))
        val folder = names("2026-08-22") + name

        assertThat(AutoBackup.backupsIn(folder)).containsExactly(name, "weighttrack-2026-08-22-weekly.json")
            .inOrder()
        assertThat(AutoBackup.toRemove(folder)).isEmpty()
        assertThat(
            java.io.File("src/main/java/com/weighttrack/data/io/AutoBackupWorker.kt").readText(),
        ).contains("folder.findFile(name)")
    }

    @Test
    fun `a half-written backup has a name of its own`() {
        val day = LocalDate.of(2026, 8, 29)

        val partial = AutoBackup.partialNameFor(day)

        assertThat(partial).isEqualTo("weighttrack-2026-08-29-part-weekly.json")
        // The marker sits inside the stem, not after the extension: a document provider rewrites
        // an extension that does not match the media type, and the file becomes unfindable.
        assertThat(partial).endsWith(".json")
        // It must not read as a backup, or the pruning would count it as one of the four kept
        // and the restore picker would offer a truncated file.
        assertThat(AutoBackup.dateOf(partial)).isNull()
        assertThat(AutoBackup.backupsIn(listOf(partial))).isEmpty()
    }

    @Test
    fun `leftovers from a run that died are found and nothing else is`() {
        val names = listOf(
            "weighttrack-2026-08-29-weekly.json",
            "weighttrack-2026-08-29-part-weekly.json",
            "weighttrack-2026-08-22-part-weekly.json",
            "holiday.jpg",
            "budget.json",
        )

        assertThat(AutoBackup.partialsIn(names))
            .containsExactly("weighttrack-2026-08-29-part-weekly.json", "weighttrack-2026-08-22-part-weekly.json")
    }

    @Test
    fun `the good copy is only given up once the new one has been read back`() {
        // A DocumentFile tree cannot be stood up in a unit test, so the order of the worker's
        // three steps is checked in its source. The order is the whole point: write elsewhere,
        // read it back, and only then touch the file somebody's history is in.
        val worker = java.io.File("src/main/java/com/weighttrack/data/io/AutoBackupWorker.kt")
            .readText()

        val writesElsewhere = worker.indexOf("partialNameFor")
        val provesIt = worker.indexOf("writeAndCheck(partial")
        val replaces = worker.indexOf("writeAndCheck(target")
        val givesUpTheProvedCopy = worker.indexOf("partial.delete()")

        assertThat(writesElsewhere).isGreaterThan(-1)
        assertThat(provesIt).isGreaterThan(writesElsewhere)
        assertThat(replaces).isGreaterThan(provesIt)
        assertThat(givesUpTheProvedCopy).isGreaterThan(replaces)
        // Nothing may rename the target on the way. A provider that refuses a rename, and
        // several cloud ones do, would otherwise leave the day with no backup at all.
        assertThat(worker).doesNotContain("renameTo")
        // And a target it could not finish writing has to go. Writing over a file truncates it,
        // and a truncated file that still carries a backup's name is worse than none: the
        // pruning counts it as one of the four kept and drops a real backup to make room.
        assertThat(worker).contains("target.delete()")
    }

    @Test
    fun `a truncated target would push a real backup out of the four that are kept`() {
        // Why the worker deletes a target it could not finish. The name alone is enough to count
        // as a backup: nothing opens these files to decide what to keep, and nothing could.
        val folder = names("2026-07-18", "2026-07-25", "2026-08-01", "2026-08-08") +
            AutoBackup.nameFor(LocalDate.of(2026, 8, 29))

        assertThat(AutoBackup.toRemove(folder)).containsExactly("weighttrack-2026-07-18-weekly.json")
    }

    @Test
    fun `the spreadsheet carries the same date and a name of its own`() {
        val day = LocalDate.of(2026, 8, 29)
        // Not weighttrack-2026-08-29.csv: that is what the manual export suggests, and a
        // scheduled file sharing the name means the pruning deletes somebody's own export.
        assertThat(AutoBackup.csvNameFor(day)).isEqualTo("weighttrack-2026-08-29-weekly.csv")
        assertThat(AutoBackup.partialCsvNameFor(day))
            .isEqualTo("weighttrack-2026-08-29-part-weekly.csv")
        assertThat(AutoBackup.dateOf(AutoBackup.csvNameFor(day), AutoBackup.CSV_SUFFIX))
            .isEqualTo(day)
    }

    @Test
    fun `the two kinds are counted apart from each other`() {
        // Five of each. Counted together the newest four names would be backups alone, so every
        // spreadsheet in the folder would be thrown out to make room for them.
        val folder = (0..4).flatMap {
            val day = LocalDate.of(2026, 8, 29).minusWeeks(it.toLong())
            listOf(AutoBackup.nameFor(day), AutoBackup.csvNameFor(day))
        }

        assertThat(AutoBackup.toRemove(folder)).containsExactly(
            AutoBackup.nameFor(LocalDate.of(2026, 8, 1)),
            AutoBackup.csvNameFor(LocalDate.of(2026, 8, 1)),
        )
    }

    @Test
    fun `a spreadsheet saved by hand is never touched`() {
        // The manual export suggests weighttrack-<date>.csv, so a scheduled one must not be
        // called that. A folder is somebody's own and deleting a file the app did not write is
        // the worst possible outcome of a backup feature.
        val byHand = "weighttrack-2026-07-04.csv"
        val folder = (0..4).map {
            AutoBackup.csvNameFor(LocalDate.of(2026, 8, 29).minusWeeks(it.toLong()))
        } + byHand

        assertThat(AutoBackup.toRemove(folder)).doesNotContain(byHand)
        assertThat(AutoBackup.dateOf(byHand, AutoBackup.CSV_SUFFIX)).isNull()
    }

    @Test
    fun `a half-written spreadsheet is collected like a half-written backup`() {
        val day = LocalDate.of(2026, 8, 29)
        val folder = listOf(
            AutoBackup.partialNameFor(day),
            AutoBackup.partialCsvNameFor(day),
            AutoBackup.nameFor(day),
            "notes.txt",
        )

        assertThat(AutoBackup.partialsIn(folder)).containsExactly(
            AutoBackup.partialNameFor(day),
            AutoBackup.partialCsvNameFor(day),
        )
        // And never counted as a backup, or the pruning throws out a real one to make room.
        assertThat(AutoBackup.toRemove(folder, keep = 0))
            .containsExactly(AutoBackup.nameFor(day))
    }

    @Test
    fun `something of somebody else's that merely looks half-written is left alone`() {
        // Matched on the shape of the name alone, any of these was deleted on the next
        // successful run.
        val theirs = listOf(
            "weighttrack-export-part.csv",
            "weighttrack-part.json",
            "weighttrack-notes-part.json",
        )

        assertThat(AutoBackup.partialsIn(theirs)).isEmpty()
        assertThat(AutoBackup.toRemove(theirs, keep = 0)).isEmpty()
    }
}