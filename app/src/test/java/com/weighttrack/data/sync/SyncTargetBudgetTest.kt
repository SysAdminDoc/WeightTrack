package com.weighttrack.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Both places a remote answer arrives read it with a ceiling.
 *
 * Neither transport can be driven from a unit test: one needs a document tree and the other a
 * server, and the bound belongs underneath the seam a test could reach, because putting it above
 * would mean testing the fake rather than the recovery. So the source says it, and this holds it
 * to that.
 *
 * What it is guarding against is not subtle. Both used to read a whole remote answer into memory
 * before deciding whether it was even one of ours, and neither had any limit at all: a file in a
 * shared folder is written by something outside this app, and a WebDAV server is somebody else's
 * machine.
 */
class SyncTargetBudgetTest {

    private fun source(name: String): String =
        File("src/main/java/com/weighttrack/data/sync/$name").readText()

    @Test
    fun `the folder target reads through the ceiling`() {
        val folder = source("FolderSyncTarget.kt")

        assertThat(folder).contains("SyncBudget.readBounded")
        // And never the unbounded call it used to make.
        assertThat(folder).doesNotContain("readBytes().decodeToString()")
    }

    @Test
    fun `the server target reads through the ceiling`() {
        val webdav = source("WebDavSyncTarget.kt")

        assertThat(webdav).contains("SyncBudget")
        assertThat(webdav).contains("readBounded(")
        // `body.string()` reads the whole response however long it is, which is the call that
        // made an oversized answer a crash rather than a refusal.
        assertThat(webdav).doesNotContain("response.body.string()")
    }

    @Test
    fun `both say which file or server it was`() {
        // A refusal nobody can act on is only half of one. The name of the file, or the address
        // of the server, is what turns "sync stopped" into something somebody can go and look at.
        assertThat(source("FolderSyncTarget.kt")).contains("sync_file_too_large")
        assertThat(source("WebDavSyncTarget.kt")).contains("sync_file_too_large")
    }
}
