package com.weighttrack.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class CrashLogStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var directory: File
    private lateinit var store: CrashLogStore

    private val buildInfo = "WeightTrack 0.3.0 (play)"

    @Before
    fun setUp() {
        directory = File(temporaryFolder.root, "crash-logs")
        store = CrashLogStore(directory)
    }

    private fun boom(message: String? = "something broke"): Throwable =
        IllegalStateException(message)

    @Test
    fun `a report is written and can be listed`() {
        val written = store.write(boom(), "main", buildInfo)!!
        val listed = store.list()

        assertThat(listed).hasSize(1)
        assertThat(listed.single().id).isEqualTo(written.id)
        assertThat(listed.single().summary)
            .isEqualTo("java.lang.IllegalStateException: something broke")
    }

    @Test
    fun `the directory is created on first write`() {
        assertThat(directory.exists()).isFalse()
        assertThat(store.write(boom(), "main", buildInfo)).isNotNull()
        assertThat(directory.isDirectory).isTrue()
    }

    @Test
    fun `the report body carries the context needed to act on it`() {
        val report = store.write(boom(), "worker-3", buildInfo, Instant.ofEpochMilli(1_700_000_000_000))!!
        val body = store.read(report.id)!!

        assertThat(body).contains("java.lang.IllegalStateException: something broke")
        assertThat(body).contains("Thread: worker-3")
        assertThat(body).contains(buildInfo)
        assertThat(body).contains("2023-11-14T22:13:20Z")
        // The stack trace is the whole point; a summary without frames cannot be debugged.
        assertThat(body).contains("at com.weighttrack.diagnostics.CrashLogStoreTest")
    }

    @Test
    fun `an exception with no message still summarises usefully`() {
        val report = store.write(boom(message = null), "main", buildInfo)!!
        assertThat(report.summary).isEqualTo("java.lang.IllegalStateException")
        assertThat(store.list().single().summary).isEqualTo("java.lang.IllegalStateException")
    }

    @Test
    fun `a blank message does not produce a trailing colon`() {
        val report = store.write(boom(message = "   "), "main", buildInfo)!!
        assertThat(report.summary).isEqualTo("java.lang.IllegalStateException")
    }

    @Test
    fun `causes are preserved in the stack trace`() {
        val cause = IllegalArgumentException("the real reason")
        val wrapper = RuntimeException("wrapper", cause)
        val report = store.write(wrapper, "main", buildInfo)!!

        val body = store.read(report.id)!!
        assertThat(body).contains("Caused by: java.lang.IllegalArgumentException: the real reason")
    }

    @Test
    fun `reports are listed newest first`() {
        store.write(boom("first"), "main", buildInfo, Instant.ofEpochMilli(1_000))
        store.write(boom("second"), "main", buildInfo, Instant.ofEpochMilli(3_000))
        store.write(boom("third"), "main", buildInfo, Instant.ofEpochMilli(2_000))

        assertThat(store.list().map { it.summary })
            .containsExactly(
                "java.lang.IllegalStateException: second",
                "java.lang.IllegalStateException: third",
                "java.lang.IllegalStateException: first",
            )
            .inOrder()
    }

    @Test
    fun `a crash loop cannot fill the device`() {
        repeat(CrashLogStore.MAX_REPORTS + 15) { index ->
            store.write(boom("crash $index"), "main", buildInfo, Instant.ofEpochMilli(index + 1L))
        }
        assertThat(store.count()).isEqualTo(CrashLogStore.MAX_REPORTS)
        // Pruning must drop the oldest, not the newest, or the report you need is the one gone.
        assertThat(store.list().first().summary).isEqualTo("java.lang.IllegalStateException: crash 34")
    }

    @Test
    fun `two crashes in the same millisecond both survive`() {
        // A crash loop can fire several times inside one millisecond. Sharing a filename would
        // mean the second write silently replaced the first, losing the earlier report.
        val moment = Instant.ofEpochMilli(1_700_000_000_000)
        val first = store.write(boom("first"), "main", buildInfo, moment)!!
        val second = store.write(boom("second"), "main", buildInfo, moment)!!
        val third = store.write(boom("third"), "main", buildInfo, moment)!!

        assertThat(setOf(first.id, second.id, third.id)).hasSize(3)
        assertThat(store.count()).isEqualTo(3)
        assertThat(store.list().map { it.summary }).containsExactly(
            "java.lang.IllegalStateException: first",
            "java.lang.IllegalStateException: second",
            "java.lang.IllegalStateException: third",
        )
        // Every report must still be readable through the id it was reported under.
        assertThat(store.read(first.id)).contains("first")
        assertThat(store.read(second.id)).contains("second")
        assertThat(store.read(third.id)).contains("third")
    }

    @Test
    fun `a clock that steps backwards does not overwrite a newer report`() {
        val later = store.write(boom("later"), "main", buildInfo, Instant.ofEpochMilli(5_000))!!
        // The device clock corrects backwards onto an occupied slot.
        val earlier = store.write(boom("earlier"), "main", buildInfo, Instant.ofEpochMilli(5_000))!!

        assertThat(earlier.id).isNotEqualTo(later.id)
        assertThat(store.count()).isEqualTo(2)
        assertThat(store.read(later.id)).contains("later")
    }

    @Test
    fun `deleting one report leaves the rest`() {
        val first = store.write(boom("first"), "main", buildInfo, Instant.ofEpochMilli(1_000))!!
        store.write(boom("second"), "main", buildInfo, Instant.ofEpochMilli(2_000))

        assertThat(store.delete(first.id)).isTrue()
        assertThat(store.list()).hasSize(1)
        assertThat(store.read(first.id)).isNull()
    }

    @Test
    fun `clearing removes every report`() {
        repeat(4) { store.write(boom("crash $it"), "main", buildInfo, Instant.ofEpochMilli(it + 1L)) }
        store.deleteAll()
        assertThat(store.list()).isEmpty()
        assertThat(store.count()).isEqualTo(0)
    }

    @Test
    fun `clearing leaves unrelated files alone`() {
        directory.mkdirs()
        val bystander = File(directory, "notes.txt").apply { writeText("keep me") }
        store.write(boom(), "main", buildInfo)

        store.deleteAll()
        assertThat(bystander.exists()).isTrue()
    }

    @Test
    fun `files that are not reports are ignored when listing`() {
        directory.mkdirs()
        File(directory, "notes.txt").writeText("not a crash")
        File(directory, "crash-notanumber.txt").writeText("not a crash either")
        File(directory, "crash-123.log").writeText("wrong extension")
        store.write(boom(), "main", buildInfo)

        assertThat(store.list()).hasSize(1)
    }

    @Test
    fun `an id from outside the directory is refused`() {
        directory.mkdirs()
        val outside = File(temporaryFolder.root, "crash-999.txt").apply { writeText("secret") }

        // The id round-trips through the UI, so a traversal attempt must not resolve.
        assertThat(store.read("../crash-999.txt")).isNull()
        assertThat(store.delete("../crash-999.txt")).isFalse()
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun `reading an unknown id returns nothing rather than throwing`() {
        assertThat(store.read("crash-1.txt")).isNull()
        assertThat(store.delete("crash-1.txt")).isFalse()
    }

    @Test
    fun `an empty or missing directory lists nothing`() {
        assertThat(store.list()).isEmpty()
        assertThat(store.count()).isEqualTo(0)
    }

    @Test
    fun `a report whose body is empty still lists with a fallback summary`() {
        directory.mkdirs()
        File(directory, "crash-500.txt").writeText("")
        assertThat(store.list().single().summary).isEqualTo("Unknown crash")
    }

    @Test
    fun `a write that cannot create its directory reports failure instead of throwing`() {
        // A file where the directory should be: mkdirs fails, and the handler must survive it.
        val blocked = File(temporaryFolder.root, "blocked").apply { writeText("in the way") }
        assertThat(CrashLogStore(blocked).write(boom(), "main", buildInfo)).isNull()
    }
}
